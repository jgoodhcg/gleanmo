---
title: "Live today on goals"
status: active
description: "Goal pace statistics react to today's logs beside the elapsed-day baseline, with a projection at the current rate"
created: 2026-09-25
updated: 2026-10-06
tags: [goals, visualization]
priority: medium
---

# Live today on goals

## Intent

The goals dashboard is checked right after logging something, and nothing but the total moves.
Average, required rate, and ratio use elapsed days only (`goals/calc.clj` `numeric-progress`), so today's work is invisible to them until tomorrow.
Keep the elapsed-day numbers as the stable baseline and add live values beside them, so the needle moves when something is logged.

Dogfood notes, verbatim (2026-09-25):

- "Goal average of completed days plus average considering today same with rates"
- "I look at the graph to see what I've done today but the needle needs to move when I do something"
- "Also I want to see what today did. Did my rate go up? Was I above average? What would it look like if I kept this rate every day? What's the projection based on fitting the curve of data points? Did I bring down the required from today? By how much?"

## Specification

### Dated totals

Add these to `numeric-progress`, leaving the existing elapsed-day values unchanged:

| Value | Definition |
|---|---|
| Average including today | logged ÷ (elapsed days + 1); today counts as a whole day |
| Needed today | required at day start − today's logged amount; negative means ahead |
| Required after today | (target − logged) ÷ (remaining days − 1); absent on the last day or once reached |
| Required change | required at day start − required after today |
| Above average today | today's logged amount compared with the elapsed-day average |

Counting today as a whole day means the live average only rises as the day goes on; it never drifts down with the clock.

"Today's logged amount" is what today added to the total (logged − elapsed-day amount), so needed today reaches zero exactly when the required rate after today equals the rate at day start.

Dashboard:

- The summary shows "Needed today" as the primary live statistic, e.g. "25 of 40 min · 15 to stay on pace", or "✓ 10 min ahead".
- Average and required show both values: the elapsed-day baseline and the live value, with the change today brought.
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

- [x] Calc tests: live average, needed today (behind, exactly on pace, ahead), required after today on the last day and after reaching the target, zero elapsed days.
  `live-today-test` and `live-today-weekly-and-open-ended-test` in `test/tech/jgood/gleanmo/test/goals/calc_test.clj`.
- [x] Logging a record today changes needed today, live average, and required after today; elapsed-day values stay the same until local midnight.
- [x] Weekly goals render no projection, pace line, or required statistics.
  `chart-clock-markers-test` now asserts a weekly chart has only the Now marker.
- [x] Extend `e2e/scripts/test-goals.ts`: log today, reload, and assert the live values moved.
  Step 11 also asserts the unchanged elapsed-day average and the weekly card.
- [x] Before/after series capture per AGENTS.md (2026-09-26T01-20-03Z baseline, 2026-09-26T01-40-27Z after).
  The series account has no goals, so those frames show only the page subtitle; `goals-09-live-today` and `goals-10-weekly` from the e2e run show the cards.

## Implementation notes (2026-09-25)

- `goals/calc.clj` `numeric-progress` adds `:today-amount`, `:live-average`, `:above-average?`, `:needed-today`, `:required-after-today`, `:required-change`, `:projection`, and `:recent-projection`.
  Pace values (`:required`, `:ratio`, `:pace-days`, `:even-pace`) now apply to dated goals only.
- Projections are hidden once the target is reached; a recent-pace line can otherwise dwarf the whole chart.
- `app/goals.clj`: `today-line` under the headline total carries `data-*` attributes with the raw values for tests.
  Weekly goals show "Still to go this week", "Today", and "Next threshold", and their chart shows a target line instead of the pace lines.

## Dogfood notes (2026-10-04): the shipped line went unnoticed

User, verbatim (dogfood list, written after this unit was built): "On a goal I want to know what I have to do today to get to the pace that finishes the goal".
That is exactly "Needed today" above, already on the selected goal's card.
Asked about it, the user said: "I did not notice 090".

