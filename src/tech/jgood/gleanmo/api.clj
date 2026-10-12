(ns tech.jgood.gleanmo.api
  "The agent API: a bearer-token JSON surface over the same queries and
   mutations the web app uses.

   Every request is scoped three ways before it reaches the database layer:
   to the token's user, to the entity groups the token was granted, and to the
   sensitive/archived visibility the token was granted. Reads reuse
   `all-entities-for-user` and `visible-entity-for-user`, so a token sees
   exactly what the app would show with the same visibility settings —
   including hiding a log whose parent habit is sensitive.

   Writes go through one endpoint, `POST /api/v1/tx`, which takes a list of
   create/update/delete ops, checks all of them, and writes all or none in a
   single transaction. `dry_run` returns the checked plan without writing, so
   an agent can show the user a batch before applying it."
  (:require
   [clojure.string :as str]
   [clojure.tools.logging :as log]
   [malli.core :as m]
   [malli.error :as me]
   [tech.jgood.gleanmo.api.access :as access]
   [tech.jgood.gleanmo.api.coerce :as coerce]
   [tech.jgood.gleanmo.cronometer.nutrients :as nutrients]
   [tech.jgood.gleanmo.db.mutations :as mutations]
   [tech.jgood.gleanmo.db.queries :as queries]
   [tech.jgood.gleanmo.schema.meta :as sm]
   [tick.core :as t]))

;; ---------------------------------------------------------------------------
;; Responses
;; ---------------------------------------------------------------------------

(defn- json
  [status body]
  {:status status, :body body})

(defn- error
  ([status message] (error status message nil))
  ([status message details]
   (json status (cond-> {"error" message}
                  details (assoc "details" details)))))

(defn- str-keys
  "Request params with string keys, whichever way ring keywordized them
   (`task/state` contains a slash, so it may arrive as either)."
  [params]
  (into {} (map (fn [[k v]] [(if (keyword? k) (coerce/kw->str k) (str k)) v]))
        params))

;; ---------------------------------------------------------------------------
;; Middleware
;; ---------------------------------------------------------------------------

(def ^:private last-used-resolution
  "`last-used-at` is advisory; refreshing it at most this often keeps a busy
   agent from turning every read into a write."
  (java.time.Duration/ofMinutes 5))

(defn- touch-last-used!
  [ctx token-doc]
  (let [now  (t/now)
        last (:api-token/last-used-at token-doc)]
    (when (or (nil? last)
              (.isAfter now (.plus ^java.time.Instant last last-used-resolution)))
      (try
        (mutations/update-entity! ctx {:entity-key :api-token
                                       :entity-id  (:xt/id token-doc)
                                       :data       {:api-token/last-used-at now}})
        (catch Exception e
          (log/warn e "Could not record API token use"))))))

(defn wrap-api-token
  "Authenticate the request by bearer token and attach `:api/token` and
   `:api/user-id`. Session cookies are never consulted."
  [handler]
  (fn [{:keys [biff/db headers], :as ctx}]
    (let [token     (access/bearer-token (get headers "authorization"))
          token-doc (when token
                      (queries/api-token-by-hash db (access/hash-token token)))]
      (if (access/usable? token-doc)
        (do (touch-last-used! ctx token-doc)
            (handler (assoc ctx
                            :api/token   token-doc
                            :api/user-id (:user/id token-doc))))
        (error 401 "Missing, unknown, or revoked API token.")))))

(defn wrap-json-errors
  [handler]
  (fn [ctx]
    (try
      (handler ctx)
      (catch Exception e
        (log/error e "API request failed")
        (error 500 "Internal error.")))))

;; ---------------------------------------------------------------------------
;; Type resolution
;; ---------------------------------------------------------------------------

(defn- readable-type
  "The entity type named in the path if the token may read it, else nil.
   Unknown and forbidden types are indistinguishable to the caller."
  [token-doc type-str]
  (let [entity-type (keyword type-str)]
    (when (access/can? token-doc :read entity-type)
      entity-type)))

