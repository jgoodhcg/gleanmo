---
title: "Dashboard Home Page Performance"
status: active
description: "Home page dashboard performance improvements"
tags: []
priority: medium
created: 2026-02-02
updated: 2026-02-02
---

# Dashboard Home Page Performance

## Work Unit Summary
- Problem / intent: Home page dashboard takes 2-3 seconds to load due to redundant queries across HTMX fragments
- Constraints: Maintain HTMX lazy-load UX pattern; avoid breaking existing functionality
- Proposed approach: Add count-only queries for stats, reduce redundant `all-entities-for-user` calls
- Open questions: Should we combine endpoints or add request-level caching?

## Original Baseline (2025-01-30)

### Endpoint Timing (Production)

| Endpoint | Time | Notes |
|----------|------|-------|
| `get-app` | 8.19ms | Fast - shell only |
| `get-app-monitoring-performance` | 12.76ms | Fast |
| `get-app-overview-events` | 94.09ms | Acceptable |
| `get-app-overview-recent` | **2.39s** | SLOW - primary bottleneck |
| `get-app-overview-stats` | **2.62s** | SLOW - primary bottleneck |
| `post-app-monitoring-performance` | 265.28ms | Moderate |

### Root Cause Analysis

Both slow endpoints call `dashboard-recent-entities` which iterates over 6 entity types:

```
get-app-overview-stats:
  └── dashboard-stats (line 216)
      └── dashboard-recent-entities (per-type-limit: 200)
          └── all-entities-for-user × 6 entity types
              Mean: 290ms each, Total: 2.61s

get-app-overview-recent:
  └── recent-activity (line 274)
      └── dashboard-recent-entities (per-type-limit: 20)
          └── all-entities-for-user × 6 entity types
              Mean: 380ms each, Total: 2.28s
```

**Key insight:** Stats endpoint fetches 200 entities per type (1200 total) just to count them.

## Pre-Deploy Baseline (2026-03-07)

Production snapshots taken before query refactoring deploy. Data has grown significantly since original baseline.

Git SHA: `a647002` | Two snapshots collected.

### Snapshot 1 (cold)

| Endpoint | Mean | Calls | Min | Max | Notes |
|----------|------|-------|-----|-----|-------|
| `get-app` | 4.71ms | 1 | - | - | Fast - shell only |
| `get-app-overview-events` | 22.53ms | 1 | - | - | Fast |
| `get-app-overview-recent` | **7.88s** | 3 | 4.46s | 10.70s | SLOW |
| `get-app-overview-stats` | **7.27s** | 3 | 5.40s | 10.77s | SLOW |
| `get-app-monitoring-performance` | 703.94ms | 1 | - | - | |
| `post-app-monitoring-performance` | 6.83s | 1 | - | - | |

### Snapshot 2 (warm)

| Endpoint | Mean | Calls | Min | Max | Notes |
|----------|------|-------|-----|-----|-------|
| `get-app` | 2.96ms | 1 | - | - | Fast |
| `get-app-overview-events` | 71.96ms | 1 | - | - | Fast |
| `get-app-overview-recent` | **4.40s** | 1 | - | - | SLOW |
| `get-app-overview-stats` | **4.95s** | 1 | - | - | SLOW |
| `get-app-monitoring-performance` | 93.33ms | 1 | - | - | |
| `post-app-monitoring-performance` | 311.15ms | 1 | - | - | |

### Summary: Pre-Deploy Range

| Endpoint | Cold | Warm | Range |
|----------|------|------|-------|
| `get-app-overview-recent` | 7.88s | 4.40s | **4.4–10.7s** |
| `get-app-overview-stats` | 7.27s | 4.95s | **4.9–10.8s** |

### Hot Path Breakdown (stats — snapshot 1, 3 calls)

| Span | Calls | Mean | Total |
|------|-------|------|-------|
| `all-entities-for-user` | 24 | 907.82ms | 21.79s |
| `batch-fetch-entities` | 56 | 5.59ms | 313.04ms |
| `dashboard-recent-entities` | 3 | 6.20s | 18.61s |
| `all-for-user-query` | 6 | 504.22ms | 3.03s |

### Hot Path Breakdown (stats — snapshot 2, 1 call)