So the user check this unit is waiting on has its first result: the feature exists but does not register.
Interpretation: a discoverability and hierarchy problem, not a missing calculation.
The line sits under the headline total as one of several statistics, and the answer to "what do I do today" should be the first thing read on the card.
Candidates to mock up and review: lead the card with the needed-today sentence; give it its own visual weight (neon accent, larger type); repeat it on the pinned home card and the met-pace chips in [091](./091-pinned-goals.md); check it is visible without scrolling on mobile (see the mobile item in [081](./081-goals-dashboard.md)).
Keep this unit active until a revised placement is something the user notices unprompted.

## Dogfood notes (2026-10-05): clutter, catch-up amount, nearer thresholds

Observed in production on a dated rep goal (Exercise lines · Pullup · Reps): 245 of 5,000 reps by September 16, 2027; 19 elapsed days at 12.9 reps/day; 13.7 reps/day required from today with 347 days left; "Next threshold 255 reps to 500 reps"; today 0 reps.
Nothing on `dev` had changed this card since production when the note was filed.

User, verbatim: "feels like cluttered text and I'm scanning for the revelant number. I can easily detect the pace needed from today onward. That's cool. I can also see what my pace is for "completed" (should soon say elapsed) days."

User, verbatim: "how many reps do I need to do to make my elapsed day average equal my ongoing pace needed? In other words I want to know how many it would take to make up for my gap."

User, verbatim: "at least for the two rep goals I have the next thresholds feel too far away to be motivating. In this example I think it would be better to see like ~5 more reps to 260. Not sure how to programmatically dervie something like that in a an abstract sense."

### Interpretation

**Clutter.** The headline number and "Required from today" are read at a glance; the secondary lines under each statistic are not ("19 elapsed days · with today 12.3 reps/day (-0.64)", "347 days left · after today 13.7 reps/day (+0.04) · 1.06× your average").
Every statistic carries a sub-sentence of mixed numbers, so the eye has no single place to land.
This is the same hierarchy problem as the 2026-10-04 note above (needed today going unnoticed), from the other side: too many equal-weight numbers.
Candidates to mock up: one number per statistic with its sub-line cut to a label; live deltas ("with today", "after today", the ratio) moved behind a disclosure or dropped where they rarely change; a single sentence that leads the card.

**Catch-up amount (new value).** The gap between where the user is and where they would be at the pace they now need.
Candidate definition: catch-up = required rate × elapsed days − amount logged before today.
In the example: 13.7 × 19 − 245 ≈ 15 reps.
A nearly equivalent and simpler definition: the deficit against the even-pace line (target ÷ total days × elapsed days − logged), here 5,000 ÷ 366 × 19 − 245 ≈ 15 reps.
The two differ in general (logging changes the required rate), so pick one and state it; the even-pace deficit is stable through the day and already drawn on the chart as "Even pace".
Negative means ahead; show "N ahead of pace" then.
It answers a different question from "Needed today": needed today is today's share; catch-up is the accumulated debt.
Weekly and open-ended goals have no pace, so no catch-up.

**Nearer thresholds.** `goals/calc.clj` `threshold-step` defaults to a nice 1/2/5 step near a tenth of the target, so a 5,000-rep goal steps by 500, roughly 36 days of work at the current pace.
A motivating threshold should be a short distance away in days of effort, not a fixed fraction of the target.
Candidate derivations to evaluate:

1. Scale the step to the pace: a nice step near a few days of required rate (13.7 × 7 ≈ 96 → 100; × 3 ≈ 41 → 50), so the next threshold is always days, not weeks, away.
2. The even-pace line itself: the next threshold is "back on pace", which in the example is ≈ 260 — the user's own example — and merges with the catch-up amount.
3. The next nice round number above the total at a finer granularity (nice step of the remaining distance ÷ ~10, or of the daily rate) — 245 → 250 → 260.

Option 2 coincides with the user's "~5 more reps to 260" only loosely (the gap is ≈ 15); option 1 or 3 gives a "~5 to 250"-style line.
A user-set `goal/threshold-step` still wins over any default.
Decide by mocking up the card with each and checking which one the user reads unprompted.

