(ns repl
  (:require
   [clj-uuid           :as uuid]
   [clojure.edn        :as edn]
   [clojure.java.io    :as io]
   [com.biffweb        :as    biff
    :refer [q]]
   [malli.core         :as m]
   [potpuri.core       :as pot]
   [tech.jgood.gleanmo :as main]
   [tech.jgood.gleanmo.schema.bm-schema :as bs]
   [tech.jgood.gleanmo.schema.meta :as sm]
   [tick.core          :as t]
   [xtdb.api           :as xt]))

;; This function should only be used from the REPL. Regular application code
;; should receive the system map from the parent Biff component. For example,
;; the use-jetty component merges the system map into incoming Ring requests.
(defn get-context
  []
  (biff/assoc-db @main/system))

(defn add-fixtures
  []
  (try
    (let [;; Define a custom reader for #time/instant
          readers      {'time/instant (fn [inst-str]
                                        (if (string? inst-str)
                                          (t/instant (java.time.Instant/parse
                                                      inst-str))
                                          inst-str))
                        ;; Add other readers if needed
                        }

          ;; Read the fixtures file
          fixtures-str (slurp (io/resource "fixtures.edn"))
          _ (println "Loaded fixtures file, size:" (count fixtures-str) "chars")

          ;; Parse the EDN with the custom readers
          fixtures     (edn/read-string {:readers readers} fixtures-str)
          _ (println "Parsed EDN, found" (count fixtures) "entities")]

      ;; Submit the transaction
      (println "Submitting transaction...")
      (biff/submit-tx (get-context) fixtures))
    (catch Exception e
      (println "Error in add-fixtures:" (.getMessage e))
      (println "Cause:"
               (if-let [cause (.getCause e)]
                 (.getMessage cause)
                 "None"))
      (.printStackTrace e))))

(comment
  ;; Call this function if you make a change to main/initial-system,
  ;; main/components, :tasks, :queues, or config.edn. If you update
  ;; secrets.env, you'll need to restart the app.
  (main/refresh)

  ;; Call this in dev if you'd like to add some seed data to your database.
  ;; If you edit the seed data (in resources/fixtures.edn), you can reset
  ;; the database by running `rm -r storage/xtdb` (DON'T run that in prod),
  ;; restarting your app, and calling add-fixtures again.
  (add-fixtures)

  ;; Query the database
  (let [{:keys [biff/db], :as ctx} (get-context)]
    (q db
       '{:find  (pull user [*]),
         :where [[user :user/email]]}))

  ;; Update an existing user's email address
  (let [{:keys [biff/db], :as ctx} (get-context)
        user-id (biff/lookup-id db :user/email "hello@example.com")]
    (biff/submit-tx ctx
                    [{:db/doc-type :user,
                      :xt/id       user-id,
                      :db/op       :update,
                      :user/email  "new.address@example.com"}]))

  ;; get latest transaction time for an entity
  (let [{:keys [biff/db], :as ctx} (get-context)
        habit-id #uuid "e6457eda-f975-4a54-b6fa-fa31f6736690"
        history  (xt/entity-history db habit-id :desc)
        tx-time  (-> history
                     first
                     :xtdb.api/tx-time)]
    tx-time)

  ;; get all habits that aren't deleted
  (let [{:keys [biff/db], :as ctx} (get-context)]
    (q db
       '{:find  (pull habit [:habit/name]),
         :where [[habit ::sm/type :habit]
                 (not [habit ::sm/deleted-at])]}))

  ;; set super user for email
  (let [{:keys [biff/db], :as ctx} (get-context)
        user-id (biff/lookup-id db :user/email "justin@jgood.online")]
    (biff/submit-tx ctx
                    [{:db/doc-type :user,
                      :xt/id       user-id,
                      :db/op       :update,
                      :authz/super-user true}]))

  ;; Check the terminal for output.
  (biff/submit-job (get-context) :echo {:foo "bar"})
  (deref (biff/submit-job-for-result (get-context) :echo {:foo "bar"})))