| Span | Calls | Mean | Total |
|------|-------|------|-------|
| `all-entities-for-user` | 9 | 549.26ms | 4.94s |
| `batch-fetch-entities` | 19 | 4.62ms | 87.83ms |
| `dashboard-recent-entities` | 1 | 4.39s | 4.39s |
| `all-for-user-query` | 2 | 272.80ms | 545.60ms |

### Hot Path Breakdown (recent — snapshot 1, 3 calls)

| Span | Calls | Mean | Total |
|------|-------|------|-------|
| `all-entities-for-user` | 15 | 1.50s | 22.57s |
| `batch-fetch-entities` | 15 | 12.49ms | 187.31ms |
| `dashboard-recent-entities` | 3 | 7.52s | 22.57s |
| `get-entity-by-id` | 101 | 9.82ms | 991.51ms |

### Hot Path Breakdown (recent — snapshot 2, 1 call)

| Span | Calls | Mean | Total |
|------|-------|------|-------|
| `all-entities-for-user` | 6 | 718.67ms | 4.31s |
| `batch-fetch-entities` | 6 | 968.21µs | 5.81ms |
| `dashboard-recent-entities` | 1 | 4.31s | 4.31s |
| `get-entity-by-id` | 34 | 2.29ms | 77.81ms |

## Pre-Deploy Baseline: Two-Phase Exclusion (2026-03-08)

Snapshot taken before deploying commit `718b8f5` (replace not-join with two-phase exclusion filtering).
Still running the old not-join code in production at this point.

Git SHA: `1d1004b` | Snapshot at 2026-03-08T20:24:38Z

| Endpoint | Mean | Calls | Min | Max | Notes |
|----------|------|-------|-----|-----|-------|
| `get-app` | 12.06ms | 1 | - | - | Fast - shell only |
| `get-app-monitoring-performance` | 15.65ms | 1 | - | - | Fast |
| `get-app-overview-events` | 15.49ms | 1 | - | - | Fast |
| `get-app-overview-recent` | **5.94s** | 1 | - | - | SLOW |
| `get-app-overview-stats` | **6.10s** | 1 | - | - | SLOW |
| `post-app-monitoring-performance` | 721.56ms | 1 | - | - | |

### Hot Path Breakdown

| Span | Calls | Mean | Total |
|------|-------|------|-------|
| `all-entities-for-user` (recent) | 6 | 974.01ms | 5.84s |
| `all-entities-for-user` (stats) | 8 | 753.43ms | 6.03s |
| `dashboard-recent-entities` (recent) | 1 | 5.84s | 5.84s |
| `dashboard-recent-entities` (stats) | 1 | 5.78s | 5.78s |
| `all-for-user-query` (stats) | 2 | 121.81ms | 243.62ms |

**Key metric to watch post-deploy:** `all-entities-for-user` mean should drop significantly
as not-join O(N×M) is replaced by O(1) set lookups in the two-phase approach.

## Post-Deploy Baseline: Two-Phase Exclusion (2026-03-08)

Git SHA: `718b8f5` | 4 snapshots collected. Cold start was slow (15s) but warmed up quickly.

### Warm Snapshots (snapshots 3-4, stable)

| Endpoint | Snap 3 | Snap 4 | Mean | Notes |
|----------|--------|--------|------|-------|
| `get-app` | 7.58ms | 5.78ms | 6.68ms | Fast |
| `get-app-overview-events` | 76.15ms | 96.67ms | 86.41ms | Fast |
| `get-app-overview-recent` | **4.78s** | **4.62s** | **4.70s** | Improved |
| `get-app-overview-stats` | **4.98s** | **4.81s** | **4.90s** | Improved |
| `post-app-monitoring-performance` | 322.41ms | 322.13ms | 322.27ms | Stable |

### Hot Path Breakdown (warm average)

| Span | Calls | Snap 3 Mean | Snap 4 Mean | Avg |
|------|-------|-------------|-------------|-----|
| `all-entities-for-user` (recent) | 6 | 779ms | 737ms | **758ms** |
| `all-entities-for-user` (stats) | 8 | 614ms | 592ms | **603ms** |
| `dashboard-recent-entities` | 1 | 4.68s | 4.42s | 4.55s |
| Max single call | - | 2.41s | 2.50s | **2.46s** |

