---
title: "Slow Content After the Shell"
status: ready
description: "The shells from 098 paint fast, but the regions you act on still take 1–5s in prod; attribute each deferred region's cost, rank the regions, and fix the queries behind them"
created: 2026-10-25
updated: 2026-10-25
tags: [performance, xtdb, queries]
priority: high
---

# Slow Content After the Shell

## Intent

[098-perceived-performance.md](./098-perceived-performance.md) did what it set
out to do. Every converted page now answers with a shell in milliseconds. It
deliberately left query internals alone, so the deferred regions still take
as long as the old pages did. Those regions are the ones you act on: goal
progress, running timers, the timeline, recent workout sessions. This unit
finds out which regions are slow and why, ranks them by how much they hold
you up, and fixes the queries behind them.

> "I think recent updates have been the "percieved performance" I think the
> page shells do load relatively quickly so it seemed to accomplish it's goal
> but a lot of actionable information still takes a long time to load and we
> need to identify, prioritize, and address that in the roadmap too."
> — 2026-10-25

This unit takes over the "Slow queries found" list in 098. Earlier home-page
query history stays in
[013-dashboard-performance.md](./013-dashboard-performance.md).

## Baseline (prod, 2026-10-25, SHA 5236231c)

The first prod snapshot after 098 shipped, covering 7 minutes of use. The full
dump is [below](#raw-snapshot-2026-10-25). Each route's handler span mixes
shell and `?full=1` requests (see step 0), so the "full request" column is
inferred from the call count and the min/max.

| Region (page) | Full request, approx. | Where the time goes |
|---|---|---|
| Goal progress (`/app/goals` `#goals-list`) | ~5s each (2 of 4 calls, max 6.14s) | `goal-source-records` 8 calls, 10.13s total, 27ms–4.11s. 098 measured 0.6–2.4s per call. |
| Recent sessions (`/app/exercise/session` idle, `#wk-recent-sessions`) | ~2.4s each (5 of 10 calls) | `sets-for-sessions` 1.10s mean and `lines-for-sets` 856ms mean, for the last 5 sessions only; `recent-sessions-for-user` 279ms |
| Timeline tier (`/app` `#overview-recent`) | ~1.2s | `recent-activity-across-types` 1.18s. `bounded-pmap` 1.11s ≈ `task` 1.10s (min 975ms): the task windowed scan sets the wall on every call, not just cold ones |
| Running timers (inside the timeline tier) | — | `active-timers-for-user` 15 calls, 78.9ms mean, **625ms max**. It should be a sparse-flag lookup |
| Timers start list + recent logs (`/app/timers`) | ~0.37–0.77s | `recent-completed-timer-logs` up to 543ms; one 560ms `all-entities-for-user` (location) |
| Today (`/app/task/today`) | ≤0.68s | `count-tasks-completed-in-range` 6 calls at 53ms mean. Acceptable |

The active workout shell isn't in this snapshot (no active session was
opened). 098 estimated it at ~1.5s, because `lines-for-sets` runs in the
shell there.

## Priorities

Ranked by what you are waiting on to act, weighted by how cheap the first
check is.

0. **Attribute cost to regions (identification, do first).** The handler span
   is per route, so a shell request and its `?full=1` request are averaged
   together. The goals source loop also has no per-source span, so the 4.11s
   call can't be traced to a source.
   - Name the handler span by tier: `get-app-goals` vs `get-app-goals-full`,
     `get-app-full-timeline`, and so on.
   - Wrap each `goal-source-records` call in a span named for its source,
     the way the overview already labels per-type spans
     (`goals/dashboard.clj:191`).

   After one more prod snapshot, the table above can be filled in directly
   rather than inferred.
1. **Workout: `sets-for-sessions` / `lines-for-sets`.** These queries gate
   the active workout shell and make up the whole idle recent-sessions
   region (`workout.clj:97`, `:880`).
   - Hypothesis from this snapshot: the set query costs *more* than the line
     query, although sets are far fewer than lines. That points to both
     scanning everything the user owns through `[?e :user/id user-id]`, with
     the `[?sid ...]` collection never driving the join.
   - Contrast: `exercise-line-records` (`db/queries.clj:1540`) binds only
     `set-id` and checks ownership afterwards.
   - Check: run with `xtdb.query=debug` and read `:triple-clause-var-order`
     and `:join-order`
     ([074-query-shape-audit.md](./074-query-shape-audit.md)).
   - Fix if confirmed: start from the parent id, then keep the user check on
     the fetched docs. User scoping stays; only where it runs moves.
