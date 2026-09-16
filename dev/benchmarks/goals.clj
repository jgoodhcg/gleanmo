(ns benchmarks.goals
  "Isolated synthetic goals benchmark. Never opens the application database."
  (:require
   [com.biffweb :as biff]
   [tech.jgood.gleanmo.db.queries :as queries]
   [tech.jgood.gleanmo.goals.dashboard :as dashboard]
   [tech.jgood.gleanmo.goals.registry :as registry]
   [tech.jgood.gleanmo.schema.meta :as sm]
   [tech.jgood.gleanmo.schema.utils :as schema-utils]
   [xtdb.api :as xt])
  (:import
   [java.time Instant LocalDate]
   [java.util UUID]))

(def ^:private now (Instant/parse "2026-09-16T12:00:00Z"))
(def ^:private settings {:show-sensitive false :show-archived false})
(def ^:private plan-only? (= "1" (System/getenv "GOALS_BENCH_PLANS")))
(def ^:private sources (vec (sort (keys registry/sources))))

(defn- id [& parts]
  (UUID/nameUUIDFromBytes (.getBytes (pr-str parts) "UTF-8")))

(defn- doc [owner entity key attrs]
  (merge {:xt/id (id owner entity key) :user/id owner ::sm/type entity
          ::sm/created-at (or (get attrs (keyword (name entity) "beginning"))
                              (get attrs (keyword (name entity) "timestamp"))
                              (.minusSeconds now (mod (hash key) 86400)))} attrs))

