---
title: "Pinned goals on the home screen"
status: active
description: "Pin goals so they lead the goals page and show as compact progress cards on the home screen"
created: 2026-09-25
updated: 2026-09-25
tags: [goals, dashboard]
priority: medium
---

# Pinned goals on the home screen

## Intent

Checking a goal currently means opening `/app/goals` and finding it in the table.
Pinning lets the goals that matter now lead the goals page and appear on the home screen.

Dogfood note, verbatim (2026-09-25):
"Do I want the graph and goal picker to be on the same page? Can I pin a goal? Can I see a small representation on the Home Screen?"

## Specification

### Pinning

- Add `[:goal/pinned {:optional true :hide true} :boolean]` to `schema/goal_schema.clj`; absent means unpinned, never stored `false`.
- Pin/Unpin sits with Edit, Archive, and Delete in the selected-goal header, and posts through the validated goal mutation with a 303 back to the page.
- Pinned goals sort first in the goals table and the first pinned goal is the default selection when no `?goal=` is given.
- Archiving a goal leaves the flag alone; archived goals are excluded from the home card regardless.

### Home card

- A "Goals" section on the overview renders one compact card per pinned goal: label, progress bar, logged of target, and the live "needed today" line from [090](./090-goal-live-today.md) (weekly: this week's gap).
- Each card links to `/app/goals?goal=<id>`.
- The section loads as a lazy HTMX fragment like the other overview sections, and renders nothing when no goal is pinned.
- `goals/dashboard` gains a way to compute only the given goal ids, so the home page never computes unpinned goals.

## Validation

- [x] Pin and unpin persist; pinned goals sort first and are the default selection.
- [x] Home shows pinned goals only, and nothing without pins; archived pinned goals are hidden.
- [x] Home card numbers match the goals page for the same goal.
  Step 12 of `e2e/scripts/test-goals.ts` reads the card's headline and gap and finds both on the goal's card.
- [ ] Home overview latency is unchanged with no pins; check the fragment's cost with several pins against [013](./013-dashboard-performance.md).
  No pins, measured 2026-09-25 against a local e2e account: the fragment took 0.055 ms per call and returned an empty body.
  It loads after `#overview-recent` swaps in, so it does not delay the timeline's request.
  Pinned, measured on a two-goal e2e account only: about 8 ms, the same as the goals page's own dashboard call for those goals.
  Open: the cost with several pins on real data, which needs the user's account.
- [x] E2E: pin a goal, see it on home, follow the link to its selected card.
- [x] Before/after series capture per AGENTS.md (2026-09-26T01-20-03Z baseline, 2026-09-26T01-47-43Z after).
  The series account has no pins, so its home frame is unchanged; `goals-11-home-pinned` and `goals-12-home-pinned-mobile` from the e2e run show the section.

## Decisions (2026-09-25)

- The goals page layout is unchanged; see Notes for the open layout question.
- `goals/dashboard` takes the pinned goal documents through `:goals` rather than ids: the fragment already holds them from the pinned lookup, so passing ids would read them twice.
- The home section sits between Running now and the activity timeline.
  Its placeholder is inside the `#overview-recent` fragment, so it loads after that fragment rather than in parallel with it.

## Implementation notes (2026-09-25)

- `schema/goal_schema.clj`: `:goal/pinned`, dissoc'd on unpin.
- `db/queries.clj` `pinned-goals-for-user`: reads the sparse flag, then `fetch-entities-by-ids`.
- `app/goals.clj`: `set-pinned!` (`POST /app/goal/:id/pin`, 303 back), pinned-first sort, a "Pinned" tag in the table, and `pinned-goals-fragment` (`GET /app/goals/pinned`).
- Cards show the label, logged of target, a progress bar, and one status line: needed today or ahead for dated goals, the gap this week for weekly goals, the gap for best performances, today's amount for open-ended goals, and completion state for books.
- Tests: `pinned-goals-test` in `test/tech/jgood/gleanmo/test/goals/dashboard_test.clj`, step 12 of `test-goals.ts`, and a 200 check on the fragment in `test-smoke.ts`.

## Notes

- Unresolved: the dogfood note asked "Do I want the graph and goal picker to be on the same page?"
  The table and chart already share `/app/goals`, and a row click swaps the selected card.
  If the note was about scrolling past the table on mobile to reach the chart, the options are the selected card above the table on mobile, or a compact goal select replacing the table on small screens.
  Pinned-first ordering and default selection shorten that scroll for pinned goals; the layout itself is left as it was until the user decides.

## Scope

No reordering of pins beyond label order.
No new chart on the home card; the progress bar is the representation.

## Context

- `src/tech/jgood/gleanmo/app/goals.clj` `goals-page`, `actions`, `goals-table`.
- `src/tech/jgood/gleanmo/goals/dashboard.clj` `dashboard`.
- `src/tech/jgood/gleanmo/app/overview.clj` fragments (`stats-fragment` etc.) and `overview-shell`.
- Depends on [090](./090-goal-live-today.md) for the "needed today" line; can ship first with logged of target only.