Approach: mock up and review before specifying, together with the needed-today placement above and the "elapsed days" wording in [081](./081-goals-dashboard.md).

## Design direction (2026-10-06): cumulative goals get the ruler

Mockups: `mockups/2026-10-goal-card/claude-code/` (01–06). The user picked the zoomed ruler (03 → 04 → 05 → 06).

User, verbatim: "I think we need to distinguish periodic from cumulative goals. We are working on a visualization just for cumulative goals and maybe those with end dates."
User, verbatim: "i think this redesign will use only visualizations that are relevant to the type of goal."
User, verbatim, on numbers over sentences: "I'm not sure I like the natural language assessment like \"✓ On even pace, 2 reps ahead.\" I kind of just want to see the relvant data and determine that myself."

Decided so far:

- **Two families.** Cumulative (a total over a dated window; extra today covers a missed day) and periodic (each day/week stands alone; hit rate and streaks matter, catch-up is meaningless). This redesign covers cumulative dated totals only; other goal types get their own views as the user reaches them. Streaks for periodic goals stay in [092](./092-goal-comparisons-records.md).
- **Ruler.** Window in units of one day of the pace needed: from a day before this morning's total to 2.5 days past now, pulling in marks within 4 days; farther marks sit at the edge with their distance. Marks: keep pace today, even pace tonight, next round number (≈3 days of pace, whole numbers for counts), target when in range. The window always contains the current total, so logging slides and widens it.
- **Full bar stays,** with the ruler's window outlined on it.
- **No sentences, no divider, nothing collapsed.** Key numbers left, aligned like a let binding (today, avg / elapsed day, pace needed, gap at day start); quieter context beside them; Log bottom right (below the key numbers on phones).
- **Projection chart stays,** shown in full below the numbers.
- The "gap closed" mark was dropped: on long goals it sits a rep from "keep pace today", because the required rate spreads the gap over the remaining days.

## Implementation plan (2026-10-06, signed off)

Target: mockup 06 (list, goal page, sidebar pins) with 05's decimal handling. Covers this unit's design direction, the list/page split and sidebar pins in [091](./091-pinned-goals.md), and the "elapsed days" item in [081](./081-goals-dashboard.md).

1. **Calc** (`goals/calc.clj`, tests in `calc_test.clj`).
   - Rename `completed-days`/`completed-logged`/`completed-amount` to `elapsed-*` everywhere (code, data keys, tests, e2e, UI copy, roadmap prose per 081). Book "completed" stays.
   - Add `:gap-at-day-start` (elapsed amount − even rate × elapsed days; negative = behind) and `:even-tonight` to `numeric-progress` for dated totals.
   - Add `ruler`: a pure function of a progress map returning `{:lo :hi :minor :major :next-major :marks [...]}` in canonical units, porting the mockup's `compute`/`rulerHTML` rules (window, 4-day pull-in, 0.3-day pad, clash rule, target cap, edge marks with distance). Integer measurements step by whole numbers; duration totals step in minutes. Tests cover the Pullup example, the 05 stress cases (tiny, far behind, far ahead, decimals, near the end), and reached/last-day guards.
2. **Goals list** (`/app/goals`): no detail card. Groups "Cumulative · with an end date" (dated totals), "Periodic · each period stands alone" (weekly), and "Other" (open-ended, best, book) with the current kinds' headline values. One CSS grid template shared by every row so columns line up across groups; pin star per row; archived section stays. The table's sort/filter controls go.
3. **Goal page** (one goal per page). Dated totals: crumb, title, source, Pin/Edit/⋯ actions; full bar with the ruler window outlined; ruler (server-rendered markup, small JS only for label lanes); key numbers `today`, `avg / elapsed day`, `pace needed`, `gap at day start` aligned as a `dl`; quiet context (period, days left, even pace, with today, ruler range); Log bottom right, below the keys on phones; the ECharts projection chart in full. No sentences, no divider, nothing collapsed. Every other goal type renders its current detail card on this page unchanged. `?goal=<id>` on `/app/goals` 303s to the goal page.
4. **Sidebar pins**: pinned, unarchived goals nested under "goals" as plain links to their pages, from `pinned-goals-for-user` (one sparse-flag lookup, no progress computed). The mockup's `today/pace` figure beside each pin is dropped. Home pinned cards unchanged.
5. **Tests**: calc tests above; `dashboard_test` updated for renamed keys; `test-goals.ts` reworked for list → page navigation and the new data attributes; smoke/manifest gain the goal page route.

