---
title: "Cronometer Integration"
status: draft
description: "Pull nutrition and biometric data from Cronometer into Gleanmo for LLM context and unified health tracking"
created: 2026-04-24
updated: 2026-04-24
tags: [integration, nutrition, biometrics, llm-context, external-data]
priority: low
---

# Cronometer Integration

## Intent

Pull nutrition and body composition data from Cronometer into Gleanmo so it can serve as LLM-ready context for daily training and nutrition decisions. Cronometer is the source of truth for food logging (data flows in from Fitbit and Oura already); Gleanmo becomes the unified data layer that combines nutrition with sleep, exercise, mood, and other tracked metrics.

The primary use case is not visualization — it's making structured nutrition data available alongside other quantified-self data so an LLM can reason about "given what I ate, how I slept, and how I trained, what should I do today?"

## Specification

### Data Ingestion

- **Food diary entries**: Individual foods/meals with timestamps, macros (protein, fat, carbs, calories), and serving info
- **Body composition**: Weight, body fat %, lean mass tracked in Cronometer
- **Historical backfill**: Import existing large history from Cronometer

### Integration Method (TBD)

Two paths to research:
1. **Cronometer Developer API** — Gold accounts may have API access; requires applying for developer credentials. Preferred if available for automated sync.
2. **CSV export + import** — Cronometer supports exporting foods, biometrics, and daily summaries as CSV. Lower-tech but reliable for initial implementation.

Recommendation: start with CSV import for historical backfill and proof-of-concept; layer in API sync once API access is secured.

### Data Model

New schemas needed:
- `nutrition-log` — individual food entries with timestamp, food name, serving, macros
- `body-composition-log` — weight, body fat %, lean mass entries

Both schemas should follow standard field ordering with `cronometer/*` lineage fields for traceability (analogous to `airtable/*` fields):
```clojure
[:cronometer/id           {:optional true} :string]   ; Cronometer entry ID
[:cronometer/exported-at  {:optional true} :instant]   ; when data was exported/synced
```

### Views

- **Food diary log**: Searchable log/table view of nutrition entries, filterable by date range
- No charting needed initially — the data serves LLM context first, visualization later

### LLM Context Layer

- Nutrition data available alongside other Gleanmo data for daily context summaries
- Format TBD — could be structured context copied out, API endpoint, or in-app AI integration

## Validation

- [ ] Schema defined and registered for `nutrition-log` and `body-composition-log`
- [ ] CSV parser handles Cronometer export format(s)
- [ ] Historical data imported without errors
- [ ] Food diary log view renders imported data
- [ ] Data queryable through standard `db/queries.clj` layer
- [ ] Entity appears on entities dashboard

## Scope

### In scope
- Read-only data import from Cronometer
- Food diary log view
- Body composition log
- Historical backfill
- Cronometer lineage metadata on imported entities

### Out of scope
- Writing nutrition data back to Cronometer
- Replacing Cronometer as the food logging interface
- Real-time API sync (deferred until API access secured)
- Nutrition charts/visualizations (defer to generic-viz work unit)
- Micronutrient tracking (initial scope is macros + calories)
- Daily summary/scorecard views

## Context

- Cronometer Gold account (paid) — may unlock API access
- Fitbit and Oura already feed data into Cronometer
- User hasn't applied for developer API access yet
- Cronometer CSV exports are confirmed available
- Existing schema conventions: `src/tech/jgood/gleanmo/schema/`
- CRUD system: `src/tech/jgood/gleanmo/crud/`
- DB layer: `src/tech/jgood/gleanmo/db/queries.clj`, `src/tech/jgood/gleanmo/db/mutations.clj`
- Related work unit: `generic-viz.md` for future charting

## Open Questions

- [ ] Does Cronometer Gold include API access, or is separate developer approval needed?
- [ ] What does a Cronometer CSV export actually look like (columns, format, date ranges)?
- [ ] Should nutrition-log be a single schema or separate schemas per meal type?
- [ ] How to handle Cronometer's composite foods / recipes in the import?
- [ ] Should imported Cronometer data be deduplicated on re-import?
- [ ] Where does the LLM context layer live — is this a separate work unit?
- [ ] How large is the historical dataset (approximate record count / date range)?

## Notes

### User Interview Summary (2026-04-24)

**Primary motivation**: Feed context into LLMs to make decisions about training and nutrition day to day. Secondary: unified dashboard, data backup/ownership.

**Data of interest**: Individual foods/meals (not just daily aggregates), body composition (weight, body fat, lean mass).

**Visualization**: Food diary log as searchable table — charts not the priority.

**Integration method**: TBD. CSV export confirmed available. API access unexplored — user has Gold but hasn't applied for developer credentials. Recommend starting with CSV import.

**History**: Large historical dataset in Cronometer — wants to backfill.

**Priority**: Low / exploratory. Slot as draft, promote when ready.

**Read/write**: Cronometer stays source of truth. Read-only import into Gleanmo for now.
