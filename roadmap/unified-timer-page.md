---
title: "Unified Timer Workspace"
status: ready
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

- [ ] `just lint-fast` per edit; `just check` before commit.
- [ ] E2E: from `/app/timers` — type to filter, start a project timer
      (verify no form page), see it under Active with elapsed time, stop it
      (verify return to `/app/timers`), open its edit link from recent logs.
- [ ] E2E: repeat start/stop for reading + meditation types.
- [ ] E2E: smoke test passes with the new sidebar link target.
- [ ] Tap count: home → running project timer ≤ 3 taps, zero form loads.
- [ ] Screenshots before/after (`SCREENSHOT_PHASE`): `/app/timers` mobile +
      desktop; a per-entity page (unchanged except start POST + stop
      redirect).
- [ ] 30s HTMX poll still refreshes elapsed times on the combined page.

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
