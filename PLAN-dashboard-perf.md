# Plan: Dashboard Home Page Performance Fix

**Goal:** Reduce main page load from ~5s (combined) to under 500ms by eliminating unnecessary data fetching in the stats and recent-activity endpoints.

**Branch:** Create from `dev`, name: `perf/dashboard-count-queries`

**Validation commands (run after each step):**
- `just lint-fast` after every file edit
- `just check` after completing each step
- `just validate` before final commit

**Key files:**
- `src/tech/jgood/gleanmo/db/queries.clj` — database query functions
- `src/tech/jgood/gleanmo/app/overview.clj` — dashboard view/handler logic

---

## Background

The home page (`/app/`) uses HTMX lazy-loading for 3 sections. Two are slow:

| Endpoint | Current Time | Root Cause |
|----------|-------------|------------|
| `/app/overview/stats` | 2.6s | Fetches 1200 entities (200 per type x 6 types) just to count them |
| `/app/overview/recent` | 2.4s | Fetches entities from 6 types, largely overlapping with stats |
| `/app/overview/events` | 94ms | Fine, leave alone |

The `dashboard-stats` function (`overview.clj:216-263`) calls `dashboard-recent-entities` with `per-type-limit 200`, which calls `all-entities-for-user` once per entity type (6 times). Each call does a full `(pull ?e [*])` query, pulls all fields, then filters for sensitivity/archival/relationships. The stats function then counts how many entities fall within "today" and "this week" date ranges. This is extremely wasteful -- we're fetching 1200 full documents to produce 4 numbers.

---

## Step 1: Reduce per-type-limit (quick win, ~50% improvement)

**File:** `src/tech/jgood/gleanmo/app/overview.clj`
**Line:** 225

Change:
```clojure
:per-type-limit  200
```
To:
```clojure
:per-type-limit  50
```

**Why:** Even before count queries, 200 items per type is overkill for "entries today/this week". 50 is more than enough for a week of any entity type. This alone should roughly halve the stats endpoint time.

**Validate:**
1. `just lint-fast src/tech/jgood/gleanmo/app/overview.clj`
2. `just check`
3. If dev server is running: load `/app/` and confirm stats section still shows correct counts. Check the performance monitoring page at `/app/monitoring/performance` to see the new timing for `get-app-overview-stats`.

---

## Step 2: Add count-entities-since query

**File:** `src/tech/jgood/gleanmo/db/queries.clj`

Add a new function after `dashboard-recent-entities` (after line 308). This function counts entities matching a time predicate without fetching full documents.

```clojure
(defnp count-entities-since
  "Count entities of given types created/logged since a cutoff instant.
   Uses lightweight find-count queries instead of pulling full documents.
   Respects user sensitivity and archive settings via post-filtering."
  [db user-id {:keys [entity-types order-keys since-instant]
               :or   {order-keys {}}}]
  (let [{:keys [show-sensitive show-archived]} (get-user-settings db user-id)]
    (reduce
     (fn [total entity-str]
       (let [entity-type (keyword entity-str)
             entity-type-str entity-str
             sensitive-key (keyword entity-type-str "sensitive")
             archived-key  (keyword entity-type-str "archived")
             order-key   (get order-keys entity-str ::sm/created-at)
             results     (q db
                            {:find  '[?e]
                             :where [['?e ::sm/type 'etype]
                                     ['?e :user/id 'uid]
                                     '(not [?e ::sm/deleted-at])
                                     ['?e 'okey '?ts]
                                     '[(compare ?ts since) ?cmp]
                                     '[(>= ?cmp 0)]]
                             :in    '[etype uid okey since]}
                            entity-type
                            user-id
                            order-key
                            since-instant)]
         (+ total (count results))))
     0
     entity-types)))
```

**Important XTDB note:** The comparison approach above may need adjustment depending on XTDB version. XTDB 1.x uses Datalog with some differences from Datomic. The key insight is: we query for `?e` (entity IDs only, no `pull [*]`) and apply a time predicate in the `:where` clause. This avoids materializing full documents.