### Comparison: Pre vs Post Two-Phase Deploy (warm)

| Metric | Pre-deploy | Post-deploy | Change |
|--------|-----------|-------------|--------|
| `get-app-overview-recent` | 5.94s | 4.70s | **-21%** |
| `get-app-overview-stats` | 6.10s | 4.90s | **-20%** |
| `all-entities-for-user` mean (recent) | 974ms | 758ms | **-22%** |
| `all-entities-for-user` mean (stats) | 753ms | 603ms | **-20%** |
| Max single call | 3.65s | 2.46s | **-33%** |

**Conclusion:** Two-phase exclusion delivers ~20% improvement on warm queries and ~33% reduction in worst-case single call. Cold start is slower due to pulling all entities without limit, but stabilizes quickly.

## Index-Only Scan Refactor (2026-07-02)

`all-entities-for-user` rewritten in place (signature unchanged, callers unaware):

1. **Phase 1:** index-only scan of `[?e ?sort-value]` tuples — no document pulls.
   Key finding: XTDB 1.x `pull` fetches the *entire document* from the doc store
   regardless of pull spec, so the only way to avoid doc materialization is plain
   datalog variables. Sorting/pagination happens on tuples in Clojure.
2. **Phase 2:** one batch pull (`:in [[?e ...]]`) of full docs for just the page.
   With relationship exclusions, docs are pulled in chunks (~2x limit) and filtered
   until the page fills. **The limit is never dropped anymore** — previously any
   non-empty exclusion map caused a full-history pull of the type.

### Pre-Deploy Prod Baseline (2026-07-02, warm, SHA d7f41a4)

| Endpoint | Time | all-entities-for-user |
|----------|------|-----------------------|
| `get-app-overview-recent` | **5.68s** | 6 calls, mean 932ms, max 2.64s (= 5.59s of the total) |
| `get-app-overview-stats` | **6.03s** | 9 calls, mean 667ms, max 2.70s (= 6.01s of the total) |
| `get-app-overview-events` | 27.91ms | fast |
| `get-app` | 4.51ms | fast |

~99% of both slow endpoints is inside `all-entities-for-user` — the exact path
this refactor changes. Compare these numbers post-deploy.

### Measured (local, in-memory node, 5,000 reading-logs w/ ~600B notes, exclusions active)

| Case | Old | New | Change |
|------|-----|-----|--------|
| limit 20 | 96ms | 66ms | -31%, identical results |
| limit 200 (stats shape) | 91ms | 73ms | -20%, identical results |

Docs pulled per call: old = all 5,000; new = ~40 (limit 20) / ~400 (limit 200).
In-memory nodes minimize doc-fetch cost, so prod (RocksDB doc store, larger docs)
should improve substantially more — doc materialization now scales with page size,
not history size. Remaining floor is the per-row datalog join (~10-20µs/row/clause),
which still scales with type history; if that becomes the bottleneck, windowing or
a materialized activity feed is the next step.

Full test suite green before and after (80 tests, 630 assertions). Verify in prod
via `/app/monitoring/performance`: `all-entities-for-user` mean should drop from
~600-900ms; new span `fetch-entities-by-ids` shows batch-pull cost.

### Post-Deploy Prod Results (2026-07-02, SHA f0a2d59)

First load (cold caches): recent 24.55s / stats 23.50s. Second load (warm):

| Metric | Pre-deploy (warm) | Post-deploy (warm) | Change |
|--------|-------------------|--------------------|--------|
| `all-entities-for-user` mean | 932ms / 667ms | 373ms / 414ms | **-55% / -38%** |
| Worst single call | 2.64s / 2.70s | 2.07s / 2.30s | -22% / -15% |
| `fetch-entities-by-ids` mean | — | 22-48ms | batch pulls cheap, as predicted |
| `get-app-overview-recent` | 5.68s | 6.93s | — (not comparable) |
| `get-app-overview-stats` | 6.03s | 6.83s | — (not comparable) |

Endpoint totals aren't comparable: the pre-deploy build made 6 `all-entities-for-user`
calls per endpoint; this build makes 17 (13-type timeline + 3 timer queries).
Doc materialization is no longer the bottleneck — remaining cost is the per-type
index scan (~370ms mean, one type at ~2s despite only ~1k rows — worth
investigating; likely bm-log doc/index bloat or exclusion-map subqueries) run
17x sequentially, twice per page load.

