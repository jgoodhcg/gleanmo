---
title: "Navigation & Logging QOL"
status: ready
description: "Analytics-backed sidebar reorder, unified timer workspace, stop-in-place timers, boulder problem discoverability, and home quick actions"
created: 2026-07-26
updated: 2026-07-27
tags: [ux, navigation, sidebar, timers, analytics]
priority: high
---

# Navigation & Logging QOL

## Intent

Remove the daily-use friction the 28-day Plausible export (2026-06-28 → 2026-07-26,
single user, iOS 56 sessions / Mac 37) makes measurable. The app's real workload
is logging and the project timer; the navigation is ordered around everything
else. Five small changes, each independently shippable, ordered by
effort-to-payoff.

**Evidence (the numbers to beat):**

| Signal | Measurement |
|---|---|
| `/app/timers` hub is a pass-through | 55 views, **2s** avg time on page — every visit is click → scan 3 cards → click again |
| Timer stop forces a full edit-form round trip | **61 distinct** `project-log/edit/<id>` visits, mostly 2–15s (open → save → leave) |
| Top pages are all logging | habit-log/new 137 views, project-log/new 111, timer/project-log 191 (93s avg — a workspace), medication-log/new 79, bm-log/new 54 |
| Sidebar order is inverted vs. usage | Today/Task Focus sit at the top with ~3 combined visits; Quick Add and timers sit below/bottom |
| Home is the #1 entry point | `/app`: 51 unique entrances, 191 views — but renders zero start-an-action affordances |

## Specification

### 1. Sidebar reorder + unified timer link (`app/shared.clj:90-199`)

> Amended 2026-07-27: the original spec here put three per-type timer deep
> links in the sidebar. User feedback: don't bypass the hub — **make the hub
> the workspace** (`unified-timer-page.md`). The sidebar gets one timers
> link instead.

Restructure `side-bar` nav (after home/account, before Calendar/Dashboards):

- **`⏱ timers → /app/timers`** first — the unified timer workspace
  (`unified-timer-page.md`): all running timers across types,
  search-to-start.
- **Quick Add** section second, reordered by measured frequency:
  habit log, project log, medication log, bm log (keep the `show-bm-logs` gate),
  workout, bouldering, symptom log, mood log, meditation log, reading log,
  calendar event, task (full form).
- **Tasks** section (Today, Task Focus) moves below Quick Add.
- Dashboards section: drop its now-redundant `⏱ timers` row.

### 2. Timer stop stays on the timer page (`timer/routes.clj:380-388`)

The stop handler currently 303s to `/app/crud/form/<entity>/edit/<id>?redirect=<timer page>`
"so the user can review notes/details". Change it to return to the page that
issued the stop: honor a `redirect` query param on the stop link
(whitelisted to `/app/timer`-prefixed paths), defaulting to the per-entity
timer page. The unified workspace passes `redirect=/app/timers`. Annotation
stays one tap away: the just-stopped log is the top row of the recent-logs
list (`timer/routes.clj:224-243`), and every row is already an edit link.
`unified-timer-page.md` extends the same principle to **start** (direct POST,
no CRUD new-form bounce).

User-approved behavior change (2026-07-26): review-after-stop becomes opt-in
instead of forced. Check e2e timer flows for assertions on the old
stop→edit redirect and update them.

### 3. Execute `inline-entity-creation.md` Phases 1–2

The spec is already `ready` with its own validation checklist — this item is
just sequencing: Phase 1 (generic single-relationship inline-create, button
path, exercise-line → exercise first, timer-entity fields flagged) and Phase 2
(Choices.js search-empty `+ Create "<text>"` bridge, workout picker
integration, timer-dashboard tactical empty-state fix). Phase 3
(many-relationship, full-form tier) can trail. Treat that file as the source
of truth; do not restate its spec here.

### 4. Boulder problem management discoverability

The screen exists (now `/app/boulder/problems` — renamed 2026-07-28, since
problems are a library reused across sessions rather than a sub-resource of
one) but was only reachable via the gym-screen picker link.

- Add a **Boulder Problems** card to the entities dashboard
  (`app/dashboards.clj:92-95`, next to Boulder Sessions/Attempts, neon-lime)
  pointing at `/app/boulder/problems` (the purpose-built
  retire/restore screen; full CRUD stays reachable from its badges).
- Add a small "problems" link in the boulder session screen header so it's
  reachable without opening the picker.

### 5. Home quick-actions strip (`app/overview.clj` shell)

