---
title: "Blood glucose on symptom logs"
status: draft
description: "Record blood glucose alongside the other vitals on symptom-log, and allow a log that is only a vitals reading"
created: 2026-09-18
updated: 2026-10-12
tags: [schema, symptom, health]
priority: medium
---

# Blood glucose on symptom logs

## Intent

Blood glucose readings have nowhere to go. `symptom-log` already carries vitals
(temperature, heart rate, blood pressure, SpO2), so glucose belongs beside
them rather than in a new entity.

## Specification

- Add `:symptom-log/blood-glucose {:optional true} :number` and
  `:symptom-log/blood-glucose-unit {:optional true} [:enum :mg-dl :mmol-l]`,
  following the `temp` / `temp-unit` pair. Same names as
  `biometric-log/blood-glucose` in
  [056-cronometer-integration.md](./056-cronometer-integration.md), so the two
  are comparable.
- Give every existing vital a unit field, per the "every measured value has a
  sibling `<field>-unit`" rule (AGENTS.md, schema conventions, 2026-10-12):
  `bp-systolic-unit` and `bp-diastolic-unit` (`[:enum :mmhg]`),
  `heart-rate-unit` (`[:enum :bpm]`), `spo2-unit` (`[:enum :percent]`). All new
  and optional — no deprecation, no migration; existing values stay valid. The
  form should default each to its only member.
- Add a symptom type for a reading taken without a symptom, so a plain glucose or blood-pressure check does not
  have to pretend to be a symptom. Name to be decided — see open questions.
- Vitals render together in the form; the new field sits with them.

Routine readings (a morning weight, a scheduled glucose check) now have a home
in `biometric-log`, imported from Cronometer (056). Symptom logs record health
events; the two are deliberately unrelated (decided 2026-10-12). That weakens
the case for the "reading without a symptom" type above — revisit it once
`biometric-log` exists.

## Validation

- [ ] Schema accepts a glucose reading with and without a unit
- [ ] Unit fields exist for every vital and default to their single member
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