;; ---------------------------------------------------------------------------
;; GET /types
;; ---------------------------------------------------------------------------

(defn types-handler
  "The token's grants and every type it can read, with field descriptions."
  [{:keys [api/token]}]
  (json 200
        {"token"  {"label"       (:api-token/label token)
                   "prefix"      (:api-token/prefix token)
                   "permissions" (access/permissions-summary token)}
         "groups" (vec
                   (for [{:keys [key label types read-only]} access/groups
                         :when (access/can? token :read (first types))]
                     (cond-> {"group"  (name key)
                              "label"  label
                              "access" (if (access/can? token :write (first types))
                                         "write"
                                         "read")
                              "types"  (mapv (fn [entity-type]
                                               {"type"   (name entity-type)
                                                "fields" (coerce/type->json
                                                          entity-type
                                                          {:read-only read-only})})
                                             types)}
                       ;; Nutrient maps carry no units; this table does.
                       (= :nutrition key)
                       (assoc "nutrients"
                              (mapv (fn [{:keys [id unit] k :key :as row}]
                                      {"key"           (name k)
                                       "label"         (:label row)
                                       "unit"          (name unit)
                                       "category"      (:category row)
                                       "cronometer-id" id})
                                    nutrients/ordered)))))}))

;; ---------------------------------------------------------------------------
;; GET /entities/:type
;; ---------------------------------------------------------------------------

(def ^:private default-limit 50)
(def ^:private max-limit 500)

(def ^:private list-params
  #{"limit" "offset" "order-by" "order" "since" "q"
    "include-sensitive" "include-archived"})

