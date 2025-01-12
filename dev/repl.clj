(ns repl
  (:require [tech.jgood.gleanmo :as main]
            [tech.jgood.gleanmo.schema :as schema]
            [com.biffweb :as biff :refer [q]]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [xtdb.api :as xt]))

;; This function should only be used from the REPL. Regular application code
;; should receive the system map from the parent Biff component. For example,
;; the use-jetty component merges the system map into incoming Ring requests.
(defn get-context []
  (biff/assoc-db @main/system))

(defn add-fixtures []
  (biff/submit-tx (get-context)
                  (-> (io/resource "fixtures.edn")
                      slurp
                      edn/read-string)))

(comment
  ;; Call this function if you make a change to main/initial-system,
  ;; main/components, :tasks, :queues, or config.edn. If you update
  ;; secrets.env, you'll need to restart the app.
  (main/refresh)

  ;; Call this in dev if you'd like to add some seed data to your database. If
  ;; you edit the seed data (in resources/fixtures.edn), you can reset the
  ;; database by running `rm -r storage/xtdb` (DON'T run that in prod),
  ;; restarting your app, and calling add-fixtures again.
  (add-fixtures)

  ;; Query the database
  (let [{:keys [biff/db] :as ctx} (get-context)]
    (q db
       '{:find (pull user [*])
         :where [[user :user/email]]}))

  ;; Update an existing user's email address
  (let [{:keys [biff/db] :as ctx} (get-context)
        user-id (biff/lookup-id db :user/email "hello@example.com")]
    (biff/submit-tx ctx
                    [{:db/doc-type :user
                      :xt/id user-id
                      :db/op :update
                      :user/email "new.address@example.com"}]))

  (sort (keys (get-context)))

  ;; Change all :habit-log/habit-ids from vecs to sets
  (let [{:keys [biff/db] :as ctx} (get-context)
        habit-logs                (q db '{:find  (pull ?habit-log [*])
                                          :where [[?habit-log :habit-log/timestamp]]})]
    (->> habit-logs
         (mapv (fn [{ids :habit-log/habit-ids
                     :as habit-log}]
                 (merge habit-log
                        {:habit-log/habit-ids (set ids)
                         :db/doc-type         :habit-log
                         :db/op               :update})))
         (biff/submit-tx ctx)))

  ;; query habit-logs with habit names from refs in :habit-log/habit-ids
  (let [{:keys [biff/db] :as ctx} (get-context)
        user-id                   #uuid "1677c7f5-232d-47a5-9df7-244b040cdcb1"
        raw-results               (q db '{:find  [(pull ?habit-log [*]) ?habit-id ?habit-name]
                                          :where [[?habit-log :habit-log/timestamp]
                                                  [?habit-log :user/id user-id]
                                                  [?habit-log :habit-log/habit-ids ?habit-id]
                                                  [?habit :xt/id ?habit-id]
                                                  [?habit :habit/name ?habit-name]]
                                          :in    [user-id]} user-id)]
    (->> raw-results
         (group-by (fn [[habit-log _ _]] (:xt/id habit-log))) ; Group by habit-log id
         (map (fn [[log-id grouped-tuples]]
                (let [habit-log-map (first (first grouped-tuples))] ; Extract the habit-log map from the first tuple
                  (assoc habit-log-map :habit-log/habits
                         (map (fn [[_ ?habit-id ?habit-name]] ; Construct habit maps
                                {:habit/id   ?habit-id
                                 :habit/name ?habit-name})
                              grouped-tuples)))))
         (into [])))

;; get latest transaction time for an entity
  (let [{:keys [biff/db] :as ctx} (get-context)
        habit-id                   #uuid "7f41decc-8a3d-4062-9ea4-3c953d30c0f3"
        history                   (xt/entity-history db habit-id :desc)
        tx-time                   (-> history first :xtdb.api/tx-time)]
    tx-time)

  ;; get all habits that aren't deleted
  (let [{:keys [biff/db] :as ctx} (get-context)]
    (q db
       '{:find (pull habit [:habit/name])
         :where [[habit ::schema/type :habit]
                 (not [habit ::schema/deleted-at])]}))

  ;; set super user for email
  (let [{:keys [biff/db] :as ctx} (get-context)
        user-id (biff/lookup-id db :user/email
                                "justin@jgood.online")]
    user-id
    (biff/submit-tx ctx
                    [{:db/doc-type :user
                      :xt/id user-id
                      :db/op :update
                      :authz/super-user true}]))

  ;; Check the terminal for output.
  (biff/submit-job (get-context) :echo {:foo "bar"})
  (deref (biff/submit-job-for-result (get-context) :echo {:foo "bar"})))