(defn- fixtures [owner n]
  (let [parent #(id owner % 0)
        parents (concat
                 (for [entity [:project :meditation :habit :exercise :boulder-problem]]
                   (doc owner entity 0 {(keyword (name entity) "label") "Synthetic"}))
                 (for [i (range 100)]
                   (doc owner :book i {:book/title "Synthetic" :book/total-pages 500})))
        records
        (mapcat
         (fn [source]
           (mapcat
            (fn [i]
              (let [at (.minusSeconds now (+ 3600 (* 86400 (mod i 3650))))
                    end (.plusSeconds at 1800)
                    relation (get-in registry/sources [source :relation])
                    attrs (merge {(keyword (name source) "beginning") at
                                  (keyword (name source) "end") end}
                                 (when relation
                                   {(:field relation) (if (= source :habit-log)
                                                        #{(parent (:entity relation))}
                                                        (parent (:entity relation)))}))]
                (if (= source :exercise-line)
                  [(doc owner :exercise-set i {:exercise-set/session-id (id owner :exercise-session i)
                                               :exercise-set/beginning at :exercise-set/end end})
                   (doc owner source i {:exercise-line/set-id (id owner :exercise-set i)
                                        :exercise-line/exercise-id (parent :exercise)
                                        :exercise-line/reps 5 :exercise-line/weight 80
                                        :exercise-line/weight-unit :kg
                                        :exercise-line/duration-seconds 45})]
                  [(doc owner source i
                        (merge attrs
                               (case source
                                 :habit-log {:habit-log/timestamp at}
                                 :reading-log {:reading-log/book-id (id owner :book (mod i 100))
                                               :reading-log/end-page (mod i 500)}
                                 :boulder-attempt {:boulder-attempt/session-id (id owner :boulder-session i)
                                                   :boulder-attempt/problem-id (parent :boulder-problem)
                                                   :boulder-attempt/attempts 2}
                                 {})))])))
            (range n)))
         sources)
        goals (map-indexed
               (fn [i {:keys [source measure aggregation relation]}]
                 (doc owner :goal i
                      (merge {:goal/label (str "Synthetic " i) :goal/source source
                              :goal/measure measure :goal/aggregation aggregation
                              :goal/timing :dated :goal/time-zone "UTC"
                              :goal/starts-on (LocalDate/parse "2026-01-01")
                              :goal/ends-on (LocalDate/parse "2026-12-31")}
                             (if (= aggregation :completion)
                               {:goal/book-ids #{(parent :book)}}
                               {:goal/target 100000})
                             (when relation {(:key relation) #{(parent (:entity relation))}}))))
               registry/measurements)]
    (vec (concat parents records goals))))

(defn- timed [f]
  (let [start (System/nanoTime) result (f)]
    {:ms (/ (- (System/nanoTime) start) 1e6) :result result}))

(defn- measure [label repetitions f check-result]
  (let [repetitions (if plan-only? 1 repetitions)
        _ (when plan-only? (binding [*out* *err*] (prn {:plan-case label})))
        first-run (timed f)]
    (check-result (:result first-run))
    (dotimes [_ (if plan-only? 0 2)] (check-result (f)))
    (let [samples (if plan-only? [(:ms first-run)]
                      (sort (repeatedly repetitions #(:ms (timed f)))))
          result {:case label :first-ms (:ms first-run)
                  :median-ms (nth samples (quot repetitions 2))
                  :p95-ms (nth samples (min (dec repetitions) (int (Math/floor (* 0.95 repetitions)))))
                  :samples repetitions}]
      (prn result)
      (flush)
      result)))

(defn- ensure! [condition data]
  (when-not condition (throw (ex-info "Benchmark correctness check failed" data))))

(defn- profile-queries [label f]
  (let [q-original biff/q calls (atom {})
        extract-original schema-utils/extract-relationship-fields
        extractions (atom 0)]
    (with-redefs [schema-utils/extract-relationship-fields
                  (fn [& args] (swap! extractions inc) (apply extract-original args))
                  biff/q (fn [db query & args]
                           (let [{:keys [ms result]} (timed #(apply q-original db query args))]
                             (swap! calls update query
                                    (fn [v] {:calls (inc (get v :calls 0))
                                             :ms (+ (get v :ms 0) ms)
                                             :runs (conj (get v :runs [])
                                                         {:ms ms :rows (count result)
                                                          :input-counts (mapv #(if (coll? %) (count %) 1) args)})}))
                             result))]
      (f))
    (prn {:case label :query-calls (reduce + (map :calls (vals @calls)))
          :relationship-schema-extractions @extractions
          :query-profile (sort-by (comp - :ms val) @calls)})))

(defn- await-statistics! [node document-count]
  ;; await-tx covers indexing, but XTDB updates planner statistics asynchronously.
  ;; Take the measured database snapshot only after those statistics exist.
  (loop [attempt 0]
    (let [stats (xt/attribute-stats node)]
      (if (>= (get stats :xt/id 0) document-count)
        stats
        (if (< attempt 600)
          (do (Thread/sleep 100) (recur (inc attempt)))
          (throw (ex-info "Timed out waiting for synthetic fixture statistics"
                          {:expected document-count :stats stats})))))))

(defn- run-scale [n repetitions]
  (let [owner (id :owner) foreign (id :foreign)
        docs (into (fixtures owner n) (fixtures foreign n))]
    (prn {:phase :seeding :records-per-source-per-user n :documents (count docs)})
    (flush)
    (with-open [node (biff/test-xtdb-node docs)]
      (let [stats (await-statistics! node (inc (count docs)))
            db (xt/db node)
            goals (queries/goals-for-user db owner)
            options {:now now :user-settings settings}
            expected (count (filter #(< (mod % 3650) 84) (range n)))]
        (prn {:phase :attribute-stats :stats stats})
        (measure :goal-list repetitions #(queries/goals-for-user db owner)
                 #(ensure! (= 13 (count %)) {:goal-count (count %)}))
        (doseq [source sources]
          (measure source repetitions
                   #(queries/goal-source-records
                     db owner {:source source :since (.minusSeconds now (* 84 86400))
                               :until now :overlap? true :user-settings settings})
                   #(ensure! (and (= expected (count %))
                                  (every? (set (map (fn [i] (id owner source i)) (range n)))
                                          (map :id %)))
                             {:source source :expected expected :actual (count %)})))
        (measure :book-history repetitions
                 #(queries/reading-logs-for-book db owner (id owner :book 0) settings)
                 #(ensure! (= (count (range 0 n 100)) (count %)) {:book-records (count %)}))
        (measure :dashboard-13-year repetitions #(dashboard/dashboard db owner options)
                 #(ensure! (= 13 (count (:active %))) {:dashboard-count (count (:active %))}))
        (profile-queries :dashboard-13-year #(dashboard/dashboard db owner options))
        ;; Preserve real source reads; vary only goal windows and goal count.
        (doseq [[label selected] [[:dashboard-13-year-preloaded goals]
                                  [:dashboard-39-year (vec (mapcat #(map (fn [g] (assoc g :xt/id (id (:xt/id g) %))) goals) (range 3)))]
                                  [:dashboard-13-decade (mapv #(assoc % :goal/starts-on (LocalDate/parse "2016-09-16")) goals)]]]
          (with-redefs [queries/goals-for-user (fn [& _] selected)]
            (measure label repetitions #(dashboard/dashboard db owner options)
                     #(ensure! (= (count selected) (count (:active %))) {:case label}))
            (when (= label :dashboard-13-decade)
              (profile-queries label #(dashboard/dashboard db owner options)))))))))

(defn -main
  "Run finite in-memory benchmarks; args are repetitions and per-source sizes."
  [& args]
  (try
    (let [repetitions (if (seq args) (parse-long (first args)) 9)
          sizes (if (next args) (map parse-long (rest args)) [1000 5000])]
      (doseq [n sizes] (run-scale n repetitions)))
    (finally (shutdown-agents))))