(defn- parse-param-string
  "A query-string value as the JSON value `coerce-value` expects for `t`."
  [t s]
  (let [base (if (and (vector? t) (#{:maybe :set} (first t))) (second t) t)]
    (case base
      :boolean                         (case s "true" true "false" false s)
      (:int :positive-int :nonnegative-int) (or (parse-long s) s)
      (:number :float :double)         (or (parse-double s) s)
      s)))

(defn- filter-value
  "`{:value v}` / `{:error msg}` for an equality filter on `field-key`. A set
   field filters by membership, so its value is one element."
  [specs field-key s]
  (let [t    (:type (specs field-key))
        elem (if (and (vector? t) (= :set (first t))) (second t) t)]
    (coerce/coerce-value elem (parse-param-string elem s))))

(defn- parse-int-param
  [params k default]
  (let [s (get params k)]
    (cond
      (nil? s)       {:value default}
      (parse-long s) {:value (parse-long s)}
      :else          {:error (str k " must be an integer")})))

(defn parse-list-params
  "Validate and convert list query params for `entity-type`. Returns
   `{:opts opts}` for `all-entities-for-user`, or `{:errors [...]}`."
  [token-doc entity-type raw-params]
  (let [params     (str-keys raw-params)
        specs      (coerce/fields entity-type)
        own-field  (fn [s]
                     (let [k (keyword s)]
                       (when (and (coerce/own-field? entity-type k)
                                  (contains? specs k))
                         k)))
        limit      (parse-int-param params "limit" default-limit)
        offset     (parse-int-param params "offset" 0)
        order-key  (some-> (get params "order-by") own-field)
        order      (get params "order" "desc")
        include-s  (= "true" (get params "include-sensitive"))
        include-a  (= "true" (get params "include-archived"))
        filter-ks  (remove list-params (keys params))
        unknown    (remove own-field filter-ks)
        where      (into {}
                         (for [s (filter own-field filter-ks)]
                           [(keyword s) (filter-value specs (keyword s)
                                                      (str (get params s)))]))
        since      (when-let [s (get params "since")]
                     (if order-key
                       (coerce/coerce-value (:type (specs order-key)) s)
                       {:error "since requires order-by"}))
        errors     (cond-> (access/visibility-errors
                            token-doc {:include-sensitive include-s
                                       :include-archived  include-a})
                     (:error limit)  (conj (:error limit))
                     (:error offset) (conj (:error offset))
                     (and (:value limit)
                          (not (<= 1 (:value limit) max-limit)))
                     (conj (str "limit must be between 1 and " max-limit))
                     (and (:value offset) (neg? (:value offset)))
                     (conj "offset must not be negative")
                     (and (get params "order-by") (nil? order-key))
                     (conj (str "order-by must be a field of " (name entity-type)))
                     (not (#{"asc" "desc"} order))
                     (conj "order must be asc or desc")
                     (:error since)  (conj (str "since: " (:error since)))
                     (seq unknown)
                     (conj (str "unknown params: " (str/join ", " unknown)))
                     true
                     (into (keep (fn [[k {e :error}]]
                                   (when e (str (coerce/kw->str k) ": " e))))
                           where))]
    (if (seq errors)
      {:errors errors}
      {:opts (cond-> {:filter-sensitive  include-s
                      :filter-archived   include-a
                      :filter-references true
                      :limit             (:value limit)
                      :offset            (:value offset)
                      :order-direction   (keyword order)}
               order-key            (assoc :order-key order-key)
               (:value since)       (assoc :since (:value since))
               (seq where)          (assoc :where (update-vals where :value))
               (get params "q")     (assoc :text (get params "q")))})))

(defn list-handler
  [{:keys [api/token api/user-id biff/db path-params params]}]
  (if-let [entity-type (readable-type token (:type path-params))]
    (let [{:keys [opts errors]} (parse-list-params token entity-type params)]
      (if errors
        (error 400 "Invalid list request." errors)
        (let [items (apply queries/all-entities-for-user db user-id entity-type
                           (mapcat identity opts))]
          (json 200
                (cond-> {"type"   (name entity-type)
                         "limit"  (:limit opts)
                         "offset" (:offset opts)
                         "count"  (count items)
                         "items"  (mapv coerce/doc->json items)}
                  (= (count items) (:limit opts))
                  (assoc "next-offset" (+ (:offset opts) (:limit opts))))))))
    (error 404 "Unknown entity type, or this token cannot read it.")))

;; ---------------------------------------------------------------------------
;; GET /entities/:type/:id
;; ---------------------------------------------------------------------------

(defn- visible-entity
  "The token-visible document, or nil. A by-id read sees everything the token
   was granted; only list reads need sensitive/archived asked for, since they
   are the ones that sweep."
  [db token-doc entity-type id]
  (queries/visible-entity-for-user db (:user/id token-doc) entity-type id
                                   :show-sensitive (access/sensitive-granted? token-doc)
                                   :show-archived  (access/archived-granted? token-doc)))

(defn get-handler
  [{:keys [api/token biff/db path-params]}]
  (let [entity-type (readable-type token (:type path-params))
        id          (parse-uuid (str (:id path-params)))
        doc         (when (and entity-type id)
                      (visible-entity db token entity-type id))]
    (if doc
      (json 200 (coerce/doc->json doc))
      (error 404 "Not found."))))

;; ---------------------------------------------------------------------------
;; POST /tx
;; ---------------------------------------------------------------------------

(def ^:private max-ops 200)

(defn- merged
  [stored data]
  (reduce-kv (fn [doc k v]
               (if (= :db/dissoc v) (dissoc doc k) (assoc doc k v)))
             (or stored {})
             data))

(defn task-signals
  "The system-managed task fields a change to `data` implies, merged into it —
   the same bookkeeping the app's state and snooze actions do, so a task
   edited by an agent reads the same as one edited by hand."
  [stored data now]
  (let [new-state (:task/state data)
        snooze    (:task/snooze-until data)]
    (cond-> data
      (and new-state (not= new-state (:task/state stored)))
      (assoc :task/last-state-change-at now
             :task/state-change-count (inc (or (:task/state-change-count stored) 0)))

      (and (= :done new-state) (not= :done (:task/state stored)))
      (assoc :task/done-at now)

      (and (some? stored)
           (some? snooze)
           (not= :db/dissoc snooze)
           (not= snooze (:task/snooze-until stored)))
      (assoc :task/snooze-count (inc (or (:task/snooze-count stored) 0))))))

(defn- derived-data
  [entity-type stored data now]
  (if (= :task entity-type)
    (task-signals stored data now)
    data))

(defn- schema-errors
  [entity-type doc malli-opts]
  (some-> (m/explain entity-type doc malli-opts) me/humanize))

(defn- flag-errors
  "Setting a visibility flag needs the matching grant: a token that cannot see
   sensitive records must not be able to hide records from itself, or reveal
   ones it was never shown."
  [token-doc entity-type data]
  (cond-> []
    (and (contains? data (keyword (name entity-type) "sensitive"))
         (not (access/sensitive-granted? token-doc)))
    (conj "setting sensitive needs a token granted sensitive records")

    (and (contains? data (keyword (name entity-type) "archived"))
         (not (access/archived-granted? token-doc)))
    (conj "setting archived needs a token granted archived records")))

(defn- expect-errors
  "Optimistic-concurrency check: each `expect` field must still hold the given
   value (null = absent). Rejects edits planned against a stale read."
  [entity-type stored expect]
  (let [specs (coerce/fields entity-type)]
    (into []
          (keep (fn [[k v]]
                  (let [field-key (keyword (if (keyword? k) (coerce/kw->str k) (str k)))
                        spec      (specs field-key)
                        expected  (cond
                                    (nil? spec) {:error "not a field"}
                                    (nil? v)    {:value nil}
                                    :else       (coerce/coerce-value (:type spec) v))]
                    (cond
                      (:error expected)
                      (str "expect " (coerce/kw->str field-key) ": " (:error expected))

                      (not= (:value expected) (get stored field-key))
                      (str "stale: " (coerce/kw->str field-key) " is now "
                           (pr-str (coerce/->json-value (get stored field-key))))))))
          expect)))

(defn- relation-refs
  "`{target-type #{ids}}` for every relation value `data` sets."
  [entity-type data]
  (let [specs (coerce/fields entity-type)]
    (reduce-kv (fn [acc k v]
                 (if-let [target (and (not= :db/dissoc v)
                                      (coerce/own-field? entity-type k)
                                      (coerce/relation-target (:type (specs k))))]
                   (update acc target (fnil into #{}) (if (set? v) v [v]))
                   acc))
               {}
               data)))

(defn- changes
  "`{field {from to}}` over the entity's own fields."
  [entity-type before after]
  (into (sorted-map)
        (keep (fn [k]
                (let [a (get before k) b (get after k)]
                  (when (and (coerce/own-field? entity-type k) (not= a b))
                    [(coerce/kw->str k) {"from" (coerce/->json-value a)
                                         "to"   (coerce/->json-value b)}]))))
        (distinct (concat (keys before) (keys after)))))

(defn- resolve-op
  "Check one op against the token and the database. Returns
   `{:op :entity-type :id :stored :data :doc :errors}`; `errors` is a vector of
   strings and empty when the op may be written."
  [{:keys [db token now malli-opts]} {:keys [op type id expect], changes-in :set}]
  (let [op-kw       (#{:create :update :delete} (keyword (str op)))
        entity-type (keyword (str type))
        writable    (access/can? token :write entity-type)
        id-given    (some? id)
        id          (when id (parse-uuid (str id)))
        base-errors (cond-> []
                      (nil? op-kw)
                      (conj "op must be create, update, or delete")
                      (not writable)
                      (conj (str "unknown type, or this token cannot write "
                                 (pr-str type)))
                      (and id-given (nil? id))
                      (conj "id must be a uuid")
                      (and (#{:update :delete} op-kw) (not id-given))
                      (conj (str (name op-kw) " needs an id"))
                      (and (#{:create :update} op-kw)
                           (not (and (map? changes-in) (seq changes-in))))
                      (conj (str (some-> op-kw name) " needs a non-empty set"))
                      (and (= :delete op-kw) (some? changes-in))
                      (conj "delete takes no set")
                      (and (= :create op-kw) (some? expect))
                      (conj "create takes no expect")
                      (and (some? expect) (not (map? expect)))
                      (conj "expect must be an object"))]
    (if (seq base-errors)
      {:op op-kw, :entity-type entity-type, :id id, :errors base-errors}
      (let [{:keys [data] coerce-errs :errors} (if (= :delete op-kw)
                                                 {:data {}, :errors {}}
                                                 (coerce/coerce-changes
                                                  entity-type changes-in))
            field-errors (mapv (fn [[k e]] (str (coerce/kw->str k) ": " e))
                               coerce-errs)
            flag-errs    (flag-errors token entity-type data)]
        (case op-kw
          :create
          (let [stored-dupe (when id (queries/get-entity-by-id db id))
                id          (or id (random-uuid))
                data        (-> (into {} (remove (fn [[_ v]] (= :db/dissoc v))) data)
                                (#(derived-data entity-type nil % now))
                                (assoc :xt/id id :user/id (:user/id token)))
                doc         (mutations/entity-doc entity-type data)
                schema      (when (empty? field-errors)
                              (schema-errors entity-type doc malli-opts))]
            {:op :create, :entity-type entity-type, :id id, :data data, :doc doc
             :id-generated (not id-given)
             :errors (cond-> (into field-errors flag-errs)
                       stored-dupe (conj "id is already in use")
                       schema      (conj (str "schema: " (pr-str schema))))})

          (:update :delete)
          (let [stored (visible-entity db token entity-type id)
                data   (if (= :delete op-kw)
                         {::sm/deleted-at now}
                         (derived-data entity-type stored data now))
                doc    (when stored (merged stored data))
                schema (when (and stored (= :update op-kw) (empty? field-errors))
                         (schema-errors entity-type doc malli-opts))]
            {:op op-kw, :entity-type entity-type, :id id, :stored stored
             :data data, :doc doc
             :errors (if (nil? stored)
                       ["not found"]
                       (cond-> (-> field-errors
                                   (into flag-errs)
                                   (into (expect-errors entity-type stored expect)))
                         schema (conj (str "schema: " (pr-str schema)))))}))))))

(defn- batch-errors
  "Cross-op problems: one entity touched twice, two creates claiming an id,
   and relation values pointing at records the user does not own (or that no
   create in this batch makes)."
  [db user-id resolved]
  (let [ids      (keep :id resolved)
        dupes    (set (keep (fn [[x n]] (when (< 1 n) x)) (frequencies ids)))
        created  (into {} (keep #(when (and (= :create (:op %)) (empty? (:errors %)))
                                   [(:id %) (:entity-type %)]))
                       resolved)
        refs     (apply merge-with into
                        (map #(relation-refs (:entity-type %) (:data %))
                             (remove #(= :delete (:op %)) resolved)))
        owned    (into #{}
                       (mapcat (fn [[target ids]]
                                 (into (queries/owned-entity-ids db user-id target ids)
                                       (filter #(= target (created %)) ids))))
                       refs)]
    (mapv (fn [{:keys [entity-type data id] :as r}]
            (let [missing (->> (relation-refs entity-type data)
                               (mapcat val)
                               (remove owned)
                               distinct)]
              (cond-> r
                (dupes id)
                (update :errors conj "the same id appears in more than one op")
                (seq missing)
                (update :errors conj (str "unknown related ids: "
                                          (str/join ", " missing))))))
          resolved)))

(defn resolve-ops
  "Check every op. Returns the resolved ops in input order."
  [{:keys [db token] :as env} ops]
  (batch-errors db (:user/id token) (mapv #(resolve-op env %) ops)))

(defn- op->json
  "`dry-run?` hides an id the server generated: the commit generates a new one,
   so showing it would promise an id the write will not use."
  [dry-run? index {:keys [op entity-type id id-generated stored doc errors]}]
  (cond-> {"index"  index
           "op"     (some-> op name)
           "type"   (some-> entity-type name)
           "id"     (when-not (and dry-run? id-generated) (some-> id str))
           "errors" errors}
    (= :create op) (assoc "changes" (changes entity-type {} doc))
    (= :update op) (assoc "changes" (changes entity-type stored doc))
    (and (= :delete op) stored) (assoc "before" (coerce/doc->json stored))))

(defn commit!
  "Write a fully valid resolved batch in one transaction."
  [ctx resolved]
  (mutations/write-entities!
   ctx
   {:creates (vec (for [{:keys [op entity-type data]} resolved
                        :when (= :create op)]
                    {:entity-key entity-type, :data data}))
    :updates (vec (for [{:keys [op entity-type id data]} resolved
                        :when (#{:update :delete} op)]
                    {:entity-key entity-type, :entity-id id, :data data}))}))

(defn tx-handler
  "Check a batch of ops and, unless `dry_run`, write it whole or not at all.

   Body: `{\"dry_run\": bool, \"ops\": [op ...]}` where an op is
     {\"op\": \"create\", \"type\": \"task\", \"id\": uuid?, \"set\": {...}}
     {\"op\": \"update\", \"type\": \"task\", \"id\": uuid, \"set\": {...},
      \"expect\": {...}?}
     {\"op\": \"delete\", \"type\": \"task\", \"id\": uuid, \"expect\": {...}?}
   `set` keys are full field names (`task/state`); null removes a field. A
   create may choose its own id so later ops in the batch can relate to it,
   and so a dry run's plan names the id the commit will use: a dry run shows
   `id: null` for a create that leaves the id to the server. Resending a
   committed batch with its chosen ids fails rather than duplicating it."
  [{:keys [api/token biff/db body-params biff/malli-opts], :as ctx}]
  (let [{:keys [ops dry_run]} body-params]
    (cond
      (not (and (sequential? ops) (seq ops)))
      (error 400 "Body must hold a non-empty ops array.")

      (< max-ops (count ops))
      (error 400 (str "At most " max-ops " ops per request."))

      (not (every? map? ops))
      (error 400 "Every op must be an object.")

      :else
      (let [resolved (resolve-ops {:db         db
                                   :token      token
                                   :now        (t/now)
                                   :malli-opts (if (var? malli-opts)
                                                 @malli-opts
                                                 malli-opts)}
                                  ops)
            valid?   (every? (comp empty? :errors) resolved)
            ops-json (vec (map-indexed (partial op->json (true? dry_run))
                                       resolved))]
        (cond
          (true? dry_run)
          (json 200 {"dry_run" true, "valid" valid?, "ops" ops-json})

          (not valid?)
          (json 422 {"committed" false, "valid" false, "ops" ops-json})

          :else
          (try
            (commit! ctx resolved)
            (json 200 {"committed" true, "valid" true, "ops" ops-json})
            (catch Exception e
              (cond
                (mutations/invalid-write? e)
                (error 422 "A write rule rejected the batch; nothing was written."
                       {"type"   (some-> (ex-data e) :entity-key name)
                        "errors" (coerce/->json-value (:errors (ex-data e)))})

                ;; The id was free when the batch was checked and taken by
                ;; commit time.
                (mutations/id-in-use? e)
                (error 409 "An id in the batch is already in use; nothing was written."
                       {"id" (some-> (ex-data e) :id str)})

                :else (throw e)))))))))

;; ---------------------------------------------------------------------------
;; Module
;; ---------------------------------------------------------------------------

(def module
  {:api-routes ["/api/v1" {:middleware [wrap-json-errors wrap-api-token]}
                ["/types" {:get types-handler}]
                ["/entities/:type" {:get list-handler}]
                ["/entities/:type/:id" {:get get-handler}]
                ["/tx" {:post tx-handler}]]})
