;; # Sensitivity Filtering Benchmark: Scaling Analysis
(ns notebooks.sensitivity-filtering-benchmark
  {:nextjournal.clerk/toc                   true
   :nextjournal.clerk/error-on-missing-vars :off}
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [nextjournal.clerk :as clerk]))

;; ## Why This Matters
;;
;; Gleanmo's dashboard calls `all-entities-for-user` across 6 entity types per page
;; load. As the dataset grew, these queries became the dominant bottleneck — with
;; production page loads reaching **4–10 seconds**.
;;
;; We then deployed `not-join`-based relationship sensitivity filtering (commit
;; `a78a8fa`), which pushed cross-entity filters into XTDB's Datalog engine. This
;; made the problem **dramatically worse** due to XTDB 1.x's nested-loop evaluation
;; of `not-join`.
;;
;; This notebook compares six filtering approaches with synthetic benchmarks and
;; grounds the analysis in real production observations.

;; ## Production Timeline
;;
;; ### Phase 0: Unbatched lookups (baseline, Jan 2025)
;;
;; Each `all-entities-for-user` call made **N x M x K individual queries** to check
;; related entity attributes — one per related ID, per relationship field, per entity.
;;
;; | Metric | Value |
;; |--------|-------|
;; | Queries per call | **31** (5 entities x 3 fields x ~2 IDs) |
;; | `all-entities-for-user` mean | 290–380ms |
;; | Dashboard overview (stats) | **2.62s** |
;; | Dashboard overview (recent) | **2.39s** |
;;
;; ### Phase 1: Query batching (commit `327c07e`)
;;
;; Batch-fetched all related entities in a single query, then filtered in memory.
;; This is the **post-filter** approach benchmarked below.
;;
;; | Metric | Before | After | Reduction |
;; |--------|--------|-------|-----------|
;; | Queries per call | 31 | **2** | 93% |
;;
;; ### Phase 2: not-join deployed (commit `a78a8fa`, Mar 2026)
;;
;; Pushed relationship sensitivity into XTDB using `not-join` clauses — the
;; "correct" Datalog approach. Production data had grown significantly:
;;
;; | Endpoint | Cold | Warm | Range |
;; |----------|------|------|-------|
;; | `get-app-overview-recent` | 7.88s | 4.40s | **4.4–10.7s** |
;; | `get-app-overview-stats` | 7.27s | 4.95s | **4.9–10.8s** |
;;
;; Hot path breakdown (warm):
;;
;; | Span | Calls | Mean | Total |
;; |------|-------|------|-------|
;; | `all-entities-for-user` | 6–9 | **549–907ms** | 4.3–4.9s |
;; | `dashboard-recent-entities` | 1–3 | 4.3–6.2s | 4.3–18.6s |
;;
;; The `not-join` clauses were the primary contributor to the per-call regression:
;; XTDB 1.x evaluates them as nested loops over the full candidate set.

;; ## The Problem
;;
;; Gleanmo uses **relationship sensitivity filtering**: when a parent entity (e.g., a habit)
;; is marked as sensitive or archived, all child entities (e.g., habit-logs) referencing
;; that parent must be excluded from query results.
;;
;; ```
;; habit-log --[:habit-log/habit-ids]--> habit
;;                                        |
;;                                        +-- :habit/sensitive true?
;;                                        +-- :habit/archived true?
;;
;; If habit is sensitive/archived -> exclude the habit-log
;; ```
;;
;; The challenge: this is a **cross-entity filter** — we need to check attributes on a
;; *related* entity to decide whether to include the *queried* entity.

;; ## Approach 1: Post-filter (batch-fetch)
;; Fetch all habit-logs, batch-fetch their parent habits, then filter in memory.
;;
;; ```clojure
;; (let [all-logs    (biff/q db {:find '(pull ?e [*])
;;                               :where '[[?e ::sm/type :habit-log] ...]})
;;       all-hab-ids (->> all-logs (mapcat :habit-log/habit-ids) set)
;;       habit-map   (into {} (map (fn [id] [id (xt/entity db id)])) all-hab-ids)
;;       excluded?   (fn [log]
;;                     (some (fn [hid]
;;                             (let [h (get habit-map hid)]
;;                               (or (:habit/sensitive h)
;;                                   (:habit/archived h))))
;;                           (:habit-log/habit-ids log)))]
;;   (remove excluded? all-logs))
;; ```
;;
;; **Pros:** Simple, correct, efficient batch fetching (2 queries total).
;;
;; **Cons:** Fetches all entities before filtering — wasted bandwidth at scale.
;;
;; **Production result:** Reduced queries from 31 to 2 (93% reduction). This was
;; deployed as "Phase 1" and was the fastest approach until `not-join` replaced it.