Extract the frequency-ordered links from item 1 into a shared component in
`app/shared.clj` (e.g. `quick-action-links`) consumed by both the sidebar and
a compact chip/button strip at the top of the overview shell. Home strip shows
only the top actions: ⏱ timers (`/app/timers`), habit log, project log,
medication log, workout, bouldering (bm log behind its flag). Pure links — no
new queries, no dashboard-performance regression.

## Validation

- [ ] `just lint-fast` after each edit; `just check` before commit.
- [ ] Update e2e flows that assert the timer stop→edit redirect; timer flow
      must pass with the new stop-in-place behavior.
- [ ] `e2e/scripts/test-smoke.ts` still passes (sidebar links changed).
- [ ] Before/after screenshots (`SCREENSHOT_PHASE=before|after`): home
      (mobile + desktop), sidebar open (mobile), entities dashboard,
      `/app/timer/project-log` after a stop.
- [ ] Manual: from a phone, time these flows and compare against today —
      start project timer (target: 2 taps from home), stop timer (target:
      1 tap, no page change), log a habit (target: 2 taps from home).
- [ ] Item 3 has its own checklist in `inline-entity-creation.md`.

## Scope

**Out of scope** (documented elsewhere, deliberately not here):

- Usage-weighted/adaptive quick-action ordering — `workflow-optimization.md`.
  This work unit ships a static order from a known 28-day sample.
- "Repeat last" one-tap logging — `workflow-optimization.md`.
- Mobile bottom tab bar — `mobile-tab-bar.md`.
- Command palette — `backlog.md` (Generic Components).
- True inline-create on the timer dashboard picker —
  `timer-dashboard-inline-create.md`.

**Companion work unit (in the batch):** the `/app/timers` rebuild itself is
specced separately in `unified-timer-page.md` — same batch, own validation
checklist.

## Context

- Sidebar: `src/tech/jgood/gleanmo/app/shared.clj:90-199` (Quick Add at
  127-140, dashboards/timers links at 149-156).
- Timers hub being bypassed: `src/tech/jgood/gleanmo/app/timers.clj`.
- Timer stop redirect: `src/tech/jgood/gleanmo/timer/routes.clj:380-388`;
  recent-logs list (post-stop annotation path): `timer/routes.clj:224-243`;
  active-timer edit link: `timer/routes.clj:286-296`.
- Home shell: `src/tech/jgood/gleanmo/app/overview.clj` (renders active
  timers/stats/recent activity; no start-action affordances today).
- Entities dashboard cards: `src/tech/jgood/gleanmo/app/dashboards.clj:80-96`.
- Boulder problems screen route: `src/tech/jgood/gleanmo/app/boulder.clj:728`.
- Analytics source: Plausible CSV export
  `~/Downloads/Plausible export gleanmo.com 28d ` (trailing space in dir
  name); key numbers are embedded above so the export isn't required to
  execute.
- Related vision doc: `workflow-optimization.md` (this work unit is its first
  concrete, analytics-backed slice).

## Notes

Sequencing intent (2026-07-26): this QOL batch executes before the Airtable
prod migration runs — a deliberate detour from the prioritization lens in
`index.md`, since daily-use friction compounds while prod runs are gated on
user-initiated fresh exports. After this ships and is user-tested: prod runs
of m003–m006, then Airtable retirement (see `data-migration-status.md`).

Superseded (2026-07-31): the migration overtook this batch. Items 1, 2, and
the unified timer page shipped; items 3-5 had not, and the m003–m006 prod runs
went ahead anyway once fresh exports were pulled. Nothing here blocks or is
blocked by the migration — the remaining items stand on their own merits.

Suggested execution order: 1 → 4 → 5 (one commit each or one combined), then
2 (touches e2e), then `unified-timer-page.md` (builds on 2's redirect param),
then 3 (largest, has its own spec).

Progress (2026-07-27): item 2 and the item-1 unified timers link (sidebar
`⏱ timers` moved above Quick Add, dropped from Dashboards) shipped with the
`unified-timer-page.md` implementation; e2e timer-stop flow updated there.
Later the same day, **item 1 shipped in full**: Quick Add reordered by
measured frequency (habit, project, medication, bm-gated, workout,
bouldering, symptom, mood, meditation, reading, calendar event, task),
Tasks section moved below Quick Add, everything else retained — validated
by the smoke e2e. Remaining here: items 3–5 (screenshots for item 1 can
ride along with item 5's home strip work).
