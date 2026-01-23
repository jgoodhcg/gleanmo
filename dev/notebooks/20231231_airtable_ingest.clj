;; # Ingesting Airtable Records
;; I'm trying to accomplish two goals with this notebook.
;; - Set data driven goals to improve my exercise habits
;; - Draft transformation of data from airtable to something that works for xtdb/gleanmo
(ns notebooks.20231231-airtable-ingest
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
            [tick.alpha.interval :as t.i]
            [clj-commons.digest :as digest]))

;; ## Read in data from files
#_#_(def exercise-file "notebook_data/2023-12-31__17_31_50_247708_exercises.edn")
  (def exercise-log-file "notebook_data/2023-12-31__14_07_28_274238_exercise_log.edn")
(def exercise-file "notebook_data/2024-02-29__09_37_08_386115_exercises.edn")
(def exercise-log-file "notebook_data/2024-02-29__09_38_03_404207_exercise_log.edn")

#_#_;; ### Exercises
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
     #_{:clj-kondo/ignore [:unresolved-var]}
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
                               :exercise-log.interval/beginning beg
                               :exercise-log.interval/end       end
                               :airtable/ported                 true}
                              (when (some? notes)
                                {:exercise-log/notes notes})
                              (when (some? relativity)
                                {:exercise-log/relativety-score relativity}))
         :exercise-set (merge {:xt/id                           set-id
                               :gleanmo/type                    :exercise-set
                               :exercise-log/id                 log-id
                               :exercise/id                     exercise-id
                               :airtable/exercise-id            exrcs-at-id
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
           (->> (xt/submit-tx xtdb-node)))))))

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
                  [?es :exercise/id     ?e]

                  [?el :gleanmo/type    :exercise-log]]
          :limit 1}))

;; ### Filling in with average durations
(defn fetch-exercises-with-duration []
  (xt/q (xt/db xtdb-node)
        '{:find  [?label
                  ?exercise-id
                  (avg (t/seconds ?duration))]
          :where [[?set-id :exercise/id ?exercise-id]
                  [?set-id :exercise-set.interval/beginning ?beginning]
                  [?set-id :exercise-set.interval/end ?end]
                  [?exercise-id :exercise/label ?label]
                  [(some? ?beginning)]
                  [(some? ?end)]
                  [(t/between ?beginning ?end) ?duration]]}))

(defn fetch-exercise-logs-and-sets-without-end
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
                                 '{:find [(count ?es)]
                                   :where [[?es :gleanmo/type :exercise-set]]})))
        all-exercises (-> (xt/q (xt/db xtdb-node)
                                '{:find [?e]
                                  :where [[?e :gleanmo/type :exercise]]})
                          vec
                          flatten
                          set)
        all-exercises-count (count all-exercises)
        exercises-with-sets (-> (xt/q (xt/db xtdb-node)
                                      '{:find [?e]
                                        :where [[?es :gleanmo/type :exercise-set]
                                                [?es :exercise/id ?e]]})
                                vec
                                flatten
                                set)
        exercises-with-sets-count (count exercises-with-sets)
        missing-logs (first (first (xt/q (xt/db xtdb-node)
                                         '{:find [(count ?el)]
                                           :where [[?el :gleanmo/type :exercise-log]
                                                   [?el :exercise-log.interval/end ?end]
                                                   [(nil? ?end)]]})))
        missing-sets (first (first (xt/q (xt/db xtdb-node)
                                         '{:find [(count ?es)]
                                           :where [[?es :gleanmo/type :exercise-set]
                                                   [?es :exercise-set.interval/end ?end]
                                                   [(nil? ?end)]]})))
        exercises-missing-log-set-entries (count (difference all-exercises exercises-with-sets))]
    (pot/map-of logs
                sets
                missing-logs
                missing-sets
                exercises-missing-log-set-entries
                exercises-with-sets-count
                all-exercises-count)))