(defn check-config []
  (let [prod-config (biff/use-aero-config {:biff.config/profile "prod"})
        dev-config  (biff/use-aero-config {:biff.config/profile "dev"})
        ;; Add keys for any other secrets you've added to resources/config.edn
        secret-keys [:biff.middleware/cookie-secret
                     :biff/jwt-secret
                     :mailersend/api-key
                     :recaptcha/secret-key
                     ; ...
                     ]
        get-secrets (fn [{:keys [biff/secret] :as config}]
                      (into {}
                            (map (fn [k]
                                   [k (secret k)]))
                            secret-keys))]
    {:prod-config prod-config
     :dev-config dev-config
     :prod-secrets (get-secrets prod-config)
     :dev-secrets (get-secrets dev-config)}))

(comment
  (check-config)
  ;;
  )

;; connect to production db from dev repl
;; realized this is uneccesary if I just used `clj -M:dev prod-repl`
;; update to the above, it actually times out on any evaluation ...
(comment
  ;; secrets are wrapped as functions so that they don't show up when serializing the maps
  (def prod-node (let [jdbc-url ((-> (check-config) :prod-config :biff.xtdb.jdbc/jdbcUrl))]
                   (biff/start-node {:topology  :jdbc
                                     :jdbc-spec {:jdbcUrl jdbc-url}
                                     :dir       "prod-storage/"})))

  (def email "example@example.com")

  ;; set a super user in production
  (let [ctx (-> @main/system
                (merge {:biff.xtdb/node prod-node})
                biff/assoc-db)
        prod-db (:biff/db ctx)
        user-id (biff/lookup-id prod-db :user/email email)]
    (biff/submit-tx ctx
                    [{:db/doc-type :user
                      :xt/id user-id
                      :db/op :update
                      :authz/super-user true}]))
  ;;
  )

(defn prod-node-start
  "Only call this once"
  []
  (let [jdbc-url ((-> (check-config) :prod-config :biff.xtdb.jdbc/jdbcUrl))]
    (biff/start-node {:topology  :jdbc
                      :jdbc-spec {:jdbcUrl jdbc-url}
                      :dir       "prod-storage/"})))

(defn get-prod-db-context [prod-node-ref]
  (-> @main/system
      (merge {:biff.xtdb/node prod-node-ref})
      biff/assoc-db))

;; migration to change name to label
(comment
  (def prod-node (prod-node-start))

  ;; habits
  (let [ctx     (get-prod-db-context prod-node)
        prod-db (:biff/db ctx)
        habits  (q prod-db '{:find  (pull ?habit [*])
                             :where [[?habit :tech.jgood.gleanmo.schema/type :habit]]})]
    (->> habits
         (mapv (fn [{n :habit/name :as habit}]
                 (merge habit
                        {:habit/label n
                         :db/doc-type :habit
                         :db/op       :update})))
         (biff/submit-tx ctx))
    )

  ;; locations
  (let [ctx       (get-prod-db-context prod-node)
        prod-db   (:biff/db ctx)
        locations (q prod-db '{:find  (pull ?location [*])
                               :where [[?location :tech.jgood.gleanmo.schema/type :location]]})]
    (->> locations
         (mapv (fn [{n :location/name :as location}]
                 (merge location
                        {:location/label n
                         :db/doc-type    :location
                         :db/op          :update})))
         (biff/submit-tx ctx))
    )
  ;; meditation-types
  (let [ctx       (get-prod-db-context prod-node)
        prod-db   (:biff/db ctx)
        meditation-types (q prod-db '{:find  (pull ?meditation-type [*])
                               :where [[?meditation-type :tech.jgood.gleanmo.schema/type :meditation-type]]})]
    (->> meditation-types
         (mapv (fn [{n :meditation-type/name :as meditation-type}]
                 (merge meditation-type
                        {:meditation-type/label n
                         :db/doc-type    :meditation-type
                         :db/op          :update})))
         (biff/submit-tx ctx))
    )

  ;; ical-urls (there are none in prod right now)

  ;;
  )

;; fixed an errant user creation colliding on email
(comment
  (def prod-node (prod-node-start))

  (let [ctx     (get-prod-db-context prod-node)
        prod-db (:biff/db ctx)]
    #_
    (biff/lookup prod-db :xt/id #uuid "955d1dac-f0fe-48d2-bccf-41a99beabdab")
    (biff/submit-tx ctx [{:xt/id       #uuid "955d1dac-f0fe-48d2-bccf-41a99beabdab"
                          :db/doc-type :user
                          :db/op       :update
                          :user/email  "notArealUser@example.com"}])
    )
  ;;
  )

;; migrate :meditation-log/position from :lying-down -> :lying
(comment
  (def prod-node (prod-node-start))

  (let [ctx     (get-prod-db-context prod-node)
        prod-db (:biff/db ctx)
        logs    (q prod-db '{:find  (pull ?l [*])
                             :where [[?l :tech.jgood.gleanmo.schema/type :meditation-log]
                                     [?l :meditation-log/position :lying-down]]})]
    (->> logs
         (mapv (fn [{id :xt/id}]
                 {:meditation-log/position :lying
                  :xt/id                   id
                  :db/doc-type             :meditation-log
                  :db/op                   :update}))
         (biff/submit-tx ctx)))

;;
  )