**Next:** (1) parallelize per-type scans, (2) merge stats into the recent
fragment so the cascade runs once, (3) profile the ~2s type.

## Parallel Scans + Endpoint Merge (2026-07-02)

Implemented next steps 1 and 2:

- `dashboard-recent-entities` runs per-type reads via `pmap` (independent
  queries on one immutable db snapshot) — wall time becomes the max single
  type instead of the sum. Verified identical results vs sequential.
- `active-timer-summaries` also parallelized (3 timer-type queries).
- The recent fragment now does **one** bounded fetch (`fetch-overview-items`,
  per-type-limit 100) shared by the timeline, the stats strip, and timers;
  the shell no longer loads `/app/overview/stats` (route kept, standalone).
  Stats week/today counts are now sampled at 100/type instead of 200/type.

Local (small dev DB): parallel cascade 31ms vs 49ms sequential; merged fragment
58ms vs ~110ms for the two old fragments. Prod expectation: one cascade per page
load instead of two, bounded by the worst single type scan (~2s until step 3
addresses it), so home page ~7s → roughly 2-3s. bm-log is only ~1k rows, so the
2s outlier is *not* row count — suspect doc/index bloat (see bm-log-bloating.md)
or exclusion-map subqueries; profile next.

### Post-Deploy Prod Results (2026-07-02, SHA d649c6d)

4 loads of `get-app-overview-recent` (now the only home page data request):
mean 4.75s, **min 2.59s (warm)**, max 9.73s (cold). `dashboard-recent-entities`
min 1.87s — wall time is now bounded by the worst single type, as designed.
Per-call `all-entities-for-user` means rose (~1s) because 16 scans now contend
in parallel; totals exceed wall time, which is the number that matters.

vs. the 2026-07-02 pre-refactor baseline (~11.7s of endpoint work per page
load across recent+stats): **~4.5x improvement warm**.

**Remaining bottleneck:** one ~2s type per cascade. Per-type tufte spans
(`scan/<type>`, `exclusions/<type>`) added to `all-entities-for-user` so the
next snapshot identifies the type and phase.

Note on topology: prod is XTDB `:jdbc` on Neon Postgres — the document store is
remote, so doc pulls are network fetches (visible in `fetch-entities-by-ids`
mean 75ms / max 686ms). This is why eliminating full-history pulls paid off so
heavily. Cold start (~10s) = empty doc cache + possible Neon compute resume.
Parallel scans share the Hikari pool for doc fetches — check
`:biff.xtdb.jdbc-pool/maximumPoolSize` if per-type spans show serialization.

## Per-Type Span Analysis (2026-07-02, SHA 8416788)

4 loads, warm min 2.92s. Per-type spans (scan/exclusions shown as duplicate
rows in the dashboard) reveal:

| Type | scan mean (min) | exclusions mean |
|------|-----------------|-----------------|
| habit-log | **4.20s (2.21s)** | 224ms |
| medication-log | **3.80s (2.04s)** | 826µs |
| bm-log | **2.62s (1.23s)** | 1.5ms |
| task | 1.24s (515ms) | 68ms |
| reading-log / meditation-log / project-log | ~10-40ms | 0.9-1.5s |
| exercise-*, symptom-*, calendar-event | µs-40ms | — |

1. **Index scans of the big log types are now the wall** — seconds each, even
   though e.g. bm-log is only ~1k rows. Everything is inflated by contention:
   16 parallel scans + 3 unlimited timer fetches + ~143 get-entity-by-id per
   load, all at once. Uncontended scan cost is unknown.
2. **`fetch-active-timers` full-fetched all docs of 3 timer types per load**
   (all-for-user-query, no limit → full scan + full Neon doc pull; 12 calls,
   572ms mean per snapshot). Fixed: `active-timers-for-user` pushes the
   "beginning set, no end" predicate into XTDB.
3. For reading/meditation/project-log the *exclusions* subquery is the slow
   row (~1s) while scans are ~10ms — exclusion parent scans (location/project)
   are tiny tables, so this too smells like contention, not real work.