(defn generate-puts-for-missing-ends []
  (doall
   (->> (fetch-exercises-with-duration)
        ;; for each exercise
        (map (fn [[_ e-id avg]]
               (let [logs (fetch-exercise-logs-and-sets-without-end e-id)]
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
                                         end-s
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
                                                     :where [[?set-id :exercise/id exercise-id]
                                                             [?set-id :exercise-log/id ?log-id]
                                                             [?log-id :exercise-log.interval/beginning ?beginning]
                                                             [?log-id :exercise-log.interval/end ?end]
                                                             [(nil? ?end)]]
                                                     :in    [exercise-id]}
                                                   exercise-id)
                                        sets (xt/q (xt/db xtdb-node)
                                                   '{:find  [(pull ?set-id [*])]
                                                     :where [[?set-id :exercise/id exercise-id]
                                                             [?set-id :exercise-set.interval/beginning ?beginning]
                                                             [?set-id :exercise-set.interval/end ?end]
                                                             [(nil? ?end)]]
                                                     :in    [exercise-id]}
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

;; ### Grouping logs into sessions
(defn query-all-logs []
  (xt/q (xt/db xtdb-node)
        '{:find  [(pull ?e [*])]
          :where [[?e :gleanmo/type :exercise-log]]}))

(defn group-logs [log-groups log]
  ;; Handle the first iteration
  (if (empty? log-groups)
    ;; And just start the first group
    (conj log-groups [log])
    ;; Then start comparing the last log in the last group with the next log
    (let [last-lg  (last log-groups)
          last-log (last last-lg)
          ll-end   (:exercise-log.interval/end last-log)
          ll-bound (-> ll-end (t/>> (t/new-duration 10 :minutes)))
          log-beg  (:exercise-log.interval/beginning log)]
      (if (-> log-beg (t/< ll-bound))
        ;; Add the log to the last group
        (conj (vec (butlast log-groups)) (conj last-lg log))
        ;; Make a new group
        (conj log-groups [log])))))

(defn generate-session-puts []
  (->> (query-all-logs)
       vec
       flatten
       vec
       (sort-by :exercise-log.interval/beginning)
       (reduce group-logs [])
       (mapcat (fn [logs]
                 (let [log-ids    (->> logs
                                       (map :xt/id))
                       session-id (generate-deterministic-uuid
                                   (str log-ids))
                       beg        (->> logs
                                       (sort-by :exercise-log.interval/beginning)
                                       first
                                       :exercise-log.interval/beginning)
                       end        (->> logs
                                       (sort-by :exercise-log.interval/end)
                                       reverse
                                       first
                                       :exercise-log.interval/end)]
                   (conj
                    (->> logs
                         (mapv (fn [l]
                                 [::xt/put (-> l
                                               (assoc
                                                :exercise-session/id
                                                session-id))])))
                    [::xt/put {:xt/id                      session-id
                               :gleanmo/type               :exercise-session
                               :exercise-session/beginning beg
                               :exercise-session/end       end}]))))))

;; ### Actually transacting
;; This is put in a comment so that clerk reloading doesn't mess with anything
(comment
  ;; Write all the valid exercises, logs, and sets to xtdb
  (write-exercises-to-xtdb!)
  ;; This includes sets
  (write-exercise-logs-to-xtdb!)
  (q-exercise-log-with-data)
  ;; Clean up all missing durations
  (xt/submit-tx xtdb-node
                (generate-puts-for-missing-ends))
  (xt/submit-tx xtdb-node
                (generate-puts-for-exercises-with-only-nil-ends))
  ;; The below should show no log or set is missing duration now
  (fetch-exercise-logs-and-sets-without-end)
  (count (fetch-exercise-logs-and-sets-without-end))
  (count (fetch-exercise-logs-and-sets-without-end))
  (stats-on-missing-end)
  ;; Total amount of exercises
  (count (q-exercises))
  ;; some exercises don't have logs so this number will be less than the above
  (count (fetch-exercises-with-duration))
  ;; sessions
  (xt/submit-tx xtdb-node (generate-session-puts))
;;
  )

;; Now let's answer some questions and start visualizing
;; ## Heatmaps of relative score
;; ### Consolidating data
(defn days-until-next-week [date]
  (->> date
       t/day-of-week
       t/int
       (- 8)))

(defn week-number-of-month [date]
  (let [year-month (t/year-month date)
        month      (t/int (t/month date))
        day        (t/day-of-month date)]
    (loop [d (t/date (str year-month "-" "01"))
           w 1]
      (let [start-of-next-week (t/>> d (t/new-period (days-until-next-week d) :days))]
        (if (and (= (t/int (t/month start-of-next-week)) month)
                 (-> start-of-next-week (t/day-of-month) (< day)))
          (recur start-of-next-week (inc w))
          w)))))

(defn week-number-of-year [date]
  (let [year (t/year date)]
    (loop [d (t/date (str year "-01-01"))
           w 1]
      (let [start-of-next-week (t/>> d (t/new-period (days-until-next-week d) :days))]
        (if (and (= (t/year start-of-next-week) year)
                 (-> start-of-next-week
                     (t.i/relation date)
                     ((fn [r] (some #{:precedes :meets :equals} [r])))))
          (recur start-of-next-week (inc w))
          w)))))

(def calendar
  (->> (t/range (t/date "2021-01-01") (t/date (t/now))
                (t/new-period 1 :days))
       (mapv (fn [d] {:date d}))))

(def rwd-by-day-data-proto
  (->>
   (xt/q (xt/db xtdb-node)
         '{:find  [(pull ?es [*])]
           :where [[?es :gleanmo/type :exercise-set]]})
   vec
   flatten
   (group-by (fn [{beg :exercise-set.interval/beginning}] (t/date beg)))
   (pot/map-vals (fn [sets]
                   (let [reps             (->> sets
                                               (mapv :exercise-set/reps)
                                               (remove nil?)
                                               (reduce +))
                         weight           (->> sets
                                               (mapv :exercise-set/weight-amount)
                                               (remove nil?)
                                               (reduce +))
                         duration-seconds (->> sets
                                               (mapv (fn [{beg :exercise-set.interval/beginning
                                                           end :exercise-set.interval/end}]
                                                       (t/seconds (t/between beg end))))
                                               (reduce +))]
                     (pot/map-of reps weight duration-seconds))))
   (mapv (fn [[d vals]]
           (merge {:date         d
                   :date-str     (str d)
                   :day-of-week  (str (t/day-of-week d))
                   :week-of-year (week-number-of-year d)} vals)))))

(defn merge-data [calendar rwd-by-day-data-proto]
  (let [calendar-map (into {} (map (fn [{:keys [date]}]
                                     [date {:date             date
                                            :reps             0
                                            :weight           0
                                            :duration-seconds 0
                                            :date-str         (str date)
                                            :day-of-week      (str (t/day-of-week date))
                                            :week-of-year     (week-number-of-year date)}]) calendar))
        rwd-map      (into {} (map (fn [{:keys [date] :as entry}] [date entry]) rwd-by-day-data-proto))]
    (vec (map (fn [[date cal-entry]]
                (if-let [rwd-entry (get rwd-map date)]
                  (merge cal-entry rwd-entry)
                  cal-entry))
              calendar-map))))

(def rwd-by-day-data-proto-2 (merge-data calendar rwd-by-day-data-proto))

;; Highest duration
(def max-duration
  (->> rwd-by-day-data-proto-2
       (map :duration-seconds)
       (reduce max)))

;; Highest reps
(def max-reps
  (->> rwd-by-day-data-proto-2
       (map :reps)
       (reduce max)))

;; Highest weight
(def max-weight
  (->> rwd-by-day-data-proto-2
       (map :weight)
       (reduce max)))

(defn safe-division [n d]
  (if (-> n (> 0))
    (-> n (/ d) float)
    0))

(def rwd-by-day-data
  (->> rwd-by-day-data-proto-2
       (mapv (fn [{r   :reps
                   d   :duration-seconds
                   w   :weight
                   :as entry}]
               (let [relative-duration (safe-division d max-duration)
                     relative-reps     (safe-division r max-reps)
                     relative-weight   (safe-division w max-weight)
                     relative-total    (-> (+ relative-duration relative-reps relative-weight)
                                           (safe-division 3))]
                 (merge entry (pot/map-of relative-duration
                                          relative-reps
                                          relative-weight
                                          relative-total)))))))

;; ### Visuals
;; #### RWD Relative
(def rwd-rel-heatmap-config
  {:data   {:values []}
   :mark   "rect"
   :config {:view {:stroke-width 0
                   :step         15}}
   :width  650
   :encoding
   {:x     {:field    "date-str"
            :type     "temporal"
            :timeUnit "week"
            :axis     {:labelExpr  "monthAbbrevFormat(month(datum.value))"
                       :labelAlign "middle"
                       :title      nil}}
    :y     {:field "day-of-week"
            :type  "ordinal"
            :sort  ["MONDAY" "TUESDAY" "WEDNESDAY" "THURSDAY" "FRIDAY" "SATURDAY"]
            :axis  {:title nil}}
    :color {:field     "relative-total"
            :type      "quantitative"
            :legend    {:title nil}}}})
;; 2024
(clerk/vl (merge rwd-rel-heatmap-config
                 {:data {:values (->> rwd-by-day-data
                                      (filter (fn [{d :date}] (= (t/year d) (t/year 2024)))))}}))
;; 2023
(clerk/vl (merge rwd-rel-heatmap-config
                 {:data {:values (->> rwd-by-day-data
                                      (filter (fn [{d :date}] (= (t/year d) (t/year 2023)))))}}))
;; 2022
(clerk/vl (merge rwd-rel-heatmap-config
                 {:data {:values (->> rwd-by-day-data
                                      (filter (fn [{d :date}] (= (t/year d) (t/year 2022)))))}}))
;; 2021
(clerk/vl (merge rwd-rel-heatmap-config
                 {:data {:values (->> rwd-by-day-data
                                      (filter (fn [{d :date}] (= (t/year d) (t/year 2021)))))}}))

;; #### Sessions

(defn query-types-with-attributes []
  (let [types #{:exercise-log :exercise-set :exercise-session :exercise}
        type-to-attributes (atom {})]
    (doseq [type types]
      (let [results (xt/q (xt/db xtdb-node)
                          '{:find  [(pull ?e [*])]
                            :where [[?e :gleanmo/type type]]
                            :in [type]}
                          type)
            attributes-set (reduce (fn [acc record]
                                     (into acc (keys record)))
                                   #{}
                                   (map first results))]
        (swap! type-to-attributes assoc type attributes-set)))
    @type-to-attributes))

;; (query-types-with-attributes)