2. **Goal progress: `goal-source-records`.** This is the largest absolute
   wait. It needs step 0's per-source spans before choosing a fix. The
   exercise-line path is a likely suspect (densest type, two-hop
   set → line join). `visible-rows` and the `attr-values` passes are the
   other costs to measure.
3. **Home timeline: the `task` windowed scan.** It takes ~1.1s on every call
   and is the critical path of the parallel fan-out. Find out why the task
   window is so much more expensive than every other type. Candidates: task
   volume in the window, or the scan's sort key. See 013's "a bound clause
   defeats range push-down" finding.
4. **Running timers: `active-timers-for-user` spikes.** The max is 625ms
   against a 1.5ms min. Confirm that every timer entity declares `running` and
   that prod docs were backfilled ([071](./071-timer-running-flag.md)). If
   they are, the spike is cold cache and needs no work.
5. **Timers page: `recent-completed-timer-logs`.** It runs up to 543ms.
   Check this only after 1–4.

Carried over from 098's list, not visible in this snapshot (re-check after
step 0):
- `recent-lines-for-user`, ~0.7s, is the active workout's exercise memory.
- `all-entities-for-user` for tasks took ~613ms on Task focus.
- Task focus with `state=any` / `not-done` calls `tasks-by-state` once per
  state, and each call loads every task.

## Specification

- Step 0 lands first and ships alone, so the next snapshot measures regions.
- Each later item is a separate change with a before/after prod snapshot,
  recorded under Progress with SHA and date.
- Query changes follow AGENTS.md: they stay in `db/queries.clj` under
  `defnp`, use scan-then-pull for list queries, and keep intent-shaped
  public signatures. Verify join order instead of reasoning about it.

## Validation

- [ ] Step 0 deployed; a snapshot shows shell and full handler spans
      separately, and goal-source spans per source
- [ ] Each fixed item has a prod before/after recorded below
- [ ] `just validate` green for each change (read the output)

## Scope

- Query and attribution work only. The shell/skeleton mechanics stay in 098.
- No caching layers until the query shapes have been checked.

## Context

- `src/tech/jgood/gleanmo/db/queries.clj` — `sets-for-sessions` `:1247`,
  `lines-for-sets` `:1266`, `goal-source-records` `:1567`,
  `recent-activity-across-types` `:973`, `windowed-scan` `:376`
- `src/tech/jgood/gleanmo/app/workout.clj`, `goals/dashboard.clj`,
  `app/deferred.clj`
- [098-perceived-performance.md](./098-perceived-performance.md),
  [013-dashboard-performance.md](./013-dashboard-performance.md),
  [021-performance.md](./021-performance.md),
  [074-query-shape-audit.md](./074-query-shape-audit.md),
  [076-performance-regression-tracking.md](./076-performance-regression-tracking.md)

## Progress

- Completed: baseline recorded (2026-10-25)
- Next action: step 0

## Raw snapshot (2026-10-25)

<details>
<summary>Performance dashboard, live metrics at 2026-10-25T23:32:58Z (instance e7a73aed…, SHA 5236231c)</summary>

