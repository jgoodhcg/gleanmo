(ns repl
  (:require [tech.jgood.gleanmo :as main]
            [tech.jgood.gleanmo.schema.meta :as sm]
            [com.biffweb :as biff :refer [q]]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [xtdb.api :as xt]
            [potpuri.core :as pot]))

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
       '{:find  (pull user [*])
         :where [[user :user/email]]}))

  ;; Update an existing user's email address
  (let [{:keys [biff/db] :as ctx} (get-context)
        user-id                   (biff/lookup-id db :user/email "hello@example.com")]
    (biff/submit-tx ctx
                    [{:db/doc-type :user
                      :xt/id       user-id
                      :db/op       :update
                      :user/email  "new.address@example.com"}]))

  ;; get latest transaction time for an entity
  (let [{:keys [biff/db] :as ctx} (get-context)
        habit-id                  #uuid "e6457eda-f975-4a54-b6fa-fa31f6736690"
        history                   (xt/entity-history db habit-id :desc)
        tx-time                   (-> history first :xtdb.api/tx-time)]
    tx-time)

  ;; get all habits that aren't deleted
  (let [{:keys [biff/db] :as ctx} (get-context)]
    (q db
       '{:find  (pull habit [:habit/name])
         :where [[habit ::sm/type :habit]
                 (not [habit ::sm/deleted-at])]}))

  ;; set super user for email
  (let [{:keys [biff/db] :as ctx} (get-context)
        user-id                   (biff/lookup-id db :user/email "justin@jgood.online")]
    (biff/submit-tx ctx
                    [{:db/doc-type      :user
                      :xt/id            user-id
                      :db/op            :update
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

;; migration to add new schema meta keys and change :meditation-type -> :meditation
(comment
  (def prod-node (prod-node-start))

  (let [{:keys [biff/db] :as ctx}
        (get-prod-db-context prod-node)
        #_ (get-context)

        entities
        (q db
           '{:find (pull e [*])
              :where [[e :tech.jgood.gleanmo.schema/type]
                      (not [e ::sm/type])]})]
    (count entities)
    #_(->> entities
           (remove (fn [e] (nil? (:xt/id e))))
           #_(take 500)
           (mapv (fn [{id :xt/id
                        ca :tech.jgood.gleanmo.schema/created-at
                        da :tech.jgood.gleanmo.schema/deleted-at
                        t :tech.jgood.gleanmo.schema/type
                        ml :meditation-type/label
                        ml-alt :meditation-type/name
                        mn :meditation-type/notes
                        hl :habit/name
                        hll :habit-log/name
                        ll :location/name
                        ja :user/joined-at}]
                    (let [t (if (= t :meditation-type)
                              :meditation
                             t)]
                      (-> {:xt/id id
                           ::sm/created-at (or ca ja)
                           ::sm/type t
                           :db/doc-type t
                           :db/op :update}
                         (pot/assoc-if :location/label ll)
                         (pot/assoc-if :habit/label hl)
                         (pot/assoc-if :habit-log/label hll)
                         (pot/assoc-if :meditation/notes mn)
                         (pot/assoc-if :meditation/label (or ml ml-alt))
                         (pot/assoc-if ::sm/deleted-at da)))))
           (biff/submit-tx ctx)))

  ;; Forgot meditation/name
  (let [{:keys [biff/db] :as ctx}
        #_
        (get-prod-db-context prod-node)
        (get-context)

        entities
        (q db
           '{:find  (pull e [*])
             :where [[e ::sm/type :meditation]]})]
    (->> entities
         (remove (fn [e] (nil? (:xt/id e))))
         (mapv (fn [{id :xt/id
                    ml :meditation/label}]
                 (-> {:xt/id           id
                      :meditation/name ml
                      :db/doc-type     :meditation
                      :db/op           :update}
                     )))
         (biff/submit-tx ctx)))
;;
  )
