(ns airtable.activity
  {:deprecated "Migration completed. Use `clj -M:dev migrate-airtable` CLI task for new migrations."}
  "DEPRECATED/LEGACY: Habits (activity) migration from Airtable - COMPLETED.

   This migration has been completed and this code is preserved for reference.
   New migrations use the CLI task: clj -M:dev migrate-airtable

   Workflow:
   1. clj -M:dev download-airtable -k $API_KEY -b BASE_ID -n table-name
   2. Use REPL functions in repl.airtable.<entity> to transform and write"
  (:require
   [clj-uuid :as uuid]
   [tech.jgood.gleanmo.schema.meta :as sm]
   [clojure.edn :as edn]
   [potpuri.core :as pot]
   [clojure.string :as str]
   [clojure.java.io :as io]
   [tick.core :as t]
   [clojure.pprint :refer [pprint]]
   [repl :refer [get-context prod-node-start get-prod-db-context]]
   [com.biffweb :as biff :refer [q]]))

(def activity-file-name
  "/Users/justingood/projects/gleanmo/airtable_data/activity_2024_07_04_15_27_20_679013.edn")
(def log-file-name
  "/Users/justingood/projects/gleanmo/airtable_data/activity_log_2024_07_04_15_27_37_542631.edn")

;; NOTE Do not change these!
(def ns-uuid-activity
  #uuid "d542f8dc-96d1-495d-afa6-738750c08510")
(def ns-uuid-activity-log
  #uuid "f4ab292b-5e61-4c71-9a3a-bc75da0525e3")

(def email
  "justin@jgood.online")
(def tz-str
  "America/Detroit") ;; use (t/zone) to check if it's a real timezone string identifier

(defn port-habits [{:keys [biff/db] :as ctx}]
  (let [user-id                   (biff/lookup-id db :user/email email)]
    (with-open [rdr (io/reader activity-file-name)]
      (doseq [line (->> rdr line-seq)]
        (let [edn-data   (edn/read-string line)
              habit-name (-> edn-data (get "fields") (get "Name"))
              at-logs    (-> edn-data (get "fields") (get "activity-log"))
              now        (t/now)]
          (when (and (not (str/blank? habit-name))
                     (seq at-logs))
            (let [created-at (-> edn-data (get "createdTime") (t/instant))
                  at-id      (-> edn-data (get "id"))
                  xt-id      (uuid/v5 ns-uuid-activity at-id)
                  sensitive  (-> edn-data (get "fields") (get "sensitive"))
                  notes      (-> edn-data (get "fields") (get "notes"))
                  habit      (cond-> {:xt/id              xt-id
                                      :db/doc-type        :habit
                                      ::sm/type       :habit
                                      ::sm/created-at created-at
                                      :user/id            user-id
                                      :habit/name         habit-name
                                      :airtable/id        at-id
                                      :airtable/ported-at now}
                               (not (str/blank? notes)) (assoc :habit/notes notes)
                               sensitive                (assoc :habit/sensitive sensitive))]

              (biff/submit-tx ctx [habit]))))))))

(defn port-habit-logs [{:keys [biff/db] :as ctx}]
  (let [user-id (biff/lookup-id db :user/email email)]
    (with-open [rdr (io/reader log-file-name)]
      (doseq [line (->> rdr line-seq)]
        (let [edn-data   (edn/read-string line)
              activities (-> edn-data (get "fields") (get "activity"))]
          (when (seq activities)
            (let [at-id         (-> edn-data (get "id"))
                  created-at    (-> edn-data (get "createdTime") (t/instant))
                  fields        (-> edn-data (get "fields"))
                  notes         (-> fields (get "notes"))
                  timestamp-raw (-> fields (get "timestamp"))
                  timestamp     (if (some? timestamp-raw)
                                  (-> timestamp-raw (t/instant))
                                  created-at)
                  habit-ids     (->> activities
                                     (map (fn [at-id] (biff/lookup-id db :airtable/id at-id)))
                                     set)
                  xt-id         (uuid/v5 ns-uuid-activity-log at-id)
                  habit-log     (cond-> {:xt/id               xt-id
                                         :db/doc-type         :habit-log
                                         ::sm/type        :habit-log
                                         ::sm/created-at  created-at
                                         :user/id             user-id
                                         :habit-log/timestamp timestamp
                                         :habit-log/time-zone tz-str
                                         :habit-log/habit-ids habit-ids}
                                  (not (str/blank? notes)) (assoc :habit-log/notes notes))]
              (biff/submit-tx ctx [habit-log]))))))))

(comment
  ;; local dev
  (port-habits (get-context))
  (port-habit-logs (get-context))

  ;; prod db
  (def prod-node (prod-node-start)) ;; only call once in a repl instance
  (port-habits (get-prod-db-context prod-node))
  (port-habit-logs (get-prod-db-context prod-node))
  ;;
  )
