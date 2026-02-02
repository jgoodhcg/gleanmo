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

## Current Baseline (2025-01-30)

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

### Phase 2: Count Queries
- [ ] Implement `count-entities-since` function
- [ ] Refactor `dashboard-stats` to use count queries
- [ ] Validate with performance metrics

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