Decided with the user (2026-10-06):

- Goal page URL: `/app/goal/:id`; `/app/goals?goal=<id>` 303s there.
- Log opens the source's log screen, the same destination as its sidebar quick action.
- Sidebar pins are links only. User, verbatim: "The pins should just be a query for a goal and it's a link to the goal id why do we have to do a progress computation to render teh sidebar?"

## Implementation notes (2026-10-06)

- `goals/calc.clj`: `ruler` (a pure port of mockup 05/06's `compute`/`rulerHTML`), `:even-rate`, `:even-tonight`, `:gap-at-day-start`, and `recent-weeks` for the periodic list rows. Tests: `gap-and-even-tonight-test`, `ruler-test` (the Pullup example worked by hand, a big day, near the end, target in reach, far-off marks, minutes), `recent-weeks-test`.
- Ruler units are minutes for duration totals and the measurement's own unit otherwise; counts and minutes step by whole numbers. Key numbers use the same units, so a 100 h goal reads "pace needed 16.4" (minutes).
- `app/goals.clj`: `goals-list` (cumulative, periodic, then "No deadline, best, and books" for every other kind, which renders the home card's figures), `cumulative-page`, and `goal-page` at `GET /app/goal/:id`. One grid template serves every row, so columns line up across groups. Old `/app/goals?goal=<id>` links 303 to the page; an archived, hidden, or missing goal 303s to the list.
- The cumulative page shows exactly the mockup: title, bar, ruler, key numbers, quiet context, Log, projection chart. The activity strip, prior-year panel, and "What counts" details are not on it; they stay on the detail card the other kinds keep.
- The projection chart's legend carries the landing values ("At your average · 34 h (85%)"), replacing the readout sentence under the chart. "Required at day start" is now "Needed from today".
- Ruler label lanes are laid out client-side in `main.js` (`laneRulers`), as in the mockup; the server renders every position.
- Sidebar pins (`shared/pinned-goal-links`) use `goals.dashboard/visible-goals`, so a goal over a sensitive habit stays out of the sidebar when sensitive mode is off. The current goal's link is lit.
- Pin stars on list rows post `from=list` and return to the list.

## Validation (2026-10-06 redesign)

- [x] Calc tests for the ruler, gap, even tonight, and recent weeks.
- [x] Render tests: `goals-list-render-test` (group order, `0/14`, `−15`, `347 days left`), `cumulative-page-render-test` (12.9 / 13.7 / −15, Log to the workout screen, no sentences, nothing collapsed), `visible-goals-test`.
- [x] `test-goals.ts` reworked: list groups and aligned columns, row navigation, the `?goal=` redirect, the ruler page's key numbers and marks, Log below the keys on a phone and beside them on desktop, day-start values holding while today moves, sidebar pins, and pin/unpin from the list.
- [x] `test-smoke.ts`: a missing goal page redirects to the list.
- [ ] User check: the redesign reads at a glance on a real goal (the 2026-10-04 and 2026-10-05 notes above).

## Scope

No stored progress values; everything stays computed per request.
No change to how totals, windows, or time zones are counted.
Comparisons and records belong to [092](./092-goal-comparisons-records.md); pinning and the home card to [091](./091-pinned-goals.md).

## Context

- `src/tech/jgood/gleanmo/goals/calc.clj` `numeric-progress` (elapsed-day average and required around line 296).
- `src/tech/jgood/gleanmo/app/goals.clj` `numeric-summary`, `numeric-chart`, `numeric-row`.
- The elapsed-day rule is specified in [081](./081-goals-dashboard.md) §4; this unit adds live values beside it rather than replacing it.
