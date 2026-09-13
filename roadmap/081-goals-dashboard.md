---
title: "Goals dashboard"
status: active
description: "Combine goal totals, calendar-week frequency, and individual achievements in one dashboard."
created: 2026-09-12
updated: 2026-09-13
tags: [goals, visualization, mockup]
priority: medium
---

# Goals dashboard

## Intent

Build a production-shaped standalone mockup before implementing goal storage and queries.
Combine the Claude table and palette with the Codex spacing and labels.

## Specification

- Place a sortable, searchable, height-bounded goals table above the selected goal.
- Preserve required/current rate ratio, even-pace difference, and next threshold columns.
- Attach one main graph and two smaller graphs within one card.
- Scope all three graphs to the selected goal.
- Support several prior ghost years with stable, distinct colors across controls, lines, and bars.
- Show actual progress, target, and previous-year comparison together.
- Include interval duration, exercise reps, calendar-week sessions, timestamp counts, bouldering attempts/duration/sessions, and individual weight/duration achievements.
- Reset weekly targets each Monday in the goal timezone.
- Use duration for elapsed time and period for calendar units.
- Keep implementation notes outside the dashboard.
- Separate duration, count, and best-performance measurements from dated, weekly, and open-ended timing.
- Include weekly reading duration, sessions for one meditation, and open-ended duration for one book.
- Show open-ended totals without deadline pace; use recent activity in their lower-right panel.
- Defer book completion presentation, page progress, nonfiction filters, and arbitrary attribute goals.
- Defer tasks per project, moon phases, and astrology.

## Scope

This iteration creates a standalone HTML mockup and a proposed data contract.
Production schemas, routes, database changes, and deployment remain outside this iteration.
The user waived the application visual-series baseline for this standalone mockup on September 12.

## Validation

- Exercise sorting, search, category filters, empty results, selection, and keyboard interaction in a local browser.
- Inspect desktop and mobile screenshots.
- Check cumulative, weekly, and achievement calculations against the displayed series.
- Confirm the main graph fits within a 1440 × 900 desktop viewport.
- Document schema gaps and aggregation semantics beside the mockup.

## Context

- Current iteration: `mockups/2026-09-motivating-dashboards/codex/mockup-codex-09-goals-dashboard.html`.
- Version 09 semantics and validation live in adjacent `goals-09-*.md` files.
- Previous mockup delivered: `mockups/2026-09-motivating-dashboards/codex/mockup-codex-08-goals-dashboard.html`.
- Data proposal and browser validation are recorded in adjacent `goals-08-*.md` files.
- Production implementation remains pending design review.
- `mockups/2026-09-motivating-dashboards/claude-code/mockup-claude-code-01-first-dashboard.html`
- `mockups/2026-09-motivating-dashboards/codex/mockup-codex-07-unified-dashboard.html`
- `src/tech/jgood/gleanmo/schema/exercise_schema.clj`
- `src/tech/jgood/gleanmo/schema/bouldering_schema.clj`
