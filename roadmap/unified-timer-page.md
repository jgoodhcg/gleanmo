---
title: "Unified Timer Workspace"
status: active
description: "One timer page: all running timers across types, search-to-start any parent, one-tap start/stop without CRUD form bounces"
created: 2026-07-27
updated: 2026-07-27
tags: [ux, timers, search, htmx]
priority: high
---

# Unified Timer Workspace

## Intent

User-proposed (2026-07-27), replacing the "three sidebar deep links" approach
in `qol-quick-actions.md` item 1: instead of bypassing the `/app/timers` hub,
**make the hub the destination**. One page where every running timer is
visible regardless of type, and any timer can be started by typing a few
characters — no per-type navigation, no scanning an alphabetical card grid.

Today's full start cost: sidebar → hub (2s pass-through) → per-type page →
scan the parent card grid → "Start Timer" → **full CRUD new-form** → save.
`start-timer-card` (`timer/routes.clj:250-270`) links to
`/app/crud/form/<entity>/new?<rel>=<parent-id>&redirect=...`, so starting
costs a form round trip just like stopping does — a chunk of
project-log/new's 111 views in the 28-day Plausible sample are timer starts.
Target: home → running timer in ≤ 3 taps, zero form loads.

## Specification

### The workspace (`/app/timers` becomes it)

Rebuild the hub page (`app/timers.clj`) as the timer workspace, composed from
the generic helpers in `timer/routes.clj` iterated over
`timers-app/timer-entities` (the three configs: project-log, reading-log,
meditation-log — `app/overview.clj:371-395` already iterates them for its
active-timer summaries; extract/share rather than duplicate):

1. **Active timers, all types** — `active-timer-card` per running timer with
   stop + edit affordances as today. HTMX 30s poll via a new combined
   fragment endpoint `GET /app/timers/active` (mirrors the per-entity
   `/active` endpoints).
2. **Search-to-start** — one text input above a single list of all parent
   entities across types (projects 📋, books 📖, meditations 🧘). Rows show
   type icon + label + a **Start** button. Client-side substring filter on
   label (lists are small — tens of rows; no server search). Row order when
   the filter is empty: **recently-timed first** (parents of the most recent
   logs across types, latest first), remainder alphabetical — the common
   case should need zero typing.
3. **Combined recent logs** — last ~5 completed logs across types as edit
   links (redirect back to `/app/timers`), replacing nothing — per-type
   pages keep their fuller lists.
4. **Per-type links** — small footer links to the per-entity pages
   ("project stats →"), which survive as the home of today-stats and
   longer recent-log lists.

### One-tap start (no form)

`POST /app/timers/start/<entity-str>` with the parent id as a form param:
validate `entity-str` against the registered timer entities, create the log
with `{<rel-key> parent-id, <beginning-key> (t/now)}` **sharing the CRUD
create path's mutation logic** (not a hand-rolled write), 303 back to
`/app/timers`. The per-entity pages' start cards switch to the same POST.
The old form-bounce start disappears; annotating a running timer stays one
tap away via the active card's edit link.

Behavior change, same shape the user already approved for stop
(`qol-quick-actions.md` item 2): the review step moves from forced-at-start
to opt-in-while-running.

### Stop returns to the issuing page

Extends item 2 of `qol-quick-actions.md`: the stop link
(`timer/routes.clj:294,370-391`) gains a `redirect` query param
(whitelisted to `/app/timer`-prefixed paths), defaulting to the per-entity
page. The workspace renders stop links with `redirect=/app/timers`.

### Search primitive

Implement the filter input as a small generic behavior in
`resources/public/js/main.js` (e.g. `input[data-filter-list]` filtering
descendants of a target container by `data-filter-text`), so
`search-filter.md`'s CRUD-list case can reuse it later. No Choices.js here —
this filters visible rows, it is not a form select.

### Navigation

- Sidebar (per `qol-quick-actions.md` item 1, amended): a single
  `⏱ timers → /app/timers` link at the top of the Timers/Quick Add area.
- Home quick-actions strip (item 5, amended): `⏱ timers` links here too.
- Per-entity timer routes stay routable (bookmarks, stats).

### DB layer

Ranking "recently-timed first" and the combined recent-logs list need
recent-N-per-type reads. Follow AGENTS.md db rules: add scan-then-pull
queries with limits to `db/queries.clj` if the existing helpers would fetch
all logs — `fetch-completed-logs` (`timer/routes.clj:80-89`) does
fetch-all-then-take today (predates the rule); don't copy that pattern into
the new combined page, and consider migrating it while there.

## Validation

- [x] `just lint-fast` per edit; `just check` before commit.
- [x] E2E: from `/app/timers` — type to filter, start a project timer
      (verify no form page), see it under Active with elapsed time, stop it
      (verify return to `/app/timers`), open its edit link from recent logs.
      *(passed 2026-07-27: `e2e/scripts/test-timers-workspace.ts`, also in CI)*
- [x] E2E: repeat start/stop for reading + meditation types. *(same script;
      meditation covers the form-fallback-then-one-tap path — passed)*
