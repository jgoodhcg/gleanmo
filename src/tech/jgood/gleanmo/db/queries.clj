(ns tech.jgood.gleanmo.db.queries
  (:require
   [com.biffweb :as    biff
    :refer [q]]
   [tech.jgood.gleanmo.schema :as schema-registry]
   [tech.jgood.gleanmo.schema.utils :as schema-utils]
   [tech.jgood.gleanmo.schema.meta :as sm]
   [tick.core :as t]
   [xtdb.api :as xt]
   [taoensso.tufte :refer [defnp defnp-]]))

(defnp get-entity-by-id
  "Get a single entity by ID.
   Returns the first result or nil if not found."
  [db entity-id]
  (let [result (q db
                  {:find  '(pull ?e [*]),
                   :where [['?e :xt/id entity-id]],
                   :in    '[entity-id]}
                  entity-id)]
    (when (seq result)
      (-> result
          first))))

(defn- ->instant
  "Coerce a variety of date/time values into java.time.Instant, if possible."
  [v]
  (cond
    (nil? v) nil
    (instance? java.time.Instant v) v
    (instance? java.time.LocalDateTime v) (.toInstant ^java.time.LocalDateTime v java.time.ZoneOffset/UTC)
    (instance? java.time.LocalDate v) (.toInstant (.atStartOfDay ^java.time.LocalDate v java.time.ZoneOffset/UTC))
    :else (try
            (t/instant v)
            (catch Exception _
              nil))))

(defn get-user-settings
  "Get all user settings in a single query. Returns a map with email and boolean settings."
  [db user-id]
  (when user-id
    (if-let [user (get-entity-by-id db user-id)]
      (let [show-bm-logs (cond
                           (contains? user :user/show-bm-logs)
                           (boolean (:user/show-bm-logs user))
                           (contains? user :user/hide-bm-logs)
                           (not (boolean (:user/hide-bm-logs user)))
                           :else
                           true)]
        {:email          (:user/email user),
         :show-sensitive (boolean (:user/show-sensitive user)),
         :show-archived  (boolean (:user/show-archived user)),
         :show-bm-logs   show-bm-logs})
      {:email          nil,
       :show-sensitive false,
       :show-archived  false,
       :show-bm-logs   true})))

(defnp get-entity-for-user
  "Get a single entity by ID that belongs to a specific user.
   Returns the first result or nil if not found."
  [db entity-id user-id entity-type]
  (let [result (q db
                  {:find  '(pull ?e [*]),
                   :where '[[?e :xt/id id]
                            [?e :user/id user-id]
                            [?e ::sm/type entity-type]],
                   :in    '[id user-id entity-type]}
                  entity-id
                  user-id
                  entity-type)]
    (when (seq result)
      (-> result
          first))))

(defn- collect-related-ids
  "Collect all related entity IDs from a batch of entities based on relationship fields.
   Returns a set of all related IDs.

   This enables batch-fetching related entities in a single query, reducing
   network round-trips from O(N × M × K) to O(1) where N=entities, M=relationship
   fields, K=related IDs per field. Data processing remains O(n) but network
   latency dominates in practice."
  [entities relationship-fields]
  (into #{}
        (for [entity entities
              {:keys [field-key input-type]} relationship-fields
              :let [ids (if (= input-type :many-relationship)
                          (get entity field-key)
                          (when-let [id (get entity field-key)] #{id}))]
              id ids
              :when id]
          id)))

(defnp- batch-fetch-entities
  "Fetch multiple entities by ID in a single query.
   Returns a map of entity-id -> entity for O(1) lookup."
  [db ids]
  (when (seq ids)
    (let [id-vec  (vec ids)
          results (q db
                     {:find  '[(pull ?e [*])]
                      :where '[[?e :xt/id ?id]]
                      :in    '[[?id ...]]}
                     id-vec)]
      (into {} (map (fn [[entity]] [(:xt/id entity) entity]) results)))))

(defn- should-remove-related-entity
  "Check if an entity has related entities that should be removed based on sensitivity or archive settings.
   Uses a pre-fetched lookup map instead of individual queries.
   Returns true if any related entity matches removal criteria."
  [entity relationship-fields related-lookup remove-sensitive remove-archived]
  (boolean
   (some
    (fn [{:keys [field-key input-type related-entity-str]}]
      (let [rel-sensitive-key (keyword related-entity-str "sensitive")
            rel-archived-key  (keyword related-entity-str "archived")
            related-ids       (if (= input-type :many-relationship)
                                (get entity field-key)
                                (when-let [id (get entity field-key)] #{id}))]
        (when (seq related-ids)
          ;; Look up related entities from pre-fetched map
          (let [related-entities (keep #(get related-lookup %) related-ids)]
            ;; Check if any related entity matches our removal criteria
            (some (fn [rel-entity]
                    (or (and remove-sensitive (get rel-entity rel-sensitive-key))
                        (and remove-archived (get rel-entity rel-archived-key))))
                  related-entities)))))
    relationship-fields)))

(def ^:private default-order-direction :desc)

(defn- build-entity-query
  [_user-id entity-type order-key order-direction limit offset]
  (let [order-direction (or order-direction default-order-direction)
        order-var       '?order-value
        base-where      ['[?e :user/id user-id]
                         ['?e ::sm/type entity-type]
                         '(not [?e ::sm/deleted-at])]
        where-clauses   (if order-key
                          (conj base-where (vec ['?e order-key order-var]))
                          base-where)
        find-elements   (if order-key
                          '[(pull ?e [*]) ?order-value]
                          '[(pull ?e [*])])]
    (cond-> {:find  find-elements
             :where where-clauses
             :in    ['user-id]}
      order-key (assoc :order-by [[order-var order-direction]])
      limit     (assoc :limit limit)
      offset    (assoc :offset offset))))

(defnp all-entities-for-user
  "Get all entities of a specific type that belong to a user.
   Optionally includes or removes entities based on sensitivity, archive status, and related entities."
  [db user-id entity-type &
   {:keys [filter-sensitive filter-archived filter-references
           relationship-fields limit offset order-key order-direction]}]
  (let [entity-type-str (name entity-type)
        sensitive-key   (keyword entity-type-str "sensitive")
        archived-key    (keyword entity-type-str "archived")
        time-stamp-key  (keyword entity-type-str "timestamp")
        filter-entities (fn [entities]
                          (let [basic-filtered (cond->> entities
                                                 (not filter-sensitive) (remove #(get % sensitive-key))
                                                 (not filter-archived)  (remove #(get % archived-key)))]
                            (if (and filter-references relationship-fields)
                              (let [remove-sensitive (not filter-sensitive)
                                    remove-archived  (not filter-archived)
                                    ;; Batch-fetch all related entities in one query
                                    all-related-ids  (collect-related-ids basic-filtered relationship-fields)
                                    related-lookup   (batch-fetch-entities db all-related-ids)]
                                (remove #(should-remove-related-entity
                                          %
                                          relationship-fields
                                          related-lookup
                                          remove-sensitive
                                          remove-archived)
                                        basic-filtered))
                              basic-filtered)))
        target-count    (when limit (+ (or offset 0) limit))
        batch-size      (let [candidate (max 32 (or limit 0))]
                          (if (pos? candidate) candidate 32))
        filtered-results (loop [acc        []
                                raw-offset 0]
                           (let [query-map   (build-entity-query
                                              user-id
                                              entity-type
                                              order-key
                                              order-direction
                                              batch-size
                                              raw-offset)
                                 raw-results (q db query-map user-id)
                                 fetched     (count raw-results)
                                 entities    (map first raw-results)
                                 filtered    (filter-entities entities)
                                 new-acc     (into acc filtered)
                                 enough?     (and target-count
                                                  (>= (count new-acc) target-count))
                                 exhausted?  (< fetched batch-size)]
                             (if (or exhausted? enough?)
                               new-acc
                               (recur new-acc (+ raw-offset fetched)))))
        ordered-results (cond->> filtered-results
                          (nil? order-key)
                          (sort-by #(or (time-stamp-key %) (::sm/created-at %)))
                          (nil? order-key)
                          reverse)
        offset'         (max 0 (or offset 0))
        after-offset    (if (zero? offset')
                          ordered-results
                          (drop offset' ordered-results))]

    (cond->> after-offset
      limit (take limit))))

(defn tasks-for-user
  "Get all tasks for a user, respecting the user's sensitive setting."
  [db user-id]
  (let [{:keys [show-sensitive show-archived]} (get-user-settings db user-id)]
    (all-entities-for-user
     db
     user-id
     :task
     :filter-sensitive show-sensitive
     :filter-archived  show-archived)))

(defn tasks-by-state
  "Get tasks for a user in a specific state, respecting sensitive settings."
  [db user-id state]
  (->> (tasks-for-user db user-id)
       (filter #(= (:task/state %) state))))

(defn count-tasks-by-state
  "Count tasks in a specific state for a user, respecting sensitive settings."
  [db user-id state]
  (count (tasks-by-state db user-id state)))

(defn projects-for-user
  "Get all projects for a user, respecting the user's sensitive setting."
  [db user-id]
  (let [{:keys [show-sensitive show-archived]} (get-user-settings db user-id)]
    (->> (all-entities-for-user
          db
          user-id
          :project
          :filter-sensitive show-sensitive
          :filter-archived  show-archived)
         (sort-by :project/label))))

(defnp all-for-user-query
  "Get all entities for a user with include/exclude options from user settings.
   This function is a higher-level wrapper around all-entities-for-user that handles
   user settings for including sensitive entities, archived entities, and related entity filtering."
  [{:keys [entity-type-str schema filter-references limit offset order-key order-direction]}
   {:keys [biff/db session]}]
  (let [user-id             (:uid session)
        ;; Get user's preferences from settings, with secure defaults
        {:keys [show-sensitive show-archived]} (get-user-settings db user-id)
        sensitive           show-sensitive
        archived            show-archived
        entity-type         (keyword entity-type-str)

        ;; Get relationship fields from schema, removing system fields
        relationship-fields (when (and schema filter-references)
                              (schema-utils/extract-relationship-fields
                               schema
                               :remove-system-fields
                               true))]

    ;; Use the core function
    (all-entities-for-user
     db
     user-id
     entity-type
     :filter-sensitive    sensitive
     :filter-archived     archived
     :filter-references   filter-references
     :relationship-fields relationship-fields
     :limit               limit
     :offset              offset
     :order-key           order-key
     :order-direction     order-direction)))

(defnp dashboard-recent-entities
  "Fetch a bounded set of recent entities per type for the dashboard, respecting user settings and related-entity filters."
  [db user-id {:keys [entity-types per-type-limit order-keys], :or {per-type-limit 20}}]
  (let [{:keys [show-sensitive show-archived]} (get-user-settings db user-id)]
    (mapcat
     (fn [entity-str]
       (let [entity-kw    (keyword entity-str)
             order-key    (get order-keys entity-str ::sm/created-at)
             entity-schema (get schema-registry/schema entity-kw)
             rel-fields    (schema-utils/extract-relationship-fields
                            entity-schema
                            :remove-system-fields true)]
         (all-entities-for-user
          db
          user-id
          entity-kw
          :filter-sensitive    show-sensitive
          :filter-archived     show-archived
          :filter-references   true
          :relationship-fields rel-fields
          :limit               per-type-limit
          :order-key           order-key
          :order-direction     :desc)))
     entity-types)))

(defnp dashboard-upcoming-events
  "Fetch upcoming calendar events with visibility filtering and a small oversample to survive filtering."
  [db user-id {:keys [limit], :or {limit 5}}]
  (let [{:keys [show-sensitive show-archived]} (get-user-settings db user-id)
        entity-kw     :calendar-event
        entity-schema (get schema-registry/schema entity-kw)
        rel-fields    (schema-utils/extract-relationship-fields
                       entity-schema
                       :remove-system-fields true)
        batch-limit   (max 40 (* 4 limit))
        now           (t/now)
        raw-events    (all-entities-for-user
                       db
                       user-id
                       entity-kw
                       :filter-sensitive    show-sensitive
                       :filter-archived     show-archived
                       :filter-references   true
                       :relationship-fields rel-fields
                       :limit               batch-limit
                       :order-key           :calendar-event/beginning
                       :order-direction     :desc)]
    (->> raw-events
         (keep (fn [e]
                 (when-let [inst (->instant (or (:calendar-event/beginning e)
                                                (::sm/created-at e)))]
                   (when (t/> inst now)
                     (assoc e ::order inst)))))
         (sort-by ::order)
         (take limit)
         (map #(dissoc % ::order)))))

(defn get-user-authz
  "Get user authorization info (super-user status) for a user ID."
  [db user-id]
  (when user-id
    (when-let [user (get-entity-by-id db user-id)]
      {:super-user (boolean (:authz/super-user user))})))

#_{:clj-kondo/ignore [:shadowed-var]}
(defnp db-viz-query
  "Execute a database visualization query with type and filter parameters."
  [db query type filter-email]
  (q db query [type filter-email]))

(defn get-last-tx-time
  [{:keys [biff/db xt/id]}]
  (let [history (xt/entity-history db id :desc)]
    (-> history
        first
        :xtdb.api/tx-time)))

(defnp get-events-for-user-year
  "Get all events for a user within a specific year, using user's timezone.
   Note: Performs date-range filtering in application code to avoid complex
   Datalog predicates on Instants."
  [db user-id year user-timezone]
  (let [zone       (java.time.ZoneId/of (or user-timezone "UTC"))
        year-start (-> (t/date (str year "-01-01"))
                       (t/at (t/time "00:00"))
                       (t/in zone)
                       (t/instant))
        year-end   (-> (t/date (str year "-12-31"))
                       (t/at (t/time "23:59:59"))
                       (t/in zone)
                       (t/instant))
        results    (q db
                      {:find  '(pull ?e [*]),
                       :where '[[?e :xt/id ?id]
                                [?e ::sm/type :calendar-event]
                                [?e :user/id ?user-id]
                                (not [?e ::sm/deleted-at])],
                       :in    '[?user-id]}
                      user-id)]

    (->> results
         (filter (fn [e]
                   (let [dt (:calendar-event/beginning e)]
                     (if (some? dt)
                       ;; If event has a dtstart, filter by year
                       (and (t/>= dt year-start)
                            (t/<= dt year-end))
                       ;; If no dtstart, include it (events without dates)
                       true)))))))
