(ns tech.jgood.gleanmo.repl
  (:require [tech.jgood.gleanmo :as main]
            [com.biffweb :as biff :refer [q]]
            [clojure.edn :as edn]
            [clojure.java.io :as io]))

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
        history                   (xt/entity-history db habit-id)
        ]
    history)

  ;; Check the terminal for output.
  (biff/submit-job (get-context) :echo {:foo "bar"})
  (deref (biff/submit-job-for-result (get-context) :echo {:foo "bar"})))