- [x] E2E: smoke test passes with the new sidebar link target. *(passed after
      fixing a stale `/app/crud/exercise-block` entry that predated this work
      — smoke had been failing since the session>set>line rename)*
- [x] Tap count: home → running project timer ≤ 3 taps, zero form loads
      (sidebar `⏱ timers` → Start = 2 taps).
- [ ] Screenshots before/after (`SCREENSHOT_PHASE`): `/app/timers` mobile +
      desktop; a per-entity page (unchanged except start POST + stop
      redirect). *(after-state captured via e2e runs; "before" needs a
      checkout of the prior commit)*
- [x] 30s HTMX poll still refreshes elapsed times on the combined page
      (`GET /app/timers/active` returns the self-refreshing fragment).

## Scope

**Out:** fuzzy matching (substring only; fuzzy lives with backlog's Fuzzy
Search Component), cross-type overlap stats on the workspace
(`timer-overlap-metrics.md`), exercise/boulder session screens (custom flows,
not timer-system entities), inline parent creation from the workspace
(`timer-dashboard-inline-create.md` — but note its empty-state case gets
less painful here since all types share one page), keyboard palette
(`backlog.md`).

## Context

- Hub page to rebuild: `src/tech/jgood/gleanmo/app/timers.clj`.
- Generic timer machinery: `src/tech/jgood/gleanmo/timer/routes.clj` —
  `timer-config` (38-68), `fetch-active-timers` (70-78),
  `fetch-completed-logs` (80-89), `start-timer-card` form-bounce to replace
  (250-270), `active-timer-card` (272-301), per-entity page composition
  (303-349), `/active` fragment (351-368), `stop-timer` (370-391),
  `gen-routes` (393+).
- Cross-type active-timer iteration to share: `app/overview.clj:371-395`.
- Sidebar/home integration points: `qol-quick-actions.md` items 1 and 5.
- Client-side filter precedent/consumer: `roadmap/search-filter.md`.
- DB query rules: AGENTS.md Database layer section (scan-then-pull,
  no fetch-all-then-filter).

## Notes

Resolved design defaults (revisit only if they fail in use): substring
filter is client-side; empty-filter ordering is recency-of-use; per-entity
pages survive for stats; direct-start POSTs share CRUD mutation logic;
`redirect` param whitelisted to `/app/timer` prefixes.

Sequencing: part of the QOL batch — see execution order in
`qol-quick-actions.md` Notes. Supersedes that doc's original three-deep-link
sidebar treatment (amended 2026-07-27).

Implementation (2026-07-27): shipped in one pass together with
`qol-quick-actions.md` item 2 (stop redirect) and the item-1 sidebar timers
link (full sidebar reorder still pending in that doc). Deviation from spec:
`{<rel-key>, <beginning>}` alone doesn't satisfy every timer schema —
meditation-log also requires location-id/position/guided/interrupted — so
`POST /app/timers/start/<entity>` fills remaining required fields from the
most recent completed log of the type (booleans default false, time-zone
from the user), and 303s to the CRUD new-form with the parent preselected
only when that fails (first-ever log of a type). `fetch-completed-logs` and
the stop handler's timer lookup were migrated off fetch-all-then-filter
(`recent-completed-timer-logs` in `db/queries.clj`, `get-entity-for-user`).

Post-ship additions (2026-07-27, user-requested): the workspace's empty
state links to creating parents (was a dead end on a fresh DB), and
**location pickers** — the user sets a location on every timer log. Two
surfaces, both Choices.js selects (chips were built first with user
sign-off, then replaced the same day: the user has tens of locations with
varying label lengths, so a chip row didn't scale — searchable selects are
also the AGENTS.md house component): (1) a `📍` select inside a single start
form (rows submit via per-button `formaction` + `parent-id` button values),
options recency-ordered via `location-usage` with last-used preselected —
zero interaction in the common case — stamping `<entity>/location-id` on
every start and riding along to the fallback new-form; (2) a compact select
on `active-timer-card` that `hx-post`s on change to
`POST /app/timers/location/<entity-str>` (clear via `:db/dissoc`; the empty
"no location" option only renders for optional location fields —
meditation-log's is required), answering 204 + `HX-Trigger:
refresh-active-timers`, which both active-timer fragments listen for
alongside the 30s poll. The timer filter input deliberately sits outside the
start form to avoid implicit submission starting a timer on Enter.

Second iteration (2026-07-27, user-requested): the workspace picker became a
**persisted global current-location setting** (`:user/current-location-id`,
optional, on the user schema). The select sits above the filter, associated
with the start form via the HTML `form` attribute, and `hx-post`s changes to
`POST /app/timers/current-location`. Starts prefer the submitted picker
value (even blank) over the setting — the persistence post is async and
could lose a race with an immediate Start — while per-entity-page starts
(no picker) read the setting. Switching to a real location **while timers
run** returns a confirm prompt: `POST /app/timers/relocate` ends every
active timer now and starts continuation logs at the new location
(`relocate-timer!` copies all schema fields except beginning/end/notes —
notes belong to the finished segment); Dismiss just keeps the setting.
Cold start: the setting is authoritative, so a user who has never set it
sees "no location" regardless of log history.