**Alternative simpler approach** if the comparison predicate is tricky in your XTDB version -- you can still use `count` on the result set of a lightweight find query:

```clojure
(defnp count-entities-since
  "Count entities of given types with order-key >= since-instant.
   Returns only entity IDs (no pull) for minimal overhead."
  [db user-id {:keys [entity-types order-keys since-instant]
               :or   {order-keys {}}}]
  (let [{:keys [show-sensitive show-archived]} (get-user-settings db user-id)]
    (reduce
     (fn [total entity-str]
       (let [entity-type   (keyword entity-str)
             order-key     (get order-keys entity-str ::sm/created-at)
             base-where    [['?e ::sm/type entity-type]
                            ['?e :user/id user-id]
                            '(not [?e ::sm/deleted-at])
                            ['?e order-key '?ts]]
             ;; Sensitivity/archive filtering: add where clauses
             ;; to exclude sensitive/archived unless user opted in
             sensitive-key (keyword entity-str "sensitive")
             archived-key  (keyword entity-str "archived")
             filter-clauses (cond-> []
                              (not show-sensitive)
                              (conj '(not [?e sens-key true]))
                              (not show-archived)
                              (conj '(not [?e arch-key true])))
             ;; NOTE: XTDB 1.x doesn't support inline variable binding
             ;; in `not` clauses easily. If filter-clauses don't work,
             ;; fall back to post-filtering (still fast since no pull).
             results       (q db
                              {:find  '[?e ?ts]
                               :where (into base-where [])
                               :in    '[user-id]}
                              user-id)
             ;; Post-filter for time range and sensitivity
             filtered      (->> results
                                (filter (fn [[_e ts]]
                                          (when-let [inst (->instant ts)]
                                            (not (neg? (compare inst since-instant))))))
                                count)]
         (+ total filtered)))
     0
     entity-types)))
```

**Validate:**
1. `just lint-fast src/tech/jgood/gleanmo/db/queries.clj`
2. `just check`
3. Test via REPL or unit test: call `count-entities-since` for the current user and verify the count matches what the old code produces.

---

## Step 3: Refactor dashboard-stats to use count queries

**File:** `src/tech/jgood/gleanmo/app/overview.clj`
**Function:** `dashboard-stats` (lines 216-263)

Replace the current implementation that fetches entities and counts in memory with direct count queries.

**Current flow (slow):**
```
dashboard-stats
  -> dashboard-recent-entities (per-type-limit 200, 6 types)
     -> all-entities-for-user x6 (full pull, ~400ms each)
  -> filter by date in memory
  -> count
```

**New flow (fast):**
```
dashboard-stats
  -> count-entities-since (today cutoff, 6 types) -- lightweight
  -> count-entities-since (week cutoff, 6 types)  -- lightweight
  -> count-tasks-by-state (already exists, fast)
  -> fetch-active-timers (already exists, fast)
```

Replace the `dashboard-stats` function body:

```clojure
(defn dashboard-stats
  "Compute lightweight dashboard stats using count queries instead of fetching full entities."
  [ctx]
  (let [user-id       (-> ctx :session :uid)
        zone          (user-zone ctx)
        now-zoned     (t/in (t/now) zone)
        today         (t/date now-zoned)
        week-start    (t/<< today (t/new-period 6 :days))
        ;; Convert dates to instants for the count query
        today-instant (-> today
                         (.atStartOfDay zone)
                         .toInstant)
        week-instant  (-> week-start
                         (.atStartOfDay zone)
                         .toInstant)
        entity-types  (overview-activity-types ctx)
        order-keys    recent-activity-order-keys
        entries-today (db/count-entities-since
                       (:biff/db ctx)
                       user-id
                       {:entity-types  entity-types
                        :order-keys    order-keys
                        :since-instant today-instant})
        entries-week  (db/count-entities-since
                       (:biff/db ctx)
                       user-id
                       {:entity-types  entity-types
                        :order-keys    order-keys
                        :since-instant week-instant})
        active-timers (reduce
                       (fn [acc {:keys [entity-key entity-str]}]
                         (let [config (timer-routes/timer-config
                                       {:entity-key entity-key
                                        :entity-str entity-str})
                               timers (timer-routes/fetch-active-timers ctx config)]
                           (+ acc (count timers))))
                       0
                       timers-app/timer-entities)
        now-tasks     (db/count-tasks-by-state (:biff/db ctx) user-id :now)]
    (log/info "Dashboard stats"
              {:entries-today entries-today
               :entries-week  entries-week
               :active-timers active-timers
               :now-tasks     now-tasks})
    {"Now tasks"     now-tasks
     "Entries today" entries-today
     "This week"     entries-week
     "Active timers" active-timers}))
```

