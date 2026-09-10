---
title: "Relation Defaults"
status: draft
description: "Curated per-related-entity defaults that prefill log fields — a meditation's usual position, a medication's fixed dose"
tags: [crud, schema, forms, qol]
priority: medium
created: 2026-09-06
updated: 2026-09-06
---

# Relation Defaults

## Intent

Many log fields are nearly constant *per related entity* rather than per user: a
given meditation is almost always logged in one position, a medication almost
always at one dose. Re-selecting that value every log is a wasted click, but the
field can't move to the parent entity because the exceptions are real data (the
one lying-down meditation, the rotated injection site). A **user-curated default
stored on the parent** removes the click in the common case while keeping every
log's own value authoritative.

## Specification

- Log schemas can declare that a field's new-form default is sourced from the
  related entity picked by another field on the same form. Declarative, via a
  schema property, so the generic CRUD form layer resolves it and each new use
  is a schema edit — no per-entity code:

  ```clojure
  ;; on the log schema field
  [:meditation-log/position
   {:crud/priority 3
    :crud/default-from {:via :meditation-log/type-id
                        :attribute :meditation/default-position}}
   [:enum :sitting :lying :walking :standing :moving]]

  ;; on the parent entity — optional, user-curated
  [:meditation/default-position {:optional true}
   [:enum :sitting :lying :walking :standing :moving]]
  ```

- **Prefill-only**: the default is applied when rendering a new-log form. The
  stored log always carries the actual submitted value; nothing is filled at
  write time, so analysis never has to know defaults exist.
- Default resolution happens client-side on parent select (and server-side on
  initial render) so switching the parent re-prefills dependent fields.
- Parents with no default stored behave exactly as today.

## Suggested First Items

| Log field | Parent | Default attribute(s) | Notes |
|---|---|---|---|
| `meditation-log/position` | meditation | `meditation/default-position` | The motivating case |
| `meditation-log/guided` | meditation | `meditation/default-guided` | A given meditation is essentially always guided or not |
| `medication-log/dosage` + `unit` | medication | `medication/default-dosage`, `medication/default-unit` | Dose is fixed per med; injection-site rotation is the counterexample that keeps the fields on the log |
| `reading-log/format` | book | `book/default-format` | Subtlety: when `book/formats` is a singleton the default is derivable — store nothing |
| `exercise-line/weight-unit` | exercise | `exercise/default-weight-unit` | An exercise is always logged in one unit |

## Existing Defaulting Mechanisms (Do Not Duplicate)

The app already has three ad-hoc defaults; this unit adds a fourth, distinct
mechanism and should not replace them:

- **Last-value prefill** — workout entry form prefills the last reps/weight
  per exercise (`src/tech/jgood/gleanmo/app/workout.clj`).
- **Recency default** — timer start preselects the most recently used location
  (`location-usage` in `src/tech/jgood/gleanmo/app/timers.clj`); the boulder
  problem picker defaults to the last problem attempted.
- **Static fallbacks** — `default-reps`, `default-distance-unit`, etc.

Why stored beats last-value here: one outlier log (a single walking meditation)
poisons the next last-value default; a curated default survives outliers.

## Non-goals

- `meditation-log/location-id` and `project-log/location-id` — already served
  by the global current-location picker; a per-parent default would fight it.
- No automatic default learning/updating from logged values in v1 — defaults
  are edited by hand on the parent.
- Not a general "remember last value everywhere" mechanism.

## Validation

- [ ] Unit tests for default resolution (parent selected / changed / has no default / default absent)
- [ ] `just lint-fast` on touched schema and form files
- [ ] E2E: new meditation-log form pre-fills position after selecting a meditation with a default
- [ ] E2E: submitting without touching the pre-filled field stores the value on the log like a hand-picked one
- [ ] Screenshots before/after on the affected form(s)

## Scope

**In scope:**
- `:crud/default-from` schema property + generic form-layer resolution
- Parent default attributes for the first-items list above
- New-form prefill (server render and parent-change)

**Out of scope:**
- Edit-form prefill (an existing log's own value always wins)
- Write-time filling or backfilling historical logs
- Timer flow prefill beyond what the shared form layer provides for free

## Context

- Schemas: `src/tech/jgood/gleanmo/schema/meditation_schema.clj`,
  `medication_schema.clj`, `reading_schema.clj`, `exercise_schema.clj`
- Form rendering: `src/tech/jgood/gleanmo/crud/forms/inputs.clj`
- Precedents: `app/workout.clj` (last-value), `app/timers.clj` (recency)
- Schema conventions in `AGENTS.md` — new parent attributes are optional and
  must not affect existing documents

## Open Questions

- [ ] Prefill-only vs write-time fill — recommend prefill-only; confirm.
- [ ] Naming convention for parent attributes: `<parent>/default-<field>` —
      confirm or adjust (`<parent>/usual-<field>`?).
- [ ] Should the form visually mark defaulted values (so "I accepted a default"
      is distinguishable at a glance), or keep pre-fill silent?
- [ ] For dosage+unit: one composite default or two independent fields?
- [ ] Should `book/formats` singleton-derivation skip storing
      `book/default-format` entirely, or is a stored override still wanted
      for multi-format books?
- [ ] Ordering of the first items — meditation position first as the pilot,
      rest batched after the mechanism proves out?