4. `get-entity-by-id` 143/load (~285ms) — relationship-label lookups;
   batchable later if it matters.

**Next after timer fix deploys:** re-snapshot. If big-type scans stay
multi-second uncontended, that's the XTDB 1.x per-row scan floor on this
hardware → options: per-user dashboard cache (TTL ~60s), materialized
recent-activity doc maintained on write, or accept ~1-2s. Also worth checking
prod vCPU count and `maximumPoolSize`.

## Post-Timer-Fix Results + Scan Diagnostics (2026-07-04, SHA c7f1c7f)

3 warm loads: handler mean **3.07s** (was 5.84s). `dashboard-recent-entities`
2.44s ≈ bounded by habit-log scan (2.37s). Timer fetches now 369ms each
(`active-timers-for-user`) but ran *after* the cascade, serially.

Scans remain suspiciously slow per row: bm-log ~1k rows at 1.56s ≈ 1.5ms/row —
~60x the local reference (~24µs/row). Either prod row counts are much larger
than assumed, or scans are pathological on that box (CPU, JVM heap, index disk).

Changes in this iteration:
- `recent-activity-section` overlaps the items fetch (future) with the timer
  fetch instead of running them serially (~0.4-1s off wall time).
- New super-user page `/app/monitoring/db`: sequential, uncontended per-type
  index scan timings + row counts for the current user (`scan-diagnostics`).
  Visit it on an idle instance — µs/row far above ~50 means the scan itself is
  pathological; large row counts mean the data is just big. This decides
  between per-user caching / materialized feed / hardware-level fixes.

## Verdict: Contention, Not Scan Cost (2026-07-04, SHA d0877bc)

The decisive measurement: `/app/monitoring/db` ran **all 13 type scans
sequentially in 600-870ms total** — uncontended scans are healthy. Meanwhile
the same scans inside the page load ballooned (habit-log 3.9s, medication-log
3.75s, `active-timers-for-user` 369ms → 2.3s) and the handler *worsened*
(3.07s → 4.68s mean) after overlapping items+timers added more parallelism.

Conclusion: the prod box cannot run ~19 concurrent scans; `pmap` sizes its
parallelism off the JVM's core count, which containers over-report, so wide
parallelism just queues and inflates every span.

Fix: `bounded-pmap` (fixed thread pool, conveys dynamic bindings for tufte).
Dashboard cascade bounded to 3; timer fetches made sequential (they overlap
the cascade via a future, so total in-flight ≈ 4). Expected warm load:
scans ~0.9s serialized + overlapped network doc fetches → **~1.5-2s**.

If that lands and further improvement is wanted, remaining levers: per-user
cache / materialized feed (sub-500ms), batching the ~145 relationship-label
`get-entity-by-id` calls, and checking prod vCPU / Hikari pool sizing.

## Warm Results + Not-Clause Discovery (2026-07-04, SHA af1c85d)

Warm (4 loads after warmup): handler mean **2.34s** (1.93-2.52s), was 4.68s.
Bounded concurrency worked. But a contradiction surfaced: `/app/monitoring/db`
runs all 13 scans in **560ms total**, while inside the cascade habit-log alone
takes 2.02s and medication-log 1.62s — same index, same rows.

Root cause: the diagnostic scan omits the sensitivity/archived/deleted-at
`(not ...)` clauses; the cascade scans included them, and **XTDB 1.x evaluates
each not-clause as a per-row subquery**, multiplying scan cost on big types.

Fix: `build-id-scan-query` now emits only the minimal 3-clause join
(user-id, type, sort-key). Deleted-at, direct sensitive/archived flags, and
relationship exclusions are all post-filtered on pulled docs in the existing
chunked phase-2 loop — all sparse, so the page usually fills from one chunk.
The diagnostics page now measures exactly the scan the dashboard runs.

Expected: big-type scans drop toward diagnostic levels (~100-300ms), cascade
~1s, handler ~1.2s warm. Remaining after that: relationship-label batching
(~145 get-entity-by-id/load ≈ 0.2-0.9s), then cache/materialized feed if
sub-second is desired.

## Post-Deploy Baseline (2026-03-07)

_To be filled after deploying query refactoring (commits 7d3ee01, a78a8fa)._

