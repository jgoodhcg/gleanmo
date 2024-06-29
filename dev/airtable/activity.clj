(ns airtable.activity
  (:require
   [clj-uuid :as uuid]
   [tech.jgood.gleanmo.schema :as schema]
   [clojure.edn :as edn]
   [potpuri.core :as pot]
   [clojure.string :as str]
   [clojure.java.io :as io]
   [tick.core :as t]
   [clojure.pprint :refer [pprint]]
   [repl :refer [get-context]]
   [com.biffweb :as biff :refer [q]]))

(def activity-file-name
  "/Users/justingood/projects/gleanmo/airtable_data/activity_2024_06_25_08_53_52_288616.edn")
(def log-file-name
  "/Users/justingood/projects/gleanmo/airtable_data/activity_log_2024_06_25_08_55_08_082302.edn")
(def ns-uuid-activity
  #uuid "d542f8dc-96d1-495d-afa6-738750c08510")
(def ns-uuid-activity-log
  #uuid "f4ab292b-5e61-4c71-9a3a-bc75da0525e3")
(def email
  "justin@jgood.online")
(def tz-str
  "America/Detroit") ;; use (t/zone) to check if it's a real timezone string identifier

(defn port-habits []
  (let [{:keys [biff/db] :as ctx} (get-context)
        user-id                   (biff/lookup-id db :user/email email)]
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
                                      ::schema/type       :habit
                                      ::schema/created-at created-at
                                      :user/id            user-id
                                      :habit/name         habit-name
                                      :airtable/id        at-id
                                      :airtable/ported-at now}
                               (not (str/blank? notes)) (assoc :habit/notes notes)
                               sensitive                (assoc :habit/sensitive sensitive))]

              (biff/submit-tx ctx [habit]))))))))

(defn port-habit-logs []
  (let [{:keys [biff/db] :as ctx} (get-context)
        user-id                   (biff/lookup-id db :user/email email)]
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
                                         ::schema/type        :habit-log
                                         ::schema/created-at  created-at
                                         :user/id             user-id
                                         :habit-log/timestamp timestamp
                                         :habit-log/time-zone tz-str
                                         :habit-log/habit-ids habit-ids}
                                  (not (str/blank? notes)) (assoc :habit-log/notes notes))]
              (biff/submit-tx ctx [habit-log]))))))))

(comment
  ;; habits
  (port-habits)

  ;; habit-logs
  (port-habit-logs)
  ;;
  )
