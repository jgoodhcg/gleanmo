---
title: "Pinned goals on the home screen"
status: draft
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

- [ ] Pin and unpin persist; pinned goals sort first and are the default selection.
- [ ] Home shows pinned goals only, and nothing without pins; archived pinned goals are hidden.
- [ ] Home card numbers match the goals page for the same goal.
- [ ] Home overview latency is unchanged with no pins; check the fragment's cost with several pins against [013](./013-dashboard-performance.md).
- [ ] E2E: pin a goal, see it on home, follow the link to its selected card.
- [ ] Before/after series capture per AGENTS.md.

## Scope

No reordering of pins beyond label order.
No new chart on the home card; the progress bar is the representation.

## Context

- `src/tech/jgood/gleanmo/app/goals.clj` `goals-page`, `actions`, `goals-table`.
- `src/tech/jgood/gleanmo/goals/dashboard.clj` `dashboard`.
- `src/tech/jgood/gleanmo/app/overview.clj` fragments (`stats-fragment` etc.) and `overview-shell`.
- Depends on [090](./090-goal-live-today.md) for the "needed today" line; can ship first with logged of target only.

## Open Questions (draft only)

- The table and chart already share `/app/goals`; a row click swaps the selected card.
  Was the "same page" note about scrolling past the table on mobile to reach the chart?
  If so, options: selected card above the table on mobile, or a compact goal select replacing the table on small screens.
