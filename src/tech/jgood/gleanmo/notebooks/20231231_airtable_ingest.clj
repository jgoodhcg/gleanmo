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
            [clojure.set :refer [difference]]
            [clojure.data :refer [diff]]
            [clojure.edn :as edn]
            [xtdb.api :as xt]
            [potpuri.core :as pot]
            [clojure-csv.core :as csv]
            [clojure.string :as str]
            [clj-uuid :as uuid]
            [tick.core :as t]
            [clj-commons.digest :as digest]))

;; ## Read in data from files
(def exercise-file "notebook_data/2023-12-31__17_31_50_247708_exercises.edn")
(def exercise-log-file "notebook_data/2023-12-31__14_07_28_274238_exercise_log.edn")

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

;; ## Writing to XTDB
;; ### XTDB Setup
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

;; Write exercises to xtdb
(defn write-exercises-to-xtdb! []
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
                         (when (not (str/blank? (:exercise/label item)))
                           [::xt/put item])))))
           (->> (remove nil?))
           vec
           (->> (xt/submit-tx xtdb-node))))))
  ;;
  )

(defn q-exercises []
  (xt/q (xt/db xtdb-node)
        '{:find [(pull ?e [*])]
          :where [[?e :gleanmo/type :exercise]]}))

;; ### Logs & Sets
;; Every airtable log is effectively a gleanmo log with one set.
;; When tracking in gleanmo logs will have more than one set.
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

(defn xform-exercise-log [{:keys [id createdTime fields]}]
  (let [{:keys [timestamp duration exercise]}
        fields
        exrcs-at-id (-> exercise first)]
    (if (and (-> timestamp str/blank? not)
             (-> exrcs-at-id str/blank? not))
      (let [log-id      (generate-deterministic-uuid id)
            set-id      (generate-deterministic-uuid (str id "-set"))
            exercise-id (get-exercise-id exrcs-at-id)
            beg         (t/instant timestamp)
            end         (when (number? duration)
                          (-> beg (t/>> (t/new-duration duration :seconds))))
            distance    (-> fields :distance)
            angle       (-> fields :angle)
            notes       (-> fields :notes)
            btn         (get fields (keyword "better than normal"))
            wtn         (get fields (keyword "worse than normal"))
            relativity  (if (some? btn) :better
                            (if (some? wtn) :worse
                                nil))
            reps        (-> fields :reps)
            weight      (-> fields :weight)]
        {:exercise-log (merge {:xt/id                           log-id
                               :gleanmo/type                    :exercise-log
                               :exercise/id                     exercise-id
                               :exercise-log.interval/beginning beg
                               :exercise-log.interval/end       end
                               :exercise-log/exercise-set-ids   #{set-id}
                               :airtable/exercise-id            exrcs-at-id
                               :airtable/ported                 true}
                              (when (some? notes)
                                {:exercise-log/notes notes})
                              (when (some? relativity)
                                {:exercise-log/relativety-score relativity}))
         :exercise-set (merge {:xt/id                           set-id
                               :gleanmo/type                    :exercise-set
                               :exercise-log/id                 log-id
                               :exercise-set.interval/beginning beg
                               :exercise-set.interval/end       end
                               :airtable/ported                 true}
                              (when (some? distance)
                                {:exercise-set/distance      distance
                                 :exercise-set/distance-unit :miles})
                              (when (some? angle)
                                {:exercise-set/inversion-angle angle})
                              (when (some? reps)
                                {:exercise-set/reps reps})
                              (when (some? weight)
                                {:exercise-set/weight-amount weight
                                 :exercise-set/weight-unit   :lb}))})
      nil)))

;; Write exercise-logs and sets to xtdb
(defn write-exercise-logs-to-xtdb! []
  (with-open [rdr (io/reader exercise-log-file)]
    (let [lines (line-seq rdr)]
      (doall
       (-> lines
           (->> (map (fn [line]
                       (let [{:keys [exercise-log
                                     exercise-set]
                              :as result}
                             (-> line
                                 edn/read-string
                                 (->> (pot/map-keys keyword))
                                 (update :fields #(pot/map-keys keyword %))
                                 xform-exercise-log)]
                         (if (some? result)
                           [exercise-log exercise-set]
                           nil)))))
           flatten
           (->> (remove nil?))
           (->> (map (fn [item] [::xt/put item])))
           vec
           (->> (xt/submit-tx xtdb-node))))))
  ;;
  )

(defn q-exercise-logs []
  (xt/q (xt/db xtdb-node)
        '{:find [(pull ?e [*])]
          :where [[?e :gleanmo/type :exercise-log]]}))