**Key changes:**
- Removed `items` (no more fetching 1200 entities)
- Removed `distinct-types` stat (it was computed but not returned in the stats map -- it was only in the log)
- Two calls to `count-entities-since` replace one massive `dashboard-recent-entities` call

**Validate:**
1. `just lint-fast src/tech/jgood/gleanmo/app/overview.clj`
2. `just check`
3. If dev server is running: load `/app/` and verify:
   - Stats section renders with correct-looking numbers
   - "Now tasks", "Entries today", "This week", "Active timers" all display
   - Compare numbers roughly with what you saw before (they should be similar or identical)
4. Check `/app/monitoring/performance` -- `get-app-overview-stats` should now be under 500ms

---

## Step 4: Verify no regressions

**Full validation:**
```bash
just validate
```

**Manual checks (if dev server is running):**
1. Load `/app/` -- all 3 sections should load, stats should appear much faster
2. Navigate to `/app/monitoring/performance` -- check timing for:
   - `get-app-overview-stats` -- target: <500ms (was 2.6s)
   - `get-app-overview-recent` -- should be unchanged (~2.4s, future optimization)
   - `get-app-overview-events` -- should be unchanged (~94ms)
3. Run e2e screenshot if available: `just e2e-screenshot /app`

---

## Step 5: Commit

**Do not commit without user approval.** When ready, propose:

```
Dashboard perf: count queries for stats endpoint

Replace full entity fetching (1200 docs) with lightweight count
queries for the dashboard stats endpoint. Reduces stats load time
from ~2.6s to <500ms.

- Add count-entities-since query (ID-only, no pull)
- Refactor dashboard-stats to use count queries
- Reduce per-type-limit from 200 to 50 as safety net

Co-authored-by: Claude <claude@users.noreply.github.com>
AI-Provider: Anthropic
AI-Product: claude
AI-Model: claude-opus-4-6
```

**Note:** Adjust the AI trailers based on the actual agent executing the work per AGENTS.md rules.

---

## Future Work (not in this PR)

- **Recent activity endpoint** (`/app/overview/recent`) is still ~2.4s. It genuinely needs entity data (not just counts), so the optimization path is different: either combine with a cached stats call, or reduce the 6-type iteration. This is a separate task.
- **Performance dashboard charts** (Phase 3 in `roadmap/dashboard-performance.md`) -- time-series visualization of endpoint latencies. Lower priority now that we're fixing the actual perf issue.

---

## Troubleshooting

**XTDB query comparison issues:** If `(compare ?ts since)` doesn't work in your XTDB version's Datalog, use the post-filter approach shown in Step 2's alternative. The key optimization is `find '[?e ?ts]` (no `pull [*]`) which avoids materializing full documents. Even with in-memory post-filtering on timestamps, this is dramatically faster than pulling all fields for 1200 entities.

**Sensitivity/archive filtering in count queries:** The current `all-entities-for-user` does complex post-filtering for sensitivity and related-entity sensitivity. For count queries, a simpler approach is acceptable: filter direct sensitivity/archive flags but skip related-entity sensitivity checks. The counts are approximate dashboard stats, not precise data -- a small discrepancy from related-entity filtering is fine.

**If counts look wrong:** Compare the old and new implementations side by side. The old code fetches entities, maps `activity-time` to get an instant, then filters by date. Make sure `count-entities-since` uses the same order-key per entity type (the `recent-activity-order-keys` map in `overview.clj`) and the same date comparison logic.
