---
title: "Blood glucose on symptom logs"
status: draft
description: "Record blood glucose alongside the other vitals on symptom-log, and allow a log that is only a vitals reading"
created: 2026-09-18
updated: 2026-09-18
tags: [schema, symptom, health]
priority: medium
---

# Blood glucose on symptom logs

## Intent

Blood glucose readings have nowhere to go. `symptom-log` already carries vitals
(temperature, heart rate, blood pressure, SpO2), so glucose belongs beside
them rather than in a new entity.

## Specification

- Add `:symptom-log/blood-glucose {:optional true} :number` and a unit field
  (`[:enum :mg-dl :mmol-l]`), following the `temp` / `temp-unit` pair.
- Add a symptom type for a reading taken without a symptom, so a plain glucose or blood-pressure check does not
  have to pretend to be a symptom. Name to be decided — see open questions.
- Vitals render together in the form; the new field sits with them.

## Validation

- [ ] Schema accepts a glucose reading with and without a unit
- [ ] Form renders and round-trips the new fields (`data-original-value` set)
- [ ] Existing symptom logs still validate

## Scope

No charts, CGM import, or meal correlation — those follow only if logging
shows the data gets used.

## Context

- `src/tech/jgood/gleanmo/schema/symptom_schema.clj` — `symptom-type-enum`
  and the vitals fields
- AGENTS.md "Changing a field that already has data" — this only adds fields,
  so no migration

## Open Questions (draft only)

- Name of the non-symptom type (`:vitals-check`? `:measurement`?), or should a
  vitals-only log leave type empty instead?
- Default unit: mg/dL?
- Fasting / post-meal context as a field, or left to notes?