```
Performance Dashboard (This Instance)
INSTANCE
e7a73aed-13d5-4704-8c6d-f0527256351c
GIT SHA
5236231c6662f757b9c4cf4ef745e5f0bda4333d
TOTAL SNAPSHOTS
2
LATEST SNAPSHOT
2026-10-25T23:25:58.939743766Z
Persist & Reset Metrics
Live Metrics (since last persist)
Snapshot taken at 2026-10-25T23:32:58.938665011Z.

get-app

Span                                                       Calls       Mean      Total        Min        Max
calendar-event                                                 3    51.04ms   153.13ms     4.02ms    80.83ms
defn_active-timers-for-user                                   15    78.90ms      1.18s     1.52ms   625.53ms
meditation-log                                                 3   180.13ms   540.40ms     6.83ms   522.62ms
reading-log                                                    3    13.41ms    40.22ms    10.91ms    17.26ms
calendar-event                                                 3     4.26ms    12.79ms     3.87ms     4.67ms
defn_direct-sensitivity-clauses                                3    23.39µs    70.16µs    21.28µs    25.97µs
defn_recent-activity-across-types                              3      1.18s      3.54s      1.05s      1.25s
defn_all-entities-for-user                                     3    30.41ms    91.22ms     9.00ms    71.47ms
defn_fetch-entities-by-ids                                    39     7.57ms   295.38ms      495ns    90.98ms
recent-activity-pull                                           3    37.81ms   113.44ms    11.98ms    61.82ms
defn_resolve-user-settings_1                                  39     6.02µs   234.90µs     1.41µs    14.70µs
symptom-episode                                                3     4.43ms    13.29ms     1.29ms     6.35ms
boulder-session                                                3   963.12µs     2.89ms   868.48µs     1.07ms
symptom-log                                                    3    31.27ms    93.82ms     5.02ms    79.57ms
mood-log                                                       3     1.17ms     3.50ms   476.04µs     1.67ms
defn_dashboard-upcoming-events                                 3    30.66ms    91.97ms     9.24ms    71.71ms
defn_resolve-user-settings_2                                  39 463.05128205128204ns    18.06µs      127ns      878ns
defn_pinned-goals-for-user                                    15     3.36ms    50.43ms   446.68µs    38.11ms
defn_bounded-pmap                                              3      1.11s      3.32s   977.19ms      1.23s
defn_get-user-authz                                            9   831.70µs     7.49ms   500.58µs     1.45ms
defn_windowed-scan                                            39   187.59ms      7.32s   468.84µs      1.23s
project-log                                                    3   396.43ms      1.19s   196.17ms   689.01ms
habit-log                                                      3   225.50ms   676.50ms    47.85ms   539.29ms
exercise-session                                               3   265.57ms   796.71ms    98.16ms   588.41ms
defn_get-user-settings                                         9     1.53ms    13.79ms     1.13ms     1.97ms
task                                                           3      1.10s      3.31s   974.73ms      1.23s
bm-log                                                         3    66.90ms   200.69ms    32.87ms   102.14ms
defn_resolve-user-settings                                    78     5.30µs   413.64µs      679ns    15.80µs
defn_get-entity-by-id                                        414     3.86ms      1.60s   363.42µs   489.25ms
handler                                                        9   649.78ms      5.85s     7.81ms      2.15s
defn_count-tasks-by-state                                      3     7.69ms    23.07ms     6.40ms     8.43ms
calendar-event                                                 3   138.62µs   415.85µs   111.02µs   165.62µs
medication-log                                                 3    98.72ms   296.17ms    92.45ms   104.43ms
Clock total: 5.85s
get-app-exercise-session

Span                                                       Calls       Mean      Total        Min        Max
defn_active-timers-for-user                                   10     5.49ms    54.92ms     3.62ms    10.47ms
defn_sets-for-sessions                                         5      1.10s      5.51s   766.07ms      1.32s
defn_fetch-entities-by-ids                                    25     2.61ms    65.14ms      681ns    43.76ms
defn_distinct-field-values                                    10     1.17ms    11.71ms   833.08µs     1.43ms
defn_resolve-user-settings_1                                  20     5.42µs   108.31µs     2.91µs    10.02µs
defn_recent-sessions-for-user                                  5   278.68ms      1.39s    84.16ms   589.77ms
defn_lines-for-sets                                            5   855.64ms      4.28s   747.36ms      1.16s
defn_resolve-user-settings_2                                  20    534.5ns    10.69µs      322ns     1.14µs
defn_pinned-goals-for-user                                    10   870.18µs     8.70ms   428.28µs     2.34ms
defn_get-user-authz                                           10     2.98ms    29.82ms   589.75µs     5.57ms
defn_get-user-settings                                        10     1.98ms    19.83ms   894.46µs     6.44ms
defn_resolve-user-settings                                    40     4.77µs   190.68µs     1.18µs    11.48µs
defn_get-entity-by-id                                         45     1.53ms    68.97ms   326.91µs     6.85ms
handler                                                       10      1.25s     12.47s    12.98ms      2.66s
Clock total: 12.47s
get-app-goals

Span                                                       Calls       Mean      Total        Min        Max
defn_reading-logs-for-book                                     2    20.04ms    40.08ms    13.58ms    26.51ms
defn_goal-source-records                                       8      1.27s     10.13s    27.23ms      4.11s
defn_goals-for-user                                            4     6.44ms    25.78ms     2.27ms     9.93ms
defn_all-entities-for-user                                     4     6.42ms    25.67ms     2.26ms     9.91ms
defn_fetch-entities-by-ids                                    38    20.38ms   774.49ms      775ns   104.25ms
defn_resolve-user-settings_1                                   8     6.90µs    55.24µs     3.37µs    13.89µs
goal                                                           4     4.19ms    16.77ms     1.16ms     7.46ms
defn_resolve-user-settings_2                                   8  486.375ns     3.89µs      263ns      770ns
defn_pinned-goals-for-user                                     4    15.28ms    61.12ms   505.52µs    58.96ms
defn_get-user-authz                                            4     1.41ms     5.64ms   602.28µs     2.46ms
defn_get-user-settings                                         4     1.29ms     5.15ms   915.40µs     1.76ms
defn_resolve-user-settings                                    16     5.90µs    94.45µs     1.31µs    15.11µs
defn_get-entity-by-id                                          8     1.32ms    10.53ms   591.27µs     2.39ms
handler                                                        4      2.66s     10.62s    25.97ms      6.14s
Clock total: 10.62s
get-app-monitoring-performance

Span                                                       Calls       Mean      Total        Min        Max
defn_entity-history-desc                                       2   349.66µs   699.33µs   340.84µs   358.49µs
defn_fetch-entities-by-ids                                     1     1.08µs     1.08µs     1.08µs     1.08µs
defn_resolve-user-settings_1                                   1     7.73µs     7.73µs     7.73µs     7.73µs
defn_resolve-user-settings_2                                   1    348.0ns    348.0ns      348ns      348ns
defn_pinned-goals-for-user                                     1   889.98µs   889.98µs   889.98µs   889.98µs
defn_get-user-authz                                            2     1.06ms     2.13ms   912.76µs     1.21ms
defn_get-user-settings                                         1     6.03ms     6.03ms     6.03ms     6.03ms
defn_resolve-user-settings                                     2     7.01µs    14.02µs     5.02µs     9.00µs
defn_get-entity-by-id                                          3     2.70ms     8.09ms   904.48µs     5.98ms
handler                                                        1    99.83ms    99.83ms    99.83ms    99.83ms
Clock total: 99.86ms
get-app-task-today

Span                                                       Calls       Mean      Total        Min        Max
defn_direct-sensitivity-clauses                               15   106.56µs     1.60ms    20.75µs     1.14ms
defn_all-entities-for-user                                     3    20.35ms    61.04ms     7.90ms    45.04ms
defn_projects-for-user                                         3    20.42ms    61.26ms     7.98ms    45.13ms
defn_fetch-entities-by-ids                                     9     1.53ms    13.73ms      787ns     6.54ms
defn_tasks-completed-today                                     3   735.70µs     2.21ms   594.76µs   850.88µs
defn_tasks-for-today                                           3    37.86ms   113.58ms    32.84ms    41.41ms
defn_count-tasks-completed-all-time                            3    28.28ms    84.84ms    24.71ms    35.41ms
defn_resolve-user-settings_1                                   9     6.54µs    58.87µs     2.94µs    11.59µs
project                                                        3    15.64ms    46.91ms     4.02ms    38.33ms
defn_resolve-user-settings_2                                   9 573.6666666666666ns     5.16µs      279ns     1.15µs
defn_pinned-goals-for-user                                     6   761.64µs     4.57ms   509.89µs   885.58µs
defn_get-user-authz                                            6   766.60µs     4.60ms   628.17µs     1.09ms
defn_count-tasks-completed-in-range                            6    52.76ms   316.58ms    25.74ms    76.66ms
defn_get-user-settings                                         6     3.79ms    22.72ms   914.08µs    11.51ms
defn_resolve-user-settings                                    18     5.82µs   104.82µs     1.03µs    12.76µs
defn_get-entity-by-id                                         21     1.55ms    32.58ms   442.47µs    11.06ms
handler                                                        6   183.75ms      1.10s     7.24ms   676.69ms
Clock total: 1.10s
get-app-timers

Span                                                       Calls       Mean      Total        Min        Max
defn_active-timers-for-user                                   18     5.68ms   102.28ms     1.69ms    25.27ms
book                                                           3     3.69ms    11.06ms     3.18ms     4.57ms
defn_all-entities-for-user                                    15    46.36ms   695.41ms     2.41ms   560.30ms
meditation                                                     3     1.88ms     5.63ms     1.13ms     3.13ms
project                                                        3   111.58µs   334.75µs    84.14µs   135.60µs
defn_fetch-entities-by-ids                                    48    13.46ms   645.89ms      561ns   553.72ms
book                                                           3   197.04µs   591.13µs   156.76µs   274.28µs
defn_resolve-user-settings_1                                  33     4.62µs   152.42µs     2.44µs    17.04µs
meditation                                                     3   117.35µs   352.05µs   103.08µs   140.99µs
project                                                        3     3.13ms     9.39ms     2.87ms     3.51ms
defn_resolve-user-settings_2                                  48 523.5416666666666ns    25.13µs      216ns     1.12µs
defn_pinned-goals-for-user                                     6   712.85µs     4.28ms   480.41µs     1.17ms
defn_get-user-authz                                            6   745.65µs     4.47ms   524.15µs     1.22ms
defn_all-for-user-query                                       15    46.40ms   696.01ms     2.44ms   560.34ms
location                                                       6     9.70ms    58.20ms     1.95ms    41.58ms
defn_get-user-settings                                         6     1.41ms     8.43ms     1.11ms     1.95ms
defn_recent-completed-timer-logs                               9    96.73ms   870.53ms    17.47ms   543.08ms
defn_resolve-user-settings                                    81     3.50µs   283.47µs      838ns    17.91µs
defn_get-entity-by-id                                         18     1.23ms    22.11ms   457.50µs     5.60ms
handler                                                        6   372.07ms      2.23s    28.70ms   772.22ms
Clock total: 2.23s
get-css-main-css

Span                                                       Calls       Mean      Total        Min        Max
handler                                                        1     1.08ms     1.08ms     1.08ms     1.08ms
Clock total: 1.11ms
get-fonts-space-mono-space-mono-400-normal-latin-woff2

Span                                                       Calls       Mean      Total        Min        Max
handler                                                        1   576.41µs   576.41µs   576.41µs   576.41µs
Clock total: 595.91µs
get-fonts-space-mono-space-mono-700-normal-latin-woff2

Span                                                       Calls       Mean      Total        Min        Max
handler                                                        1   684.59µs   684.59µs   684.59µs   684.59µs
Clock total: 707.07µs
get-fonts-space-mono-space-mono-css

Span                                                       Calls       Mean      Total        Min        Max
handler                                                        1     1.00ms     1.00ms     1.00ms     1.00ms
Clock total: 1.03ms
get-js-main-js

Span                                                       Calls       Mean      Total        Min        Max
handler                                                        1     1.92ms     1.92ms     1.92ms     1.92ms
Clock total: 1.94ms
get-sw-js

Span                                                       Calls       Mean      Total        Min        Max
handler                                                       18    15.50ms   278.99ms   684.55µs    92.73ms
Clock total: 282.06ms
get-vendor-choices-11-2-4-choices-min-css

Span                                                       Calls       Mean      Total        Min        Max
handler                                                        1   527.36µs   527.36µs   527.36µs   527.36µs
Clock total: 543.42µs
get-vendor-choices-11-2-4-choices-min-js

Span                                                       Calls       Mean      Total        Min        Max
handler                                                        1   776.56µs   776.56µs   776.56µs   776.56µs
Clock total: 795.10µs
get-vendor-htmx-1-9-0-htmx-min-js

Span                                                       Calls       Mean      Total        Min        Max
handler                                                        1   835.08µs   835.08µs   835.08µs   835.08µs
Clock total: 853.80µs
get-vendor-sortable-1-15-0-sortable-min-js

Span                                                       Calls       Mean      Total        Min        Max
handler                                                        1     1.70ms     1.70ms     1.70ms     1.70ms
Clock total: 1.75ms
post-app-monitoring-performance

Span                                                       Calls       Mean      Total        Min        Max
handler                                                        1   949.89ms   949.89ms   949.89ms   949.89ms
defn_put-performance-report!                                   1   932.23ms   932.23ms   932.23ms   932.23ms
defn_get-user-authz                                            1   622.37µs   622.37µs   622.37µs   622.37µs
defn_get-entity-by-id                                          2   980.03µs     1.96ms   616.17µs     1.34ms
defn_get-user-settings                                         1     6.03ms     6.03ms     6.03ms     6.03ms
Clock total: 949.91ms
```

</details>
