---
title: "Live today on goals"
status: active
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

"Today's logged amount" is what today added to the total (logged − completed-day amount), so needed today reaches zero exactly when the required rate after today equals the rate at day start.

Dashboard:

- The summary shows "Needed today" as the primary live statistic, e.g. "25 of 40 min · 15 to stay on pace", or "✓ 10 min ahead".
- Average and required show both values: the completed-day baseline and the live value, with the change today brought.
- The chart adds a dashed projection from Now to the end date at the live average, with a readout of where it lands ("At this rate: 34 h, 85% of target") or the date the target would be reached.
- A second projection runs at recent pace: the total over the last 14 days, today included, divided by 14.
  It shows speeding up or slowing down, and appears only once 14 days have completed, because a shorter window equals the live average.
  14 is a chosen default (`calc/recent-pace-days`), not a derived value; change it there.
- The goals table is unchanged; the live values are on the selected goal's card.

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

- [x] Calc tests: live average, needed today (behind, exactly on pace, ahead), required after today on the last day and after reaching the target, zero completed days.
  `live-today-test` and `live-today-weekly-and-open-ended-test` in `test/tech/jgood/gleanmo/test/goals/calc_test.clj`.
- [x] Logging a record today changes needed today, live average, and required after today; completed-day values stay the same until local midnight.
- [x] Weekly goals render no projection, pace line, or required statistics.
  `chart-clock-markers-test` now asserts a weekly chart has only the Now marker.
- [x] Extend `e2e/scripts/test-goals.ts`: log today, reload, and assert the live values moved.
  Step 11 also asserts the unchanged completed-day average and the weekly card.
- [x] Before/after series capture per AGENTS.md (2026-09-26T01-20-03Z baseline, 2026-09-26T01-40-27Z after).
  The series account has no goals, so those frames show only the page subtitle; `goals-09-live-today` and `goals-10-weekly` from the e2e run show the cards.

## Implementation notes (2026-09-25)

- `goals/calc.clj` `numeric-progress` adds `:today-amount`, `:live-average`, `:above-average?`, `:needed-today`, `:required-after-today`, `:required-change`, `:projection`, and `:recent-projection`.
  Pace values (`:required`, `:ratio`, `:pace-days`, `:even-pace`) now apply to dated goals only.
- Projections are hidden once the target is reached; a recent-pace line can otherwise dwarf the whole chart.
- `app/goals.clj`: `today-line` under the headline total carries `data-*` attributes with the raw values for tests.
  Weekly goals show "Still to go this week", "Today", and "Next threshold", and their chart shows a target line instead of the pace lines.

## Scope

No stored progress values; everything stays computed per request.
No change to how totals, windows, or time zones are counted.
Comparisons and records belong to [092](./092-goal-comparisons-records.md); pinning and the home card to [091](./091-pinned-goals.md).

## Context

- `src/tech/jgood/gleanmo/goals/calc.clj` `numeric-progress` (completed-day average and required around line 296).
- `src/tech/jgood/gleanmo/app/goals.clj` `numeric-summary`, `numeric-chart`, `numeric-row`.
- The completed-day rule is specified in [081](./081-goals-dashboard.md) §4; this unit adds live values beside it rather than replacing it.
