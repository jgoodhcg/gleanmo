---
title: "Perceived Performance: Shell Then Full Page"
status: active
description: "Slow pages answer with a fast shell of real cheap data and shaped skeletons, then fill the slow regions from the same URL's full page in one or a few htmx requests"
created: 2026-10-19
updated: 2026-10-19
tags: [performance, htmx, ux]
priority: high
---

# Perceived Performance: Shell Then Full Page

## Intent

Make the app *feel* faster without touching the queries that make it slow.
A slow page renders a quick shell — layout, header, filter forms, labels,
anything cheap — with skeletons shaped like the content they stand in for,
then fills the slow regions with one (at most a few) htmx requests after
render. Query work stays in [013-dashboard-performance.md](./013-dashboard-performance.md)
and [021-performance.md](./021-performance.md); this unit is about when the
user sees something, not how long the whole page takes.

## Specification

### Design (decided 2026-10-19)

1. **Queries: accretion only.** New small queries (a cheap labels-only first
   slice) may be added to `db/queries.clj`; existing query internals do not
   change here. Slow queries noticed along the way are recorded below.
2. **`defnp` first.** Every public function in `db/queries.clj` and
   `db/mutations.clj` is a Tufte `defnp`, so each page's handler span has a
   breakdown on the performance dashboard (`/app/task/today` had none).
3. **Shell vs full, one URL.**
   - `/app/<page>` returns the **shell** — what users navigate to.
   - `/app/<page>?full=1` returns the **full page**: a normal standalone page
     with everything computed. Other query params are kept
     (`/app/task/focus?search=x&full=1`).
   - The shell has one loader element per tier:
     `{:hx-get "/app/goals?full=1" :hx-trigger "load" :hx-swap "none"
       :hx-select-oob "#goals-list,#goals-notes"}` — one request; htmx swaps
     each listed id over its skeleton. No `hx-swap-oob` markers in server HTML,
     no per-region fragment endpoints.
   - A page with a wide spread of costs may use a second tier
     (`?full=1&tier=<name>`), which computes only that tier's regions. Tiers
     run one after another (`hx-trigger "htmx:afterRequest from:#<previous
     loader>"`), never at once, so their queries don't compete on the small
     prod box.
   - One shared helper, `app/deferred.clj`: `handler` (shell by default, full
     for `?full=1`), `loader`, `wants?` (which tiers a full render computes),
     `skeleton-attrs`, `pulse-delay`. The layout is never stripped based on
     `HX-Request`.
4. **Skeletons** are shaped like the real content — `overview/timeline-skeleton`
   is the model: pulse with staggered delays, no generic spinners. Skeleton
   regions carry `aria-busy` and `data-deferred-pending` (an e2e hook).
5. **Saves are out of scope.** Mutations keep write + 303 (or their existing
   fragment responses, which call the full render directly, so they never
   return skeletons).
6. **Scope.** Pages whose prod handler is over ~100ms. Not CRUD new/edit
   forms or pages that are already fast.

### Pages

Order: Goals → Home → Task focus → Workout → Today, then the other slow pages
from the metrics (Timers).

| Page | Shell (real data) | Deferred regions |
|------|-------------------|------------------|
| `/app/goals` | header, one row per visible active goal with its real label, group and pin star | `#goals-list` (progress, figures), `#goals-notes` (archived, hidden count) |
| `/app` (home) | hidden h1, PWA banner | tier `timeline`: `#overview-recent` (running timers, timeline, stats); tier `goals`: `#home-pinned-goals` |
| `/app/task/focus` | header, full filter form (projects) | `#task-list` |
| `/app/exercise/session` | the whole active-session view, or the idle start form | active: `#wk-memory` (exercise memory); idle: `#wk-recent-sessions` |
| `/app/task/today` | header, date, quick add, backlog link | `#today-stats`, `#today-tasks` |
| `/app/timers` | see Progress | see Progress |

## Estimates (to compare with prod after deploy)

