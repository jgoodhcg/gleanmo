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
            [tick.core :as t]))

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
        new-uuid              (java.util.UUID/randomUUID)]
    (->>
     {:xt/id                  new-uuid
      :xt/valid-time          (t/instant createdTime)
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
                       (xt/submit-tx xtdb-node [[::xt/put item]])))))))))

{::clerk/visibility {:code :show :result :show}}
(xt/q (xt/db xtdb-node) `{:find [e]
                          :where [[e :gleanmo/type :exercise]]})
;; ### Sessions
;; ### Logs
;; ### Sets