(defn check-config
  []
  (let [prod-config (biff/use-aero-config {:biff.config/profile "prod"})
        dev-config  (biff/use-aero-config {:biff.config/profile "dev"})
        ;; Add keys for any other secrets you've added to
        ;; resources/config.edn
        secret-keys [:biff.middleware/cookie-secret
                     :biff/jwt-secret
                     :mailersend/api-key
                     :recaptcha/secret-key
                     ; ...
                     ]
        get-secrets (fn [{:keys [biff/secret], :as config}]
                      (into {}
                            (map (fn [k]
                                   [k (secret k)]))
                            secret-keys))]
    {:prod-config  prod-config,
     :dev-config   dev-config,
     :prod-secrets (get-secrets prod-config),
     :dev-secrets  (get-secrets dev-config)}))

(comment
  (check-config)
  ;;
  )

(defn prod-node-start
  "Only call this once"
  []
  (let [jdbc-url ((-> (check-config)
                      :prod-config
                      :biff.xtdb.jdbc/jdbcUrl))]
    (biff/start-node {:topology  :jdbc,
                      :jdbc-spec {:jdbcUrl jdbc-url},
                      :dir       "prod-storage/"})))

(defn get-prod-db-context
  [prod-node-ref]
  (-> @main/system
      (merge {:biff.xtdb/node prod-node-ref})
      biff/assoc-db))

;; migration to add new schema meta keys and change :meditation-type ->
;; :meditation
(comment
  (def prod-node (prod-node-start))

  (let [{:keys [biff/db], :as ctx}
        (get-prod-db-context prod-node)
        #_(get-context)

        entities
        (q db
           '{:find  (pull e [*]),
             :where [[e :tech.jgood.gleanmo.schema/type]
                     (not [e ::sm/type])]})]
    (count entities)
    #_(->> entities
           (remove (fn [e] (nil? (:xt/id e))))
           #_(take 500)
           (mapv
            (fn [{id     :xt/id,
                  ca     :tech.jgood.gleanmo.schema/created-at,
                  da     :tech.jgood.gleanmo.schema/deleted-at,
                  t      :tech.jgood.gleanmo.schema/type,
                  ml     :meditation-type/label,
                  ml-alt :meditation-type/name,
                  mn     :meditation-type/notes,
                  hl     :habit/name,
                  hll    :habit-log/name,
                  ll     :location/name,
                  ja     :user/joined-at}]
              (let [t (if (= t :meditation-type)
                        :meditation
                        t)]
                (-> {:xt/id          id,
                     ::sm/created-at (or ca ja),
                     ::sm/type       t,
                     :db/doc-type    t,
                     :db/op          :update}
                    (pot/assoc-if :location/label ll)
                    (pot/assoc-if :habit/label hl)
                    (pot/assoc-if :habit-log/label hll)
                    (pot/assoc-if :meditation/notes mn)
                    (pot/assoc-if :meditation/label (or ml ml-alt))
                    (pot/assoc-if ::sm/deleted-at da)))))
           (biff/submit-tx ctx)))

  ;; Forgot meditation/name
  (let [{:keys [biff/db], :as ctx}
        #_(get-prod-db-context prod-node)
        (get-context)

        entities
        (q db
           '{:find  (pull e [*]),
             :where [[e ::sm/type :meditation]]})]
    (->> entities
         (remove (fn [e] (nil? (:xt/id e))))
         (mapv (fn [{id :xt/id,
                     ml :meditation/label}]
                 (-> {:xt/id           id,
                      :meditation/name ml,
                      :db/doc-type     :meditation,
                      :db/op           :update})))
         (biff/submit-tx ctx)))
  ;;
  )

