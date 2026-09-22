---
title: "Suggested goal targets"
status: active
description: "The goal editor suggests a slight-stretch target from the user's own history"
created: 2026-09-22
updated: 2026-09-22
tags: [goals]
priority: medium
---

# Suggested goal targets

## Intent

Choosing a target that is a slight stretch needs historical numbers.
The app calculates the suggestion on the server,
so no person or agent must read the history.

## Specification

The goal editor shows a suggested target below the target field.
It uses the same daily calculation as goal progress
(`goals/calc.clj` `daily-values`), with the selected relation filter.
Only completed days count.
The editor says so: "Today does not count." (weekly: "This week does not count.")

- Dated totals: the rate of the last 365 days
  (or since the first record, divided by at least 28 days),
  over the goal length, plus 15%.
  With no end date yet, the goal length is one year from the start date;
  the editor says so, and **Use** fills that end date.
  **Use** never changes an end date that is already set.
- Weekly totals: the 60th-percentile week of the last 52 whole weeks,
  from the first week with activity.
  The editor shows how many weeks reached the target.
- Best performances: the best of the last 365 days, plus 5%, rounded up.
- Open-ended totals and book completion: no suggestion.

Values round to two significant figures in the input unit.
**Use** copies the value into the target field.
The suggestion loads through `GET /app/goals/suggestion` after render
and on each form change, so the editor page does not wait for it.

## Validation

- [x] `tech.jgood.gleanmo.test.goals.suggest-test`
- [x] `just e2e-test goals` step 10 (suggestion shows; Use fills the target)
- [ ] User checks suggestions against own history

## Scope

No stored suggestion, no AI, no change to goal progress.

## Context

- `src/tech/jgood/gleanmo/goals/suggest.clj`
- `src/tech/jgood/gleanmo/app/goal_editor.clj` `suggestion-fragment`
- `roadmap/081-goals-dashboard.md`