From prod handler means on 2026-10-19 (SHA 947212b). "First content" is the
shell's server time; "complete" is shell + full request. The full request
recomputes the shell's cheap data, so complete is roughly the old handler time
plus the shell. Network round trips are not included.

| Page | Before (handler) | First content (est.) | Complete (est.) |
|------|------------------|----------------------|-----------------|
| Goals | ~3.0s | ~30–80ms (`goals-for-user` ~3ms + visibility fetch + layout) | ~3.0–3.1s |
| Home | shell 14ms, then `/app/overview/recent` 1.8–2.3s, then `/app/goals/pinned` | ~15ms (unchanged) | timeline ~1.8–2.3s (unchanged); pinned goals after it, as before |
| Task focus | ~756ms | ~30–60ms (projects for the filter) | ~0.8s |
| Workout (active) | ~2.3s | ~1.5s (`lines-for-sets` 1.4s stays in the shell) | ~2.3s (memory `recent-lines-for-user` ~0.7s) |
| Workout (idle) | not measured separately | ~50ms | recent sessions: `lines-for-sets` over 5 sessions |
| Today | 209–931ms | ~20–40ms | ~0.2–0.9s |
| Timers | ~730ms | see Progress | ~0.75s |

## Slow queries found (future work, not changed here)

- `lines-for-sets` ~1.4s in prod despite being parent-scoped (equality on set
  ids). It now gates the Workout shell, so it is the main thing between the
  active session and a fast first paint. Check its join order per
  [074-query-shape-audit.md](./074-query-shape-audit.md).
- `goal-source-records` ×6 per `/app/goals` request at 0.6–2.4s each — one per
  source request window; the goals page total is dominated by it.
- `recent-activity-across-types` ~1.2s, 26 `windowed-scan` calls and 258
  `get-entity-by-id` calls per `/app/overview/recent`.
- `active-timers-for-user` up to 841ms on home — should be a sparse-flag
  lookup since [071-timer-running-flag.md](./071-timer-running-flag.md); check
  that every timer entity declares `running` and that prod was backfilled.
- `all-entities-for-user` for tasks ~613ms on Task focus.
- `recent-completed-timer-logs` up to 567ms on Timers.
- `recent-lines-for-user` ~0.7s for Workout's exercise memory.

## Validation

- [ ] `just validate` green (read the output; it can exit 0 on failures)
- [ ] Unit tests for `deferred` (URL building, tier selection) and that each
      converted page's shell and full render carry the same region ids
- [ ] e2e: existing suites pass against shells (they wait for deferred regions)
- [ ] Series tick before and after; before/after pairs for the converted pages
- [ ] After deploy: compare shell and full handler times on
      `/app/monitoring/performance` with the estimates above

## Scope

- No changes to existing query internals.
- No change to how saves respond.
- No CRUD new/edit forms, no already-fast pages.

## Context

- `src/tech/jgood/gleanmo/app/deferred.clj` — the shared helper
- `src/tech/jgood/gleanmo/app/overview.clj` — the earlier home pattern
  (`overview-shell` + `/app/overview/recent`, `timeline-skeleton`) this
  supersedes
- [013-dashboard-performance.md](./013-dashboard-performance.md),
  [021-performance.md](./021-performance.md)

## Open Questions

- **Skeleton flash after save.** Mutations that 303 back to a deferred page
  land on its shell, so a save now shows skeletons for the slow regions before
  they fill. Workout avoids this by keeping the active-session view in the
  shell. For the other pages, is the flash acceptable, or should a 303 carry a
  hint (e.g. `?full=1`) so post-save loads render complete in one response?
- **Failed loader.** If the full request errors, the skeletons stay. The old
  home fragment had the same behavior. Worth an `htmx:responseError` handler
  that swaps in a "couldn't load — reload" line?
- **Double cost of cheap data.** The full request recomputes what the shell
  already showed. Negligible for labels; revisit if a shell grows.

## Progress

- Checkout: branch `perceived-perf` (from `dev` at 7aa7e06)
- Completed: `defnp` conversion of the remaining public db functions
- Remaining: see Pages
- Validation: —
- Blockers: none
- Next action: convert Goals
