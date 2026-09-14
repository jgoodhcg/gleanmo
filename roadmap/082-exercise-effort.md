---
title: "Exercise effort: failure and reps in reserve"
status: draft
description: "Record whether a set reached failure, or how many reps were left, on each exercise line"
created: 2026-09-14
updated: 2026-09-14
tags: [exercise, schema, workout]
priority: medium
---

# Exercise effort: failure and reps in reserve

## Intent

Record how close a set came to failure.
The user wants to note that a set reached failure, or that about one or two more reps were possible.
Today an exercise line records reps, weight, distance, and duration, but not effort.
The idea came up on 2026-09-14 during goals dashboard review ([081](./081-goals-dashboard.md)).
This draft keeps the idea until it is designed.

## Specification

Not decided. The leading candidate:

```clojure
[:exercise-line/reps-in-reserve
 {:optional true, :crud/label "Reps in reserve"}
 :nonnegative-int]
```

- `0` means the set reached failure.
- `1` or `2` means that many more reps were likely possible.
- An absent value means effort was not recorded.

Why a number fits: reps in reserve counts reps, so the spacing between values is real, and averages over it can mean something.
AGENTS.md "Ratings — ordinal enums stay enums" asks for that test before a rating becomes a number.
The `:nonnegative-int` alias from 081 already exists.

Alternatives to decide between:

- An RPE scale (`:number` with `:crud/scale`, for example 6–10 in half steps), where RPE 10 is about zero reps in reserve.
- A boolean such as `:exercise-line/failed`, plus an optional reps-in-reserve value.
- An enum such as `:failure :one-left :two-left :more`.
  It is ordered but not evenly spaced, so it stays an enum under the ratings rule.

## Validation

- [ ] Malli accepts 0 and rejects negative or fractional values.
- [ ] The workout screen records failure (0), a reps-in-reserve value, and "not recorded" as three distinct outcomes.
- [ ] Editing a line keeps, changes, and clears the value; clearing removes the attribute.
- [ ] CRUD forms and lists show the field; an existing line without it still validates.
- [ ] E2E: log a set at failure and a set with two reps in reserve, then reload the workout screen and check both.

## Scope

- Per exercise line first.
  Failure happens to one exercise in one set, so a session or set cannot hold it without losing which exercise failed.
- A session-level overall effort is a separate question.
  `boulder-session/perceived-exertion` is the existing precedent.
- Goals and analysis that use effort (for example, "sets to failure this month") are out of scope until the data exists.

## Context

- Schema: `exercise-line` in `src/tech/jgood/gleanmo/schema/exercise_schema.clj`.
- Entry: `line-fields`, `line-params`, `line-doc`, and `update-line!` in `src/tech/jgood/gleanmo/app/workout.clj`.
- Conventions: AGENTS.md, "Ratings — ordinal enums stay enums"; booleans are past-tense state words without `?`.

## Open Questions (draft only)

- Reps in reserve, RPE, a failure flag, or an enum?
- The workout screen stores zero as "no value" for weight, distance, and duration.
  That rule cannot apply here, because zero means failure.
  What control shows "not recorded" clearly: a segmented control (—, F, 1, 2, 3+), or a stepper with a clear button?
- Should the form prefill effort from the last line of the same exercise, as it does for reps and weight?
  Effort varies from set to set, so prefilling may record the wrong value without notice.
- Are "3+" and larger values worth recording, or is the useful range only 0–2?

## Notes

- Keep the default path fast: logging a set without effort must cost no extra taps.