(defn q-exercise-log-with-data []
  (xt/q (xt/db xtdb-node)
        '{:find  [(pull ?el [:exercise-log.interval/beginning])
                  (pull ?e  [:exercise/label])
                  (pull ?es [:exercise-set/reps :exercise-set/weight-amount])]
          :where [[?e  :gleanmo/type    :exercise]

                  [?es :gleanmo/type    :exercise-set]
                  [?es :exercise-log/id ?el]

                  [?el :gleanmo/type    :exercise-log]
                  [?el :exercise/id     ?e]]
          :limit 1}))

;; ### Filling in with average durations
(defn fetch-exercises-with-duration []
  (xt/q (xt/db xtdb-node)
        '{:find  [?label
                  ?exercise-id
                  (avg (t/seconds ?duration))]
          :where [[?log-id :exercise/id ?exercise-id]
                  [?log-id :exercise-log.interval/beginning ?beginning]
                  [?log-id :exercise-log.interval/end ?end]
                  [?exercise-id :exercise/label ?label]
                  [(some? ?beginning)]
                  [(some? ?end)]
                  [(t/between ?beginning ?end) ?duration]]}))

(defn fetch-exercise-logs-without-end
  ([]
   (xt/q (xt/db xtdb-node)
         '{:find  [?log-id ?exercise-id ?set-id]
           :where [[?log-id :exercise/id ?exercise-id]
                   [?log-id :exercise-log.interval/end ?end]
                   [?set-id :exercise-log/id ?log-id]
                   [(nil? ?end)]]}))
  ([exercise-id]
   (xt/q (xt/db xtdb-node)
         '{:find  [?log-id exercise-id ?set-id]
           :where [[?log-id :exercise/id exercise-id]
                   [?log-id :exercise-log.interval/end ?end]
                   [?set-id :exercise-log/id ?log-id]
                   [(nil? ?end)]]
           :in [exercise-id]}
         exercise-id)))

(defn stats-on-missing-end []
  (let [logs (first (first (xt/q (xt/db xtdb-node)
                                 '{:find [(count ?el)]
                                   :where [[?el :gleanmo/type :exercise-log]]})))
        sets (first (first (xt/q (xt/db xtdb-node)
                                 '{:find [(count ?el)]
                                   :where [[?el :gleanmo/type :exercise-set]]})))
        missing-logs (first (first (xt/q (xt/db xtdb-node)
                                         '{:find [(count ?el)]
                                           :where [[?el :gleanmo/type :exercise-log]
                                                   [?el :exercise-log.interval/end ?end]
                                                   [(nil? ?end)]]})))
        missing-sets (first (first (xt/q (xt/db xtdb-node)
                                         '{:find [(count ?el)]
                                           :where [[?el :gleanmo/type :exercise-set]
                                                   [?el :exercise-set.interval/end ?end]
                                                   [(nil? ?end)]]})))
        exercises-missing-logs (xt/q (xt/db xtdb-node)
                                     '{:find [?label (count ?set)]
                                       :where [[?el :gleanmo/type :exercise-log]
                                               [?el :exercise/id ?e]
                                               [?el :exercise-log/exercise-set-ids ?set]
                                               [?e :gleanmo/type :exercise]
                                               [?e :exercise/label ?label]
                                               [?el :exercise-log.interval/end ?end]
                                               [(nil? ?end)]]})]
    (pot/map-of logs sets missing-logs missing-sets
                exercises-missing-logs)))

(defn generate-puts-for-missing-ends []
  (doall
   (->> (fetch-exercises-with-duration)
        ;; for each exercise
        (map (fn [[_ e-id avg]]
               (let [logs (fetch-exercise-logs-without-end e-id)]
                 ;; find all the logs without an end
                 (when (seq logs)
                   (doall
                    (->> logs
                         (map (fn [[log-id _ set-id]]
                                (let [log (xt/entity (xt/db xtdb-node) log-id)
                                      set (xt/entity (xt/db xtdb-node) set-id)
                                      beg-l (-> log :exercise-log.interval/beginning)
                                      beg-s (-> set :exercise-set.interval/beginning)
                                      end-l (t/>> beg-l (t/new-duration avg :seconds))
                                      end-s (t/>> beg-s (t/new-duration avg :seconds))]
                                  ;; create a put operation for the log and set
                                  ;; all airtable logs have just one set
                                  [[::xt/put
                                    (-> log
                                        (assoc
                                         :exercise-log.interval/end
                                         end-l
                                         :airtable/missing-duration
                                         true
                                         :exercise-log.interval/averaged-end
                                         true))]
                                   [::xt/put
                                    (-> set
                                        (assoc
                                         :exercise-set.interval/end
                                         end-l
                                         :airtable/missing-duration
                                         true
                                         :exercise-set.interval/averaged-end
                                         true))]])))
                         ;; flatten out to one list of operations
                         (mapcat identity)))))))
        ;; remove the lists of exercises with no missing log end timestamps
        (remove nil?)
        ;; flatten out the exercise lists into a single list of operations
        (mapcat identity))))