;; crud single relation query
(comment
  (let [ctx (get-context)]
    (q (:biff/db ctx)
       '{:find  [?id ?label],
         :where [[?e ::sm/type :location]
                 [?e :xt/id ?id]
                 [?e :location/label ?label]]}))

  (let [ctx       (get-context)
        label-key :location/label]

    (q (:biff/db ctx)
       `{:find  [~'?id ~'?label],
         :where [[~'?e ::sm/type ~'related-entity]
                 [~'?e :xt/id ~'?id]
                 [~'?e ~label-key ~'?label]],
         :in    [[related-entity]]}
       [:location]))

  (let [a 1]
    `[a 'a ~a ~'a])

  (let [a "1"]
    '[a 'a ~a ~'a])

  (let [ctx       (get-context)
        label-key :location/label]
    (q (:biff/db ctx)
       {:find  ['?id '?label],
        :where [['?e ::sm/type 'related-entity]
                ['?e :xt/id '?id]
                ['?e label-key '?label]],
        :in    [['related-entity]]}
       [:location]))

  (let [ctx       (get-context)
        label-key :location/label
        related-entity :location]
    (q (:biff/db ctx)
       {:find  ['?id '?label],
        :where [['?e ::sm/type related-entity]
                ['?e :xt/id '?id]
                ['?e label-key '?label]]}))
  ;;
  )

(defn get-keys-of-airtable-file
  [file-path]
  (with-open [rdr (io/reader file-path)]
    (->> (line-seq rdr)
         (mapcat (fn [line]
                   (when (seq (.trim line))
                     (keys (get (edn/read-string line) "fields")))))
         (into #{}))))

(comment
  (get-keys-of-airtable-file
   "airtable_data/bm_log_2025_08_12_10_07_39_065151.edn")
  ; => #{"blood"
  ;   "anxiety" "pain" "timestamp" "urgency" "straining" "mucus" "notes"
  ;   "size" "bristol" "color"
  ;   "odor"}

  ;;
  )

(defn parse-bristol
  [bristol-str]
  (if bristol-str
    (cond
      (re-find #"\(1\)|hard.*clumps?" bristol-str) :b1-hard-clumps
      (re-find #"\(2\)|lumpy" bristol-str)         :b2-lumpy-log
      (re-find #"\(3\)|crack" bristol-str)         :b3-cracked-log
      (re-find #"\(4\)|smooth" bristol-str)        :b4-smooth-log
      (re-find #"\(5\)|soft.*blob" bristol-str)    :b5-soft-blobs
      (re-find #"\(6\)|mushy|ragged" bristol-str)  :b6-mushy-ragged
      (re-find #"\(7\)|liquid|watery" bristol-str) :b7-liquid
      :else                                        :n-a)
    :n-a))

(defn parse-enum
  [value mapping]
  (if value
    (get mapping (.toLowerCase value) :n-a)
    :n-a))

;; Namespace UUID for bm-log deterministic UUID generation
(def bm-log-namespace-uuid #uuid "c8d3f4a1-9b2e-4f8a-b6d1-e3f5a7c9d2b4")

(defn generate-deterministic-uuid
  [seed]
  (uuid/v5 bm-log-namespace-uuid seed))

(defn airtable->bm-log
  [airtable-record user-id]
  (let [fields               (get airtable-record "fields")
        id                   (get airtable-record "id")
        created-time         (get airtable-record "createdTime")

        blood-mapping        {"none"    :none,
                              "trace"   :trace,
                              "visible" :visible,
                              "lots"    :lots}

        urgency-mapping      {"none"     :none,
                              "mild"     :mild,
                              "moderate" :moderate,
                              "severe"   :severe}

        anxiety-mapping      {"none"     :none,
                              "mild"     :mild,
                              "moderate" :moderate,
                              "severe"   :severe}

        color-mapping        {"brown"  :brown,
                              "yellow" :yellow,
                              "green"  :green,
                              "black"  :black,
                              "red"    :red,
                              "grey"   :grey}

        odor-mapping         {"normal"   :normal,
                              "foul"     :foul,
                              "metallic" :metallic,
                              "sweet"    :sweet,
                              "sour"     :sour}

        size-mapping         {"small"  :small,
                              "medium" :medium,
                              "meduim" :medium, ; typo in data
                              "large"  :large}

        pace-mapping         {"quick"   :quick,
                              "typical" :typical,
                              "long"    :long}

        completeness-mapping {"complete"   :complete,
                              "incomplete" :incomplete,
                              "unsure"     :unsure}

        ease-mapping         {"easy"           :easy,
                              "normal"         :normal,
                              "difficult"      :difficult,
                              "very-difficult" :very-difficult}]

    (into
     {}
     (remove (fn [[k v]] (nil? v))
             {:xt/id                  (generate-deterministic-uuid id),
              ::sm/type               :bm-log,
              ::sm/created-at         (t/instant created-time),
              :user/id                user-id,
              :bm-log/timestamp       (t/instant (get fields "timestamp")),
              :bm-log/bristol         (parse-bristol (get fields "bristol")),
              :bm-log/pace            (parse-enum (get fields "pace") pace-mapping),
              :bm-log/color           (parse-enum (get fields "color")
                                                  color-mapping),
              :bm-log/blood           (parse-enum (get fields "blood")
                                                  blood-mapping),
              :bm-log/mucus           (if-let [mucus (get fields "mucus")]
                                        (boolean mucus)
                                        :n-a),
              :bm-log/urgency         (parse-enum (get fields "urgency")
                                                  urgency-mapping),
              :bm-log/incontinence    :n-a, ; not in airtable data
              :bm-log/straining       (if-let [straining (get fields "straining")]
                                        (boolean straining)
                                        :n-a),
              :bm-log/odor            (parse-enum (get fields "odor") odor-mapping),
              :bm-log/size            (parse-enum (get fields "size") size-mapping),
              :bm-log/notes           (get fields "notes"),
              :bm-log/anxiety         (parse-enum (get fields "anxiety")
                                                  anxiety-mapping),
              :bm-log/feeling-of-completeness
              (parse-enum (get fields "feeling-of-completeness")
                          completeness-mapping),
              :bm-log/ease-of-passage (parse-enum (get fields "ease-of-passage")
                                                  ease-mapping),
              :bm-log/airtable-id     id,
              :bm-log/airtable-created-time (t/instant created-time)}))))

(defn convert-airtable-bm-logs
  [file-path user-id]
  (with-open [rdr (io/reader file-path)]
    (->> (line-seq rdr)
         (map (fn [line]
                (when (seq (.trim line))
                  (airtable->bm-log (edn/read-string line) user-id))))
         (remove nil?)
         vec)))

(defn validate-bm-logs
  "Validates a collection of bm-logs against the malli schema.
   Returns a map with :failed, :passed, and :total counts."
  [bm-logs]
  (let [registry     (:registry main/malli-opts)
        validator    (m/validator bs/bm-log {:registry registry})
        results      (map (fn [bm-log]
                            {:bm-log bm-log,
                             :valid? (validator bm-log)})
                          bm-logs)
        failed       (filter #(not (:valid? %)) results)
        passed-count (count (filter :valid? results))
        total-count  (count results)]
    {:failed (map :bm-log failed),
     :passed passed-count,
     :total  total-count}))

(comment
  (->>
   (convert-airtable-bm-logs
    "airtable_data/bm_log_2025_08_12_10_07_39_065151.edn"
    "my-user-id")
   count)

  ;; Validate converted bm-logs
  (->>
   (convert-airtable-bm-logs
    "airtable_data/bm_log_2025_08_12_10_07_39_065151.edn"
    #uuid "344f6b1e-a1a9-487a-9d5b-b17c7b1f3973")
   validate-bm-logs)
  ;;
  )

(defn write-bm-logs-to-db
  [{:keys [biff/db], :as ctx} email]
  (let [{user-uuid :xt/id}
        (first (q db
                  '{:find  (pull ?e [*]),
                    :where [[?e :user/email email]],
                    :in    [email]}
                  email))
        bm-logs      (convert-airtable-bm-logs
                      "airtable_data/bm_log_2025_08_12_10_07_39_065151.edn"
                      user-uuid)
        transactions (->> bm-logs
                          (map (fn [bm-log]
                                 (merge bm-log
                                        {:db/doc-type :bm-log,
                                         :db/op       :create,
                                         :user/id     user-uuid}))))]

    (biff/submit-tx ctx (rest transactions))))

(comment
  (def prod-node (prod-node-start))

  (let [ctx (get-prod-db-context prod-node)]
    (write-bm-logs-to-db ctx "<redacted-email>")
    #_(count (q (:biff/db ctx)
       '{:find  (pull ?e [*]),
         :where [[?e ::sm/type :bm-log]]}
       )))

;;
  )