;; ## Approach 2: not-join (Datalog-native)
;; Push the filter into XTDB using `not-join` clauses.
;;
;; ```clojure
;; {:find '(pull ?e [*])
;;  :where '[[?e ::sm/type :habit-log]
;;           (not-join [?e]
;;                     [?e :habit-log/habit-ids ?rel]
;;                     [?rel :habit/sensitive true])
;;           (not-join [?e]
;;                     [?e :habit-log/habit-ids ?rel]
;;                     [?rel :habit/archived true])]}
;; ```
;;
;; **How XTDB 1.x evaluates `not-join`:** For each candidate entity `?e`, XTDB executes
;; the inner join pattern as a nested sub-query. With N habit-logs and M habits, this
;; becomes **O(N x M)** per `not-join` clause — essentially a nested loop.
;;
;; **Pros:** Declarative, pure Datalog, single query.
;;
;; **Cons:** O(N x M) evaluation in XTDB 1.x — caused the **4–10 second production
;; regression** documented above (`all-entities-for-user` mean jumped to 549–907ms).

;; ## Approach 3: Two-phase exclusion (recommended)
;; The key insight: the **exclusion set is tiny** (typically < 20 IDs) compared to the
;; total entity count. So we can:
;;
;; 1. **Phase 1:** Query for excluded parent IDs (one fast index scan)
;; 2. **Phase 2:** Fetch all logs, post-filter with O(1) set lookups
;;
;; ```clojure
;; (let [;; Phase 1: find excluded parent IDs (tiny set)
;;       excluded-ids (set (map first
;;                             (biff/q db {:find '[?h]
;;                                         :where '[(or [?h :habit/sensitive true]
;;                                                      [?h :habit/archived true])]})))
;;       ;; Phase 2: fetch all, filter with set membership
;;       all-logs (biff/q db {:find '(pull ?e [*])
;;                            :where '[[?e ::sm/type :habit-log]]})]
;;   (remove (fn [log]
;;             (some excluded-ids (:habit-log/habit-ids log)))
;;           all-logs))
;; ```
;;
;; **Pros:** Fastest — O(1) set lookups, no nested DB joins. Short-circuits when
;; no sensitive/archived entities exist (the common case).
;;
;; **Cons:** Two round-trips to DB (but both return tiny result sets).

;; ## Approach 4: Two-phase with `not` pushdown
;; If the excluded set is small, push explicit `(not [?e :field id])` per excluded
;; ID into the Datalog query.
;;
;; ```clojure
;; (let [excluded-ids (find-excluded-ids db user-id)
;;       not-clauses  (mapv (fn [id]
;;                            (list 'not ['?e :habit-log/habit-ids id]))
;;                          excluded-ids)
;;       query {:find '(pull ?e [*])
;;              :where (into base-where not-clauses)}]
;;   (biff/q db query user-id))
;; ```
;;
;; XTDB 1.x evaluates each `not` as its own scan — with K excluded IDs, that's
;; **K scans per candidate entity**. **Catastrophically slow** at scale.

;; ## Approach 5: Include-list with `contains?` predicate
;; Flip the logic: instead of excluding a small set, query for **allowed** parent IDs
;; and constrain the main query with a `contains?` predicate.
;;
;; ```clojure
;; (let [;; Phase 1: find allowed (non-excluded) parent IDs
;;       allowed-ids (set (map first
;;                            (biff/q db {:find '[?h]
;;                                        :where '[[?h ::sm/type :habit]
;;                                                  (not [?h :habit/sensitive true])
;;                                                  (not [?h :habit/archived true])]})))
;;       ;; Phase 2: query with contains? predicate
;;       query {:find '(pull ?e [*])
;;              :where '[[?e ::sm/type :habit-log]
;;                        [?e :habit-log/habit-ids ?hid]
;;                        [(contains? allowed-ids ?hid)]]
;;              :in '[user-id allowed-ids]}]
;;   (biff/q db query user-id allowed-ids))
;; ```
;;
;; The `contains?` predicate runs O(1) per binding. Better than `not-join`, but the
;; predicate evaluation overhead per candidate still makes it slightly slower than
;; Clojure-side set filtering in the two-phase approach.