| Endpoint | Mean | Notes |
|----------|------|-------|
| `get-app` | | |
| `get-app-overview-events` | | |
| `get-app-overview-recent` | | |
| `get-app-overview-stats` | | |

### Recent Improvements

#### Query Batching (Commit 327c07e) - Complete
- Reduced queries per `all-entities-for-user` call from 31 to 2 (93% reduction)
- This improved each individual call but didn't address the redundancy across endpoints

## Optimization Options

### Option 1: Count-Only Queries for Stats (High Impact)
**Estimated improvement:** Stats endpoint from 2.6s to ~100ms

The `dashboard-stats` function only needs:
- Count of entries today
- Count of entries this week
- Count of active timers (already separate)
- Count of "now" tasks (already has `count-tasks-by-state`)

Instead of fetching 1200 entities, use `COUNT` queries:

```clojure
;; Proposed: count-entities-since
(defn count-entities-since [db user-id entity-type since-instant]
  (let [order-key (get order-keys entity-type ::sm/created-at)]
    (count (q db
              {:find '[?e]
               :where [['?e ::sm/type entity-type]
                       ['?e ::sm/user-id user-id]
                       ['?e order-key '?ts]
                       [(list '> '?ts since-instant)]]}))))
```

### Option 2: Combine Stats + Recent Endpoints (Medium Impact)
**Estimated improvement:** Eliminate one 2.3s query

Since both endpoints query similar data:
1. Stats endpoint returns the entities it already fetched
2. Recent endpoint uses cached data if stats already loaded
3. Or: Single endpoint returns both stats and recent items

### Option 3: Request-Level Caching (Medium Impact)
**Estimated improvement:** 50% reduction for concurrent loads

Add short TTL (2-5 seconds) cache per user for `dashboard-recent-entities` results:
- First endpoint to call populates cache
- Second endpoint hits cache instead of DB

### Option 4: Reduce Stats Sample Size (Quick Win)
**Estimated improvement:** 50% reduction

Currently fetches 200 items per type. For "today" and "this week" counts:
- 50 items per type would likely be sufficient
- Change `per-type-limit 200` to `per-type-limit 50`

## Performance Monitoring Dashboard

### Current State
- Tufte profiling captures per-request timing
- Snapshots persist to XTDB as `:performance-report` entities every 60s
- Basic text dashboard at `/app/monitoring/performance`

### Proposed: Time-Series Performance Charts

#### Hot Paths to Monitor
Based on current analysis, track these routes over time:
- `get-app-overview-stats`
- `get-app-overview-recent`
- `get-app-overview-events`
- `post-app-monitoring-performance`

#### Implementation Approach
1. Query `:performance-report` entity history from XTDB
2. Extract timing stats for hot paths from `:performance-report/pstats`
3. Render time-series charts using existing viz infrastructure

#### Data Already Available
Each `:performance-report` document contains:
```clojure
{:performance-report/instance-id "..."
 :performance-report/generated-at #inst "..."
 :performance-report/git-sha "..."
 :performance-report/pstats
 {:get-app-overview-stats
  {:clock {...}
   :stats {:n ... :mean ... :min ... :max ...}
   :summary "..."}}}
```

This is sufficient for:
- Mean latency over time per endpoint
- P50/P95/P99 percentiles (if we add to stats capture)
- Comparison across git commits/deploys

## Implementation Roadmap

### Recommended Approach

**Start with Phase 3 (Performance Dashboard)** before optimizing, because you can't improve what you can't measure over time.

Currently we have a single snapshot of timing data. If we implement Phase 1 or 2 first, we'll subjectively feel "is it faster?" but won't have historical data to compare against or track regressions.

With the dashboard in place first:
1. Establish a clear baseline with historical tracking
2. Make Phase 1/2 changes
3. See the before/after in charts with git SHA annotations
4. Catch any future regressions automatically

**Alternative:** If the 2.6s load time is actively painful, Phase 1 (reduce `per-type-limit` 200→50) is a one-line change that could cut stats time roughly in half with zero risk. Do that first for quick relief, then build the dashboard.

### Phase 1: Quick Wins
- [ ] Reduce stats `per-type-limit` from 200 to 50
- [ ] Add baseline performance test to CI

