---
title: "Live today on goals"
status: draft
description: "Goal pace statistics react to today's logs beside the completed-day baseline, with a projection at the current rate"
created: 2026-09-25
updated: 2026-09-25
tags: [goals, visualization]
priority: medium
---

# Live today on goals

## Intent

The goals dashboard is checked right after logging something, and nothing but the total moves.
Average, required rate, and ratio use completed days only (`goals/calc.clj` `numeric-progress`), so today's work is invisible to them until tomorrow.
Keep the completed-day numbers as the stable baseline and add live values beside them, so the needle moves when something is logged.

Dogfood notes, verbatim (2026-09-25):

- "Goal average of completed days plus average considering today same with rates"
- "I look at the graph to see what I've done today but the needle needs to move when I do something"
- "Also I want to see what today did. Did my rate go up? Was I above average? What would it look like if I kept this rate every day? What's the projection based on fitting the curve of data points? Did I bring down the required from today? By how much?"

## Specification

### Dated totals

Add these to `numeric-progress`, leaving the existing completed-day values unchanged:

| Value | Definition |
|---|---|
| Average including today | logged ÷ (completed days + 1); today counts as a whole day |
| Needed today | required at day start − today's logged amount; negative means ahead |
| Required after today | (target − logged) ÷ (remaining days − 1); absent on the last day or once reached |
| Required change | required at day start − required after today |
| Above average today | today's logged amount compared with the completed-day average |

Counting today as a whole day means the live average only rises as the day goes on; it never drifts down with the clock.

Dashboard:

- The summary shows "Needed today" as the primary live statistic, e.g. "25 of 40 min · 15 to stay on pace", or "✓ 10 min ahead".
- Average and required show both values: the completed-day baseline and the live value, with the change today brought.
- The chart adds a dashed projection from Now to the end date at the live average, with a readout of where it lands ("At this rate: 34 h, 85% of target") or the date the target would be reached.
- The goals table's rate column may gain the live value; keep the table scannable on mobile.

### Open-ended totals

Show the live average and today against the average. No required rate or projection, as today.

### Weekly totals

Weekly progress resets Monday with no carry-forward, so a catch-up rate is meaningless.
Remove the even-pace line, the required line, the "Projection start" marker, and the required/ratio/pace statistics for weekly goals.
Show this week's amount, the gap to the target, and today's amount.
This resolves the "Weekly goals show a pace and a projection the user cannot act on" item in [081](./081-goals-dashboard.md); the streak half moves to [092](./092-goal-comparisons-records.md).

### Best performances

Unchanged.

## Validation

- [ ] Calc tests: live average, needed today (behind, exactly on pace, ahead), required after today on the last day and after reaching the target, zero completed days.
- [ ] Logging a record today changes needed today, live average, and required after today; completed-day values stay the same until local midnight.
- [ ] Weekly goals render no projection, pace line, or required statistics.
- [ ] Extend `e2e/scripts/test-goals.ts`: log today, reload, and assert the live values moved.
- [ ] Before/after series capture per AGENTS.md.

## Scope

No stored progress values; everything stays computed per request.
No change to how totals, windows, or time zones are counted.
Comparisons and records belong to [092](./092-goal-comparisons-records.md); pinning and the home card to [091](./091-pinned-goals.md).

## Context

- `src/tech/jgood/gleanmo/goals/calc.clj` `numeric-progress` (completed-day average and required around line 296).
- `src/tech/jgood/gleanmo/app/goals.clj` `numeric-summary`, `numeric-chart`, `numeric-row`.
- The completed-day rule is specified in [081](./081-goals-dashboard.md) §4; this unit adds live values beside it rather than replacing it.

## Open Questions (draft only)

- Whole-day vs. pro-rated today in the live average. Proposed: whole day (above).
- "Fitting the curve": a linear fit on a cumulative total is close to the average rate, and higher-order fits extrapolate wildly on a few weeks of data.
  Proposed instead: a second projection at recent pace (last 7 or 14 days), which shows speeding up or slowing down. Confirm, and pick the window.
