---
title: "Perceived Performance: Shell Then Full Page"
status: active
description: "Slow pages answer with a fast shell of real cheap data and shaped skeletons, then fill the slow regions from the same URL's full page in one or a few htmx requests"
created: 2026-10-19
updated: 2026-10-25
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
| `/app/timers` | header, running timers (with their 30s poll), per-type links | `#start-timer-section` (location picker, filter, start list), `#recent-logs-section` |

Per-page decisions made while building (2026-10-19):

- **Goals.** The shell reads `goals-for-user` plus one visibility fetch, so a
  goal hidden by the sensitive/archived settings never shows even as a label.
  Rows group by family from the goal alone (a book goal carries a placeholder
  `:book-progress` so it groups as it will). Pin stars in the shell work.
- **Home.** `/app/overview/recent` and `/app/goals/pinned` are gone; the
  timeline tier renders the pinned-goal cards' label-only skeleton in place,
  and the goals tier replaces it. With nothing pinned the region is empty.
- **Task focus.** `focus-view` stays the full render, because the task
  mutations (`set-state`, `snooze`, …) render it and `hx-select` `#task-list`
  from it. The route uses `focus-page`, the deferred handler.
- **Workout.** The shell prefills the log form from the current session's
  lines (already read for the view, so free): after a save, the exercise just
  logged and its values are exactly right. The full memory (other exercises'
  last values for recall-on-select) arrives as a hidden `#wk-memory` data
  element, and the form script reads memory at select time instead of once
  at init. When the session has no lines yet, the default exercise depends on
  the full memory, so the shell reads it itself (~0.7s); a fresh session has
  few or no sets, so `lines-for-sets` costs little then.
- **Today.** The loader sits outside `#today-content`, because quick add and
  every row action replace that block.
- **Timers.** The start list is deferred with the recent logs, not rendered
  early: its order (recently timed first) and the location default come from
  the recent logs, so an early list would reshuffle under the cursor.
- **Not converted:** `/app/monitoring/performance` (~660ms; super-user only).

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

Moved to [105-slow-content-after-shell.md](./105-slow-content-after-shell.md)
on 2026-10-25, which ranks them against the first post-deploy prod snapshot.

## Prod result (2026-10-25, SHA 5236231c)

The shells meet the goal. First content is fast on every converted page, and
complete times are about the same as before, which is what the estimates
predicted. Handler spans mix shell and `?full=1` requests, so the split below
is inferred from min/max (105 step 0 makes it measurable). Raw dump in
[105](./105-slow-content-after-shell.md#raw-snapshot-2026-10-25).

| Page | Shell (min handler) | Full (approx.) | vs. estimate |
|------|--------------------|----------------|--------------|
| Goals | 26ms | ~5s (max 6.14s) | shell as estimated; full worse than ~3.0s |
| Home | 7.8ms | timeline ~1.2s | as estimated |
| Workout (idle) | 13ms | recent sessions ~2.4s | first measurement |
| Today | 7.2ms | ≤0.68s | as estimated |
| Timers | 29ms | ~0.37–0.77s | as estimated |

> "I think the page shells do load relatively quickly so it seemed to
> accomplish it's goal but a lot of actionable information still takes a long
> time to load" — 2026-10-25

## Validation

- [x] `just validate` green (read the output; it can exit 0 on failures)
- [ ] Unit tests for `deferred` (URL building, tier selection) and that each
      converted page's shell and full render carry the same region ids
      (`deferred` covered; per-page region-id tests not written)
- [x] e2e: existing suites pass against shells (they wait for deferred regions)
- [x] Series tick before and after; before/after pairs for the converted pages
- [x] After deploy: compare shell and full handler times on
      `/app/monitoring/performance` with the estimates above (2026-10-25,
      see Prod result)

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
- **A save during the load.** A row action on Today or Task focus, taken
  before the loader returns, renders fresh content — then the loader's
  response, computed from a snapshot that may predate the save, swaps its
  regions over it. The window is the full request's duration. Tolerable, or
  should a mutation cancel the in-flight loader (`hx-sync`)?
- **Double cost of cheap data.** The full request recomputes what the shell
  already showed. Negligible for labels; revisit if a shell grows.

## Progress

- Checkout: merged to `dev` and deployed (prod at 5236231c on 2026-10-25)
- Completed (2026-10-19): `defnp` conversion; `app/deferred.clj`; Goals,
  Home (two tiers), Task focus, Workout, Today, and Timers converted.
  `/app/overview/recent` and `/app/goals/pinned` removed.
- Fixes found on the way:
  - htmx 1.9.0 with `hx-swap="none"` + `hx-select-oob` fires
    `afterSwap`/`afterSettle` only on the *last* out-of-band region, never on
    the loader. `main.js` re-fires both on every region once the loader's
    `htmx:afterRequest` succeeds, so per-region initializers (start-list
    filter, Choices, sortable, charts) run. Found by `test-timers-workspace`.
  - `bg-dark-light` is not a defined color (no Tailwind theme entry, no CSS
    class), so every skeleton bar — including the old home timeline
    skeleton — was transparent. Skeletons now use `bg-dark-border`. 14 other
    uses (mostly `hover:bg-dark-light`) remain and do nothing; worth a
    backlog sweep.
- Validation: `just validate` green; `just e2e-test-all` green (25 suites);
  before/after pairs for smoke, goals, workout, timers-workspace,
  home-running-timer, today-mobile all pair; series ticks
  `2026-10-19T15-35-00Z` (before) and `2026-10-19T15-50-51Z` (after). Shell
  states checked by screenshot with `?full=1` held back, desktop and mobile.
  Local timings are not meaningful (near-empty DB); compare prod against the
  estimates above.
- Blockers: none
- Prod compared with the estimates (2026-10-25): shells as estimated; the
  slow regions move to [105](./105-slow-content-after-shell.md)
- Remaining: per-page region-id tests; the Open Questions above
- Next action: decide the open questions, or close this unit with them
  carried forward