;; ## Synthetic Benchmark Results
;; The benchmark test (`sensitivity_benchmark_test.clj`) runs all approaches
;; at three data scales with tufte profiling, plus a **baseline** (no filtering)
;; to show the cost floor.
;;
;; Regenerate with `biff test tech.jgood.gleanmo.test.db.sensitivity-benchmark-test`.

^{:nextjournal.clerk/visibility {:code :hide}
  :nextjournal.clerk/no-cache true}
(def benchmark-data
  (let [f (io/file "notebook_data/sensitivity_benchmark_results.edn")]
    (when (.exists f)
      (edn/read-string (slurp f)))))

;; ### Raw Data
^{:nextjournal.clerk/visibility {:code :hide}}
benchmark-data

;; ### Grouped Bar Chart: viable approaches
;; Compares the four viable approaches plus the no-filter baseline. Pushdown is
;; excluded because its large-scale values (180ms+) would compress the chart.
^{:nextjournal.clerk/visibility {:code :hide}}
(when benchmark-data
  (let [rows (mapcat
              (fn [{:keys [scale n-logs baseline-ms post-filter-ms not-join-ms
                           two-phase-ms include-predicate-ms]}]
                (let [s (str scale " (" n-logs " logs)")]
                  (cond-> []
                    baseline-ms          (conj {:scale s :approach "Baseline (no filter)" :time-ms (double baseline-ms)})
                    post-filter-ms       (conj {:scale s :approach "Post-filter"          :time-ms (double post-filter-ms)})
                    not-join-ms          (conj {:scale s :approach "not-join"             :time-ms (double not-join-ms)})
                    two-phase-ms         (conj {:scale s :approach "Two-phase"            :time-ms (double two-phase-ms)})
                    include-predicate-ms (conj {:scale s :approach "Include-predicate"    :time-ms (double include-predicate-ms)}))))
              benchmark-data)
        scale-order (mapv #(str (:scale %) " (" (:n-logs %) " logs)") benchmark-data)]
    (clerk/vl
     {:$schema  "https://vega.github.io/schema/vega-lite/v5.json"
      :width    600
      :height   350
      :title    "Sensitivity Filtering: Mean Query Time by Approach and Scale"
      :data     {:values rows}
      :mark     {:type "bar" :cornerRadiusTopLeft 3 :cornerRadiusTopRight 3}
      :encoding {:x       {:field "scale"
                           :type  "nominal"
                           :sort  scale-order
                           :axis  {:title "Scale" :labelAngle 0}}
                 :y       {:field "time-ms"
                           :type  "quantitative"
                           :axis  {:title "Mean time (ms)"}}
                 :color   {:field  "approach"
                           :type   "nominal"
                           :scale  {:scheme "tableau10"}
                           :legend {:title "Approach"}}
                 :xOffset {:field "approach" :type "nominal"}}})))

;; ### Scaling Analysis (all approaches, log scale)
;; Log scale shows the full range including pushdown's divergence.
^{:nextjournal.clerk/visibility {:code :hide}}
(when benchmark-data
  (let [rows (mapcat
              (fn [{:keys [scale n-logs baseline-ms post-filter-ms not-join-ms
                           two-phase-ms two-phase-pushdown-ms include-predicate-ms]}]
                (let [s (str scale " (" n-logs ")")]
                  (cond-> []
                    baseline-ms            (conj {:scale s :n-logs n-logs :approach "Baseline"          :time-ms (double baseline-ms)})
                    post-filter-ms         (conj {:scale s :n-logs n-logs :approach "Post-filter"       :time-ms (double post-filter-ms)})
                    not-join-ms            (conj {:scale s :n-logs n-logs :approach "not-join"          :time-ms (double not-join-ms)})
                    two-phase-ms           (conj {:scale s :n-logs n-logs :approach "Two-phase"         :time-ms (double two-phase-ms)})
                    two-phase-pushdown-ms  (conj {:scale s :n-logs n-logs :approach "Pushdown (not)"    :time-ms (double two-phase-pushdown-ms)})
                    include-predicate-ms   (conj {:scale s :n-logs n-logs :approach "Include-predicate" :time-ms (double include-predicate-ms)}))))
              benchmark-data)
        scale-order (mapv #(str (:scale %) " (" (:n-logs %) ")") benchmark-data)]
    (clerk/vl
     {:$schema  "https://vega.github.io/schema/vega-lite/v5.json"
      :width    600
      :height   350
      :title    "Scaling: Query Time vs Data Size (all approaches)"
      :data     {:values rows}
      :mark     {:type "line" :point {:size 100 :filled true} :strokeWidth 2.5}
      :encoding {:x     {:field "scale"
                         :type  "ordinal"
                         :sort  scale-order
                         :axis  {:title "Scale (number of habit-logs)"}}
                 :y     {:field "time-ms"
                         :type  "quantitative"
                         :axis  {:title "Mean time (ms)"}}
                 :color {:field  "approach"
                         :type   "nominal"
                         :scale  {:scheme "tableau10"}
                         :legend {:title "Approach"}}}})))

;; ## Why Pushing Into Datalog Fails in XTDB 1.x
;;
;; Every approach that pushes negation or predicate filtering into XTDB 1.x Datalog
;; performs worse than Clojure-side set operations:
;;
;; | Approach | What XTDB 1.x actually does | Complexity |
;; |----------|----------------------------|------------|
;; | `not-join` (2 clauses) | Nested loop: for each ?e, scan all habits | O(N x M) |
;; | `not` pushdown (K clauses) | K separate scans per ?e | O(N x K) |
;; | `contains?` predicate | Predicate eval per binding (better, but overhead) | O(N) + overhead |
;; | Two-phase (Clojure set) | Hash set lookup per log | O(N) + O(1) lookups |
;;
;; The `contains?` predicate (approach 5) is the closest competitor — it avoids
;; nested loops but still pays per-binding predicate evaluation overhead inside the
;; query engine. At large scale, Clojure-side `some` on a hash set is faster because
;; it runs after `pull [*]` deserialization, which is the true bottleneck.
;;
;; With a proper query planner (PostgreSQL, XTDB 2.x), the engine would build a hash
;; set internally and match two-phase performance. But in XTDB 1.x, the application
;; layer is smarter than the query engine for this pattern.
;;
;; **The fundamental trade-off: do the work where the cost model is known.**

;; ## The Baseline Floor
;;
;; The "baseline" approach (no filtering at all) reveals something important: at large
;; scale, **two-phase is actually faster than no filtering**. This happens because the
;; excluded logs are removed before the lazy sequence is fully realized — fewer entities
;; to deserialize from `pull [*]`.
;;
;; This means two-phase has reached the performance floor. The only way to go faster
;; would be to reduce the main query cost itself (e.g., `pull` fewer fields, or
;; pagination with `limit`/`offset`).

;; ## Conclusion
;;
;; ### All approaches ranked
;;
;; | Rank | Approach | Large-scale (1000 logs) | Production status |
;; |------|----------|------------------------|-------------------|
;; | 1 | **Two-phase** | ~17ms | Recommended |
;; | 2 | Include-predicate | ~21ms | Viable alternative |
;; | 3 | Post-filter | ~24ms | Previously deployed |
;; | 4 | Baseline (no filter) | ~24ms | Cost floor |
;; | 5 | not-join | ~42ms | Currently deployed — **regressed** |
;; | 6 | Pushdown (not) | ~180ms | Do not use |
;;
;; ### Why two-phase wins
;;
;; 1. **Phase 1** (find excluded IDs) is a single index scan returning a tiny set
;; 2. **Phase 2** uses Clojure-side O(1) set lookups — faster than any in-engine filtering
;; 3. Short-circuits entirely when no sensitive/archived entities exist (common case)
;; 4. Already at or below the no-filter baseline — the performance floor for `pull [*]`
;;
;; ### Architectural context
;;
;; All database access is routed through `db/queries.clj` and `db/mutations.clj`,
;; providing a clean interface boundary. When we eventually migrate to a new
;; database (XTDB 2.x, PostgreSQL, or otherwise), the two-phase pattern can be
;; replaced with native query-engine support for exclusion joins — but only the
;; query layer needs to change.
;;
;; For now, two-phase is the right trade-off: it works within XTDB 1.x's
;; limitations, is easy to reason about, and the benchmark confirms it's the
;; fastest option available.
;;
;; ### Next steps
;;
;; 1. Deploy two-phase approach to replace `not-join` in `relationship-sensitivity-clauses`
;; 2. Collect post-deploy production baselines to confirm improvement
;; 3. Track via performance monitoring dashboard at `/app/monitoring/performance`
