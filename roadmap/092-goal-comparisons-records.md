---
title: "Goal comparisons and records"
status: draft
description: "Compare a goal with last week and last year, show its best and worst periods of all time, and count weekly streaks"
created: 2026-09-25
updated: 2026-09-25
tags: [goals, visualization]
priority: medium
---

# Goal comparisons and records

## Intent

A goal says how the current period is going, but not how it stands against the user's own history.
The prior-year panel exists but always reads "No comparable history", because the coverage metadata it requires was never built.

Dogfood notes, verbatim:

- 2026-09-25: "I want to see more stats and comparisons to last week and last year and best of all time and worst of all time."
- 2026-09-16 (from [081](./081-goals-dashboard.md)): "I just want to see how I'm doing and the gap and a streak."

## Specification

All values use the goal's scope: source, measurement, relation filter, and saved time zone.

### Comparisons

- This week vs. last week (Monday resets, as weekly goals do), including today.
- This goal period vs. the same dates last year, using the existing calendar alignment in `goals/calc.clj` `comparison-window`.
- Last year's cumulative series on the chart, with the year-selection controls already listed as an 081 follow-up.

### Records

- Best and worst day, and best and worst week, of all time, each with its date.
- Where the current period ranks among past periods of the same length.
- Worst excludes periods before coverage starts, and periods with no logs (otherwise empty weeks always win).
- Best-performance goals show the all-time best and when it was set; they have no worst.

### Streaks (weekly goals)

- Current and longest run of consecutive weeks that reached the target.
- The current week counts once reached; until then the streak shows as continuing from last week.

### Loading

- Computed for the selected goal only, and loaded as a fragment after the page renders, the way target suggestions load (`GET /app/goals/suggestion`).
- All-time reads need a bounded daily projection per goal scope in `db/queries.clj`, reusing the `daily-values` calculation; check the query shape per AGENTS.md.

## Validation

- [ ] Calc tests: week alignment across a year boundary and DST, best/worst ignoring empty and uncovered periods, streak across a partial week.
- [ ] Coverage: comparisons stay hidden for sources without established coverage.
- [ ] Query plan and latency against production-sized fixtures (see 081's synthetic audit).
- [ ] E2E: selected goal shows comparisons and records; switching goals updates them.
- [ ] Before/after series capture per AGENTS.md.

## Scope

Absorbs 081's "Historical comparisons: coverage entry, prior-year chart series, and year-selection controls" follow-up and the streak half of its weekly-goal feedback.
No stored records or streaks; computed per request.

## Context

- `src/tech/jgood/gleanmo/goals/calc.clj` `comparison-window`, `covered?`, `comparison`, `history`.
- `src/tech/jgood/gleanmo/app/goals.clj` `comparison-panel`, `rhythm-panel`.
- `src/tech/jgood/gleanmo/goals/suggest.clj` already reads 365 days of history per goal scope.
- 081 §4: "Do not infer complete coverage from the earliest log or manufacture prior-year data."

## Open Questions (draft only)

- How coverage is established. This blocks `ready`. Options:
  1. The user records a "complete since" date once per source (compatible with 081's rule).
  2. Each source's first record starts its coverage. Simple, and mostly true since the Airtable exit, but it overturns 081's rule and must be recorded as a decision.
- Confirm "worst" means the worst period with at least one log.