### Phase 2: Query Namespace Refactoring (Complete)
- [x] Thread user-settings through request context via `wrap-user-settings` middleware
- [x] Add `resolve-user-settings` helper (ctx-first, DB fallback)
- [x] Add `direct-sensitivity-clauses` — schema-driven Datalog `:where` clauses
- [x] Add `build-count-query` — lightweight ID-only queries
- [x] Push state/date predicates into XTDB for task queries (`tasks-for-today`, `count-tasks-by-state`, etc.)
- [x] Add `relationship-sensitivity-clauses` — `not-join` for related-entity sensitivity/archived filtering
- [x] Push direct + relationship sensitivity into `all-entities-for-user` via `build-entity-query :extra-where`
- [x] Migrate all callers from `get-user-settings` to `resolve-user-settings`
- [x] Remove superseded `PLAN-dashboard-perf.md`

### Phase 3: Performance Dashboard Enhancement
- [ ] Add route to query performance history across instances
- [ ] Create time-series chart component for hot paths
- [ ] Display latency trends over time with git SHA annotations

#### Detailed Implementation Plan

**Data Source:** XTDB entity history on `:performance-report` documents

The existing `load-performance-history` function (app.clj:206) already queries this:
```clojure
(defn load-performance-history [db instance-id {:keys [dur]}]
  (let [doc-id  (keyword "performance-report" instance-id)
        history (xt/entity-history db doc-id :desc {:with-docs? true})
        ...]
    ...))
```

**Proposed additions:**

1. **Cross-instance history query** - Current UI only shows one instance. Add:
   ```clojure
   (defn all-performance-reports [db {:keys [since limit]}]
     (->> (xt/q db '{:find [(pull ?e [*])]
                     :where [[?e :db/doc-type :performance-report]]})
          (map first)
          (sort-by :performance-report/generated-at)
          reverse
          (take (or limit 100))))
   ```

2. **Hot path extraction** - Pull specific routes from pstats:
   ```clojure
   (def hot-paths
     #{:get-app-overview-stats
       :get-app-overview-recent
       :get-app-overview-events
       :get-app-crud-form-entity-type-edit-id})

   (defn extract-hot-path-stats [report]
     (let [pstats (:performance-report/pstats report)]
       (->> hot-paths
            (keep (fn [path]
                    (when-let [data (get pstats path)]
                      {:path path
                       :mean (get-in data [:stats :handler :mean])
                       :n (get-in data [:stats :handler :n])
                       :generated-at (:performance-report/generated-at report)
                       :git-sha (:performance-report/git-sha report)})))
            vec)))
   ```

3. **Chart data format** - Transform for existing viz components:
   ```clojure
   (defn hot-path-time-series [reports path]
     (->> reports
          (keep (fn [r]
                  (when-let [stats (get-in r [:performance-report/pstats path :stats :handler])]
                    {:x (:performance-report/generated-at r)
                     :y (:mean stats)
                     :label (subs (:performance-report/git-sha r) 0 7)})))
          (sort-by :x)))
   ```

**UI Components:**
- Line chart showing mean latency over time per hot path
- Vertical markers for deploys (git SHA changes)
- Table of current vs 7-day-ago comparison

### Phase 4: Advanced Optimizations (If Needed)
- [ ] Request-level caching for `dashboard-recent-entities`
- [ ] Consider combining HTMX endpoints
- [ ] Evaluate server-sent events for progressive loading

## Files Involved

- `src/tech/jgood/gleanmo/app/overview.clj` - Dashboard handlers
- `src/tech/jgood/gleanmo/db/queries.clj` - Query functions
- `src/tech/jgood/gleanmo/observability.clj` - Metrics capture
- `src/tech/jgood/gleanmo/app.clj` - Monitoring routes (lines 206-486)

## Notes

### Metrics Capture Flow
1. `wrap-request-profiling` middleware wraps every request (observability.clj:200)
2. Tufte accumulator collects timing data
3. Background task flushes to XTDB every 60s via `persist-instance-snapshot!`
4. XTDB entity history provides time-series without explicit versioning

### Current Dashboard Location
- URL: `/app/monitoring/performance`
- Super-user only access
- Shows text-based summary with time window selection (1h, 6h, 24h, 7d)
