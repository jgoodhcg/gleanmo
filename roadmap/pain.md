---
title: "Pain Log Roadmap"
status: idea
description: "Pain logs with Airtable history and CRUD/viz"
tags: []
priority: medium
created: 2026-02-02
updated: 2026-02-02
---

# Pain Log Roadmap

## Work Unit Summary
- Problem / intent: Track pain logs with Airtable history and a simple CRUD/viz flow.
- Constraints: Mirror Airtable schema first so backfill is lossless.
- Proposed approach: Define schema + CRUD, build an Airtable ingester, then enable generic visualizations.
- Open questions: Should location be a single enum or a set to allow multiple pain sites?

Track subjective pain data (location, intensity, duration, triggers, treatment) with historical Airtable import.

## Goals
1. Mirror the Airtable schema so past entries drop into XTDB before we redesign fields.
2. Provide a lightweight CRUD + visualization flow similar to bm-log/medication-log.
3. Enable analytics (e.g., intensity over time, trigger correlations) once history exists.

## Proposed Schema (first pass)
```clojure
:pain-log/id            :uuid
:user/id                :user/id
:pain-log/timestamp     :instant
:pain-log/location      [:enum :abdomen :head :back :shoulder :hip :other] ; align with Airtable list
:pain-log/intensity     [:enum :none :mild :moderate :severe :incapacitating]
:pain-log/duration      {:optional true} :duration ; or minutes as int
:pain-log/trigger       {:optional true} :string
:pain-log/treatment     {:optional true} :string
:pain-log/notes         {:optional true} :string
;; Airtable provenance
:airtable/id            {:optional true} :string
:airtable/created-time  {:optional true} :instant
```
_Update enums once we inspect the Airtable export._

## Implementation Steps
1. **Schema + CRUD**
   - Add Malli schema + module (routes, forms, viz).
   - Reuse the existing log form components (timestamp picker, enum dropdowns).
2. **Airtable Migration**
   - Export pain table to `airtable_data/`.
   - Build `airtable->pain-log` + `write-pain-logs-to-db` helpers (deterministic UUIDs, enum mapping, validation).
   - Import full history before switching to in-app logging.
3. **Visualization**
   - Attach generic calendar heatmap route (already works for timestamp entities).
   - Plan follow-up charts (intensity histogram, trigger correlation) once data lands.

## Open Questions
- Do we need multi-location per entry (e.g., both abdomen and back)? If so, switch `:pain-log/location` to a set.
- Should intensity remain categorical or capture a numeric 0–10 scale?
- Are there medication cross-links (e.g., “took nortriptyline”) that should reference `:medication-log/id`?
