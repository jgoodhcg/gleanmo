(ns tech.jgood.gleanmo.test.db.sensitivity-benchmark-test
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is testing]]
   [com.biffweb :as biff :refer [test-xtdb-node]]
   [taoensso.tufte :as tufte]
   [tech.jgood.gleanmo :as main]
   [tech.jgood.gleanmo.db.mutations :as mutations]
   [tech.jgood.gleanmo.schema.meta :as sm]
   [tick.core :as t]
   [xtdb.api :as xt])
  (:import
   [java.util UUID]))

(defn- get-context
  [node]
  {:biff.xtdb/node node
   :biff/db (xt/db node)
   :biff/malli-opts #'main/malli-opts})

(defn seed-habits!
  "Create n-total habits, with n-sensitive marked sensitive and n-archived marked archived.
   Returns {:all [ids...] :sensitive #{ids} :archived #{ids} :normal #{ids}}."
  [ctx user-id n-total n-sensitive n-archived]
  (let [ids (mapv
             (fn [i]
               (let [sensitive? (< i n-sensitive)
                     archived?  (and (not sensitive?)
                                     (< i (+ n-sensitive n-archived)))]
                 (mutations/create-entity!
                  ctx
                  {:entity-key :habit
                   :data (cond-> {:user/id user-id
                                  :habit/label (str "habit-" i)}
                           sensitive? (assoc :habit/sensitive true)
                           archived?  (assoc :habit/archived true))})))
             (range n-total))]
    {:all ids
     :sensitive (set (take n-sensitive ids))
     :archived  (set (->> ids (drop n-sensitive) (take n-archived)))
     :normal    (set (drop (+ n-sensitive n-archived) ids))}))

(defn seed-habit-logs!
  "Create logs-per-habit habit-logs for each habit, each referencing that habit."
  [ctx user-id habit-ids logs-per-habit]
  (let [now (t/now)]
    (doseq [habit-id habit-ids
            i (range logs-per-habit)]
      (mutations/create-entity!
       ctx
       {:entity-key :habit-log
        :data {:user/id user-id
               :habit-log/timestamp (t/<< now (t/new-duration (* i 60) :minutes))
               :habit-log/time-zone "UTC"
               :habit-log/habit-ids #{habit-id}}}))))

;; ---------------------------------------------------------------------------
;; Three approaches to sensitivity filtering
;; ---------------------------------------------------------------------------

(defn approach-post-filter
  "Approach 1: Fetch all habit-logs, batch-fetch parent habits, filter in memory."
  [db user-id]
  (let [all-logs (biff/q db
                         {:find '(pull ?e [*])
                          :where '[[?e :user/id user-id]
                                   [?e ::sm/type :habit-log]
                                   (not [?e ::sm/deleted-at])]
                          :in '[user-id]}
                         user-id)
        ;; Collect all referenced habit IDs
        all-habit-ids (->> all-logs
                           (mapcat :habit-log/habit-ids)
                           set)
        ;; Batch-fetch parent habits
        habit-map (when (seq all-habit-ids)
                    (into {}
                          (map (fn [id]
                                 [id (xt/entity db id)]))
                          all-habit-ids))
        ;; Filter: exclude logs whose any parent is sensitive or archived
        excluded? (fn [log]
                    (some (fn [hid]
                            (let [h (get habit-map hid)]
                              (or (:habit/sensitive h)
                                  (:habit/archived h))))
                          (:habit-log/habit-ids log)))]
    (remove excluded? all-logs)))

(defn approach-not-join
  "Approach 2: Use not-join in Datalog (currently deployed pattern)."
  [db user-id]
  (let [query {:find '(pull ?e [*])
               :where ['[?e :user/id user-id]
                       ['?e ::sm/type :habit-log]
                       '(not [?e ::sm/deleted-at])
                        ;; not-join for sensitive parents
                       '(not-join [?e]
                                  [?e :habit-log/habit-ids ?rel]
                                  [?rel :habit/sensitive true])
                        ;; not-join for archived parents
                       '(not-join [?e]
                                  [?e :habit-log/habit-ids ?rel]
                                  [?rel :habit/archived true])]
               :in '[user-id]}]
    (biff/q db query user-id)))

(defn approach-two-phase
  "Approach 3: Pre-query excluded parent IDs, then filter with :not-in style."
  [db user-id]
  (let [;; Phase 1: find excluded parent IDs (tiny set)
        sensitive-ids (set (map first
                                (biff/q db
                                        {:find '[?h]
                                         :where '[[?h :user/id user-id]
                                                  [?h ::sm/type :habit]
                                                  [?h :habit/sensitive true]]
                                         :in '[user-id]}
                                        user-id)))
        archived-ids  (set (map first
                                (biff/q db
                                        {:find '[?h]
                                         :where '[[?h :user/id user-id]
                                                  [?h ::sm/type :habit]
                                                  [?h :habit/archived true]]
                                         :in '[user-id]}
                                        user-id)))
        excluded-ids  (into sensitive-ids archived-ids)
        ;; Phase 2: fetch all logs, post-filter by excluded set (O(1) lookups)
        all-logs (biff/q db
                         {:find '(pull ?e [*])
                          :where '[[?e :user/id user-id]
                                   [?e ::sm/type :habit-log]
                                   (not [?e ::sm/deleted-at])]
                          :in '[user-id]}
                         user-id)]
    (if (empty? excluded-ids)
      all-logs
      (remove (fn [log]
                (some excluded-ids (:habit-log/habit-ids log)))
              all-logs))))

(defn approach-two-phase-pushdown
  "Approach 4: Pre-query excluded IDs, push them into Datalog as explicit (not) clauses.
   Each (not [?e :field excluded-id]) is O(1) index lookup — no nested loop."
  [db user-id]
  (let [;; Phase 1: find excluded parent IDs in a single query
        excluded-ids (set (map first
                               (biff/q db
                                       {:find '[?h]
                                        :where '[[?h :user/id user-id]
                                                 [?h ::sm/type :habit]
                                                 (or [?h :habit/sensitive true]
                                                     [?h :habit/archived true])]
                                        :in '[user-id]}
                                       user-id)))
        ;; Phase 2: build query with explicit (not) per excluded ID
        base-where ['[?e :user/id user-id]
                    ['?e ::sm/type :habit-log]
                    '(not [?e ::sm/deleted-at])]
        not-clauses (mapv (fn [id] (list 'not ['?e :habit-log/habit-ids id]))
                          excluded-ids)
        query {:find '(pull ?e [*])
               :where (into base-where not-clauses)
               :in '[user-id]}]
    (biff/q db query user-id)))

(defn approach-include-predicate
  "Approach 5: Pre-query allowed parent IDs, push into Datalog via contains? predicate.
   Flips the logic: instead of excluding a small set, constrain to the allowed set."
  [db user-id]
  (let [;; Phase 1: find allowed parent IDs (the complement of excluded)
        allowed-ids (set (map first
                              (biff/q db
                                      {:find '[?h]
                                       :where '[[?h :user/id user-id]
                                                [?h ::sm/type :habit]
                                                (not [?h :habit/sensitive true])
                                                (not [?h :habit/archived true])]
                                       :in '[user-id]}
                                      user-id)))]
    (if (empty? allowed-ids)
      []
      ;; Phase 2: query with contains? predicate on the allowed set
      (biff/q db
              {:find '(pull ?e [*])
               :where '[[?e :user/id user-id]
                        [?e ::sm/type :habit-log]
                        (not [?e ::sm/deleted-at])
                        [?e :habit-log/habit-ids ?hid]
                        [(contains? allowed-ids ?hid)]]
               :in '[user-id allowed-ids]}
              user-id
              allowed-ids))))

(defn approach-baseline
  "Approach 0: No filtering at all — the theoretical minimum for the main query.
   Measures the cost floor: pull [*] over all habit-logs with no sensitivity logic."
  [db user-id]
  (biff/q db
          {:find '(pull ?e [*])
           :where '[[?e :user/id user-id]
                    [?e ::sm/type :habit-log]
                    (not [?e ::sm/deleted-at])]
           :in '[user-id]}
          user-id))

;; ---------------------------------------------------------------------------
;; Benchmark runner
;; ---------------------------------------------------------------------------

(def ^:private scale-configs
  [{:scale "small"  :n-habits 5   :logs-per-habit 1  :n-sensitive 1  :n-archived 1}
   {:scale "medium" :n-habits 20  :logs-per-habit 5  :n-sensitive 3  :n-archived 3}
   {:scale "large"  :n-habits 100 :logs-per-habit 10 :n-sensitive 10 :n-archived 10}])

(defn- mean-ms
  "Extract mean time in milliseconds from tufte pstats for the given profiling id."
  [pstats id]
  (let [stats (get-in (deref pstats) [:stats id])]
    (when stats
      (/ (:mean stats) 1e6))))

(defn- run-single-scale
  "Run all three approaches at one scale level and return timing data."
  [scale-config]
  (let [{:keys [scale n-habits logs-per-habit n-sensitive n-archived]} scale-config
        n-logs (* n-habits logs-per-habit)
        n-excluded (+ n-sensitive n-archived)
        iterations 5]
    (with-open [node (test-xtdb-node [])]
      (let [ctx (get-context node)
            user-id (UUID/randomUUID)
            habits (seed-habits! ctx user-id n-habits n-sensitive n-archived)]
        (seed-habit-logs! ctx user-id (:all habits) logs-per-habit)
        (let [db (xt/db node)
              ;; Warm up JIT
              _ (dotimes [_ 2]
                  (doall (approach-baseline db user-id))
                  (doall (approach-post-filter db user-id))
                  (doall (approach-not-join db user-id))
                  (doall (approach-two-phase db user-id))
                  (doall (approach-two-phase-pushdown db user-id))
                  (doall (approach-include-predicate db user-id)))
              ;; Benchmark each approach
              [_ bl-pstats]
              (tufte/profiled {}
                              (dotimes [_ iterations]
                                (tufte/p :baseline (doall (approach-baseline db user-id)))))
              [_ pf-pstats]
              (tufte/profiled {}
                              (dotimes [_ iterations]
                                (tufte/p :post-filter (doall (approach-post-filter db user-id)))))
              [_ nj-pstats]
              (tufte/profiled {}
                              (dotimes [_ iterations]
                                (tufte/p :not-join (doall (approach-not-join db user-id)))))
              [_ tp-pstats]
              (tufte/profiled {}
                              (dotimes [_ iterations]
                                (tufte/p :two-phase (doall (approach-two-phase db user-id)))))
              [_ tpp-pstats]
              (tufte/profiled {}
                              (dotimes [_ iterations]
                                (tufte/p :two-phase-pushdown (doall (approach-two-phase-pushdown db user-id)))))
              [_ ip-pstats]
              (tufte/profiled {}
                              (dotimes [_ iterations]
                                (tufte/p :include-predicate (doall (approach-include-predicate db user-id)))))]
          {:scale                   scale
           :n-logs                  n-logs
           :n-excluded              n-excluded
           :baseline-ms             (mean-ms bl-pstats :baseline)
           :post-filter-ms          (mean-ms pf-pstats :post-filter)
           :not-join-ms             (mean-ms nj-pstats :not-join)
           :two-phase-ms            (mean-ms tp-pstats :two-phase)
           :two-phase-pushdown-ms   (mean-ms tpp-pstats :two-phase-pushdown)
           :include-predicate-ms    (mean-ms ip-pstats :include-predicate)})))))

(defn run-scaling-benchmark
  "Run the sensitivity filtering benchmark at all scale levels.
   Returns a vector of EDN maps with timing data."
  []
  (mapv run-single-scale scale-configs))

;; ---------------------------------------------------------------------------
;; Tests
;; ---------------------------------------------------------------------------

(deftest sensitivity-filtering-correctness-test
  (testing "All approaches return the same correct results"
    (with-open [node (test-xtdb-node [])]
      (let [ctx (get-context node)
            user-id (UUID/randomUUID)
            habits (seed-habits! ctx user-id 10 2 2)]
        (seed-habit-logs! ctx user-id (:all habits) 3)
        (let [db (xt/db node)
              post-filter-results        (set (map :xt/id (approach-post-filter db user-id)))
              not-join-results           (set (map :xt/id (approach-not-join db user-id)))
              two-phase-results          (set (map :xt/id (approach-two-phase db user-id)))
              two-phase-pushdown-results (set (map :xt/id (approach-two-phase-pushdown db user-id)))
              include-predicate-results  (set (map :xt/id (approach-include-predicate db user-id)))
              baseline-results           (set (map :xt/id (approach-baseline db user-id)))
              ;; Expected: logs for 6 normal habits × 3 logs = 18
              expected-count (* 6 3)
              ;; Baseline returns ALL logs (no filtering) = 10 habits × 3 logs = 30
              baseline-count (* 10 3)]
          (is (= expected-count (count post-filter-results))
              "Post-filter should return only logs for non-sensitive, non-archived habits")
          (is (= baseline-count (count baseline-results))
              "Baseline should return all logs without filtering")
          (is (= post-filter-results not-join-results)
              "not-join should match post-filter results")
          (is (= post-filter-results two-phase-results)
              "two-phase should match post-filter results")
          (is (= post-filter-results two-phase-pushdown-results)
              "two-phase-pushdown should match post-filter results")
          (is (= post-filter-results include-predicate-results)
              "include-predicate should match post-filter results"))))))

(deftest sensitivity-filtering-benchmark
  (testing "Scaling benchmark writes results EDN"
    (let [results (run-scaling-benchmark)]
      (is (= 3 (count results)) "Should have results for 3 scale levels")
      (doseq [r results]
        (is (pos? (:baseline-ms r))             (str "baseline should be > 0 at " (:scale r)))
        (is (pos? (:post-filter-ms r))        (str "post-filter should be > 0 at " (:scale r)))
        (is (pos? (:not-join-ms r))           (str "not-join should be > 0 at " (:scale r)))
        (is (pos? (:two-phase-ms r))          (str "two-phase should be > 0 at " (:scale r)))
        (is (pos? (:two-phase-pushdown-ms r)) (str "two-phase-pushdown should be > 0 at " (:scale r)))
        (is (pos? (:include-predicate-ms r))  (str "include-predicate should be > 0 at " (:scale r))))
      ;; Write results to EDN for the Clerk notebook
      (let [out-file (io/file "notebook_data/sensitivity_benchmark_results.edn")]
        (io/make-parents out-file)
        (spit out-file (pr-str results))
        (is (.exists out-file) "EDN file should be written")
        (is (= results (edn/read-string (slurp out-file)))
            "Written EDN should round-trip")))))