(defn determine-median-interval-seconds []
  (->>
   (xt/q (xt/db xtdb-node)
         '{:find  [(median (t/seconds ?duration))]
           :where [[?log-id :exercise-log.interval/beginning ?beginning]
                   [?log-id :exercise-log.interval/end ?end]
                   [(some? ?beginning)]
                   [(some? ?end)]
                   [(t/between ?beginning ?end) ?duration]]})
   first
   first))

(defn exercises-with-only-nil-ends []
  (let [exercises-with-ends
        (set (map first (xt/q (xt/db xtdb-node)
                              '{:find  [?e]
                                :where [[?l :exercise/id ?e]
                                        [?l :exercise-log.interval/end ?end]
                                        [(some? ?end)]]})))
        all-exercises
        (set (map first (xt/q (xt/db xtdb-node)
                              '{:find  [?e]
                                :where [[?e :gleanmo/type :exercise]]})))
        exercises-with-only-nil-ends-exclusively
        (difference all-exercises exercises-with-ends)]
    exercises-with-only-nil-ends-exclusively))

(defn generate-puts-for-exercises-with-only-nil-ends []
  (let [median-interval-seconds (determine-median-interval-seconds)
        med-dur                 (t/new-duration median-interval-seconds :seconds)
        exercises               (exercises-with-only-nil-ends)
        puts                    (for [exercise-id exercises]
                                  (let [logs (xt/q (xt/db xtdb-node)
                                                   '{:find  [(pull ?log-id [*])]
                                                     :where [[?log-id :exercise/id exercise-id]
                                                             [?log-id :exercise-log.interval/beginning ?beginning]
                                                             [?log-id :exercise-log.interval/end ?end]
                                                             [(nil? ?end)]]
                                                     :in [exercise-id]}
                                                   exercise-id)
                                        sets (xt/q (xt/db xtdb-node)
                                                   '{:find  [(pull ?set-id [*])]
                                                     :where [[?log-id :exercise/id exercise-id]
                                                             [?log-id :exercise-log/exercise-set-ids ?set-id]
                                                             [?set-id :exercise-set.interval/beginning ?beginning]
                                                             [?set-id :exercise-set.interval/end ?end]
                                                             [(nil? ?end)]]
                                                     :in [exercise-id]}
                                                   exercise-id)]
                                    (concat
                                     (->> logs
                                          (mapcat identity)
                                          (map (fn [{:exercise-log.interval/keys [beginning]
                                                    :as                         log}]
                                                 (let [end (t/>> beginning med-dur)]
                                                   [::xt/put (-> log
                                                                (assoc :exercise-log.interval/end end)
                                                                (assoc :airtable/missing-duration true)
                                                                (assoc :exercise-log.interval/global-median-end true))]))))
                                     (->> sets
                                          (mapcat identity)
                                          (map (fn [{:exercise-set.interval/keys [beginning]
                                                    :as                         set}]
                                                 (let [end (t/>> beginning med-dur)]
                                                   [::xt/put (-> set
                                                                (assoc :exercise-set.interval/end end)
                                                                (assoc :airtable/missing-duration true)
                                                                (assoc :exercise-set.interval/global-median-end true))])))))))]

    (->> puts
         (mapcat identity)
         vec)))

;; ## Actually reading and writing to xtdb
;; This was problematic to have so many side effects in the notebook because of dynamic loading
;; Pulled it out to work with it at the repl
(comment
  (write-exercises-to-xtdb!)
  (write-exercise-logs-to-xtdb!)
  (q-exercise-log-with-data)
  (xt/submit-tx xtdb-node (generate-puts-for-missing-ends))
  (xt/submit-tx xtdb-node (generate-puts-for-exercises-with-only-nil-ends))
  (fetch-exercise-logs-without-end)
  (stats-on-missing-end)
  (count (q-exercises))
  (count (fetch-exercise-logs-without-end))
  (count (fetch-exercise-logs-without-end))
  ;; some exercises don't have logs
  (count (fetch-exercises-with-duration))

;;
  )

;; ### Sessions
