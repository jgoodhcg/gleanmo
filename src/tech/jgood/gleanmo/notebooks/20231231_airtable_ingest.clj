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
{::clerk/visibility {:code :fold :result :show}}

;; ### Deterministic UUIDs
;; I want all of the ported items to have a consistent xt/id type -- UUID.
;; However, I will be iterating on processing and might not delete the entire xtdb each run.
;; I want to be able to run the porting __deterministically__ and not duplicate information.
;; So the UUID id needs to be deterministically derived from the airtable record.
(def namespace-uuid #uuid "ba5589b9-e2a2-47b9-9273-86206538c0e2")

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

{::clerk/visibility {:code :show :result :show}}
(xt/q (xt/db xtdb-node)
      '{:find [?label ?id]
        :where [[?e :gleanmo/type :exercise]
                [?e :exercise/label ?label]
                [?e :xt/id ?id]]})

;; ### Sessions
;; ### Logs
;; ### Sets
