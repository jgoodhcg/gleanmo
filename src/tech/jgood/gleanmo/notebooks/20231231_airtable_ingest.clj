;; # Ingesting Airtable Records
;; I'm trying to accomplish two goals with this notebook.
;; - Set data driven goals to improve my exercise habits
;; - Draft transformation of data from airtable to something that works for xtdb/gleanmo
(ns tech.jgood.gleanmo.notebooks.20231231-airtable-ingest
  {:nextjournal.clerk/toc                   true
   :nextjournal.clerk/error-on-missing-vars :off}
  (:require [nextjournal.clerk :as clerk]
            [semantic-csv.core :as sc]
            [clojure.java.io :as io]
            [com.rpl.specter :as sp]
            [clojure.edn :as edn]
            [xtdb.api :as xt]
            [potpuri.core :as pot]
            [clojure-csv.core :as csv]
            [clojure.string :as str]
            [clj-uuid :as uuid]
            [tick.core :as t]
            [clj-commons.digest :as digest]))

;; ## Read in data from files
{::clerk/visibility {:code :fold :result :show}}
(def exercise-file "notebook_data/2023-12-31__17_31_50_247708_exercises.edn")
(def exercise-log-file "notebook_data/2023-12-31__14_07_28_274238_exercise_log.edn")

;; ### Exercise-logs
(with-open [rdr (io/reader exercise-log-file)]
  (let [lines (line-seq rdr)]
    (-> lines
        (->> (map (fn [line]
                    (-> line
                        edn/read-string
                        (->> (pot/map-keys keyword))
                        (update :fields #(pot/map-keys keyword %))
                        (update-in [:fields :timestamp]
                                   #(if (or (nil? %)
                                            (str/blank? %))
                                      % (t/instant %)))))))
        shuffle
        (->> (take 2)))))

;; ### Exercises
(with-open [rdr (io/reader exercise-file)]
  (let [lines (line-seq rdr)]
    (-> lines
        (->> (map (fn [line]
                    (-> line
                        edn/read-string
                        (->> (pot/map-keys keyword))
                        (update :fields #(pot/map-keys keyword %))))))
        shuffle
        (->> (take 2)))))

;; ## Writing to XTDB
;;
;; ### XTDB Setup
{::clerk/visibility {:code :fold :result :hide}}
(defn start-xtdb! []
  (letfn [(kv-store [dir]
            {:kv-store {:xtdb/module 'xtdb.rocksdb/->kv-store
                        :db-dir (io/file dir)
                        :sync? true}})]
    (xt/start-node
     {:xtdb/tx-log (kv-store "notebook_data/xtdb/tx-log")
      :xtdb/document-store (kv-store "notebook_data/xtdb/doc-store")
      :xtdb/index-store (kv-store "notebook_data/xtdb/index-store")})))
(defonce xtdb-node (start-xtdb!))
(defn stop-xtdb! []
  (.close xtdb-node))

;; ### Deterministic UUIDs
;; I want all of the ported items to have a consistent xt/id type -- UUID.
;; However, I will be iterating on processing and might not delete the entire xtdb each run.
;; I want to be able to run the porting __deterministically__ and not duplicate information.
;; So the UUID id needs to be deterministically derived from the airtable record.
{::clerk/visibility {:code :fold :result :show}}
(def namespace-uuid #uuid "ba5589b9-e2a2-47b9-9273-86206538c0e2")

{::clerk/visibility {:code :fold :result :hide}}
(defn generate-deterministic-uuid [seed]
  (uuid/v5 namespace-uuid seed))

;; ### Exercises
(defn xform-exercise [{:keys [id createdTime fields]}]
  (let [{:keys [name
                notes
                distance-unit
                weight-unit
                exercise-log
                source
                log-count
                latest-done]} fields
        new-uuid              (generate-deterministic-uuid id)
        valid-time            (t/instant createdTime)]
    (->>
     {:xt/id                  new-uuid
      :xt/valid-time          valid-time
      :gleanmo/type           :exercise
      :exercise/label         name
      :exercise/notes         notes
      :exercise/source        source
      :airtable/ported        true
      :airtable/created-time  createdTime
      :airtable/distance-unit distance-unit
      :airtable/id            id
      :airtable/exercise-log  exercise-log
      :airtable/weight-unit   weight-unit
      :airtable/log-count     log-count
      :airtable/latest-done   latest-done}

     ;; effectively dissoc's anything with a nil value
     (sp/setval [sp/MAP-VALS nil?] sp/NONE))))

(with-open [rdr (io/reader exercise-file)]
  (let [lines (line-seq rdr)]
    (doall
     (-> lines
         (->> (map (fn [line]
                     (let [item
                           (-> line
                               edn/read-string
                               (->> (pot/map-keys keyword))
                               (update :fields #(pot/map-keys keyword %))
                               xform-exercise)]
                       [::xt/put item]))))
         vec
         (->> (xt/submit-tx xtdb-node))))))

{::clerk/visibility {:code :fold :result :show}}
(xt/q (xt/db xtdb-node)
      '{:find [(pull ?e [*])]
        :where [[?e :gleanmo/type :exercise]]})

;; ### Logs & Sets
;; Every airtable log is effectively a gleanmo log with one set.
;; When tracking is done in gleanmo logs will have more than one set.
(defn get-exercise-id [airtable-id]
  (-> (xt/q (xt/db xtdb-node)
            '{:find  [?id]
              :where [[?e :gleanmo/type :exercise]
                      [?e :airtable/id airtable-id]
                      [?e :xt/id ?id]]
              :in    [airtable-id]}
            airtable-id)
      first
      first))

(get-exercise-id "rec61KbEbx5uAVtAW")

{::clerk/visibility {:code :show :result :show}}
(defn xform-exercise-log [{:keys [id createdTime fields]}]
  (let [{:keys [timestamp duration exercise]}
        fields
        exrcs-at-id (-> exercise first)]

    (if (and (-> timestamp str/blank? not)
             (-> exrcs-at-id str/blank? not)
             (-> duration integer?))
      (let [log-id      (generate-deterministic-uuid id)
            set-id      (generate-deterministic-uuid (str id "-set"))
            exercise-id (get-exercise-id exrcs-at-id)
            beg         (t/instant timestamp)
            end         (-> beg (t/>> (t/new-duration duration :seconds)))]
        {:exercise-log {:xt/id                           log-id
                        :gleanmo/type                    :exercise-log
                        :exercise/id                     exercise-id
                        :exercise-log.interval/beginning beg
                        :exercise-log.interval/end       end
                        :exercise-log/exercise-set-ids   #{set-id}}
         :exercise-set {:xt/id set-id}})
      nil)))

(with-open [rdr (io/reader exercise-log-file)]
  (let [lines (line-seq rdr)]
    (doall
     (-> lines
         (->> (map (fn [line]
                     (let [item
                           (-> line
                               edn/read-string
                               (->> (pot/map-keys keyword))
                               (update :fields #(pot/map-keys keyword %))
                               xform-exercise-log)]
                       (if (some? item)
                         [::xt/put item]
                         nil)))))
         (->> (remove nil?))
         shuffle
         (->> (take 2))
         #_#_vec
           (->> (xt/submit-tx xtdb-node))))))

;; ### Sessions
