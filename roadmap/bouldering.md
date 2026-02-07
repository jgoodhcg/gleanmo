---
title: "Bouldering Log Roadmap"
status: draft
description: "Climbing sessions and problem attempts with Airtable backfill"
tags: []
priority: medium
created: 2026-02-02
updated: 2026-02-07
---

# Bouldering Log Roadmap

## Work Unit Summary
- Problem / intent: Capture climbing sessions and problem attempts with Airtable backfill.
- Constraints: Preserve Airtable schema fidelity for import and keep data entry lightweight at the gym.
- Proposed approach: Add session + attempt entities, CRUD flows, then build an Airtable ingester.
- Open questions: What is the final grade enum list and how should gym-specific grade systems map?

Capture climbing sessions and problem attempts (grades, gyms, attempts, sends) with Airtable backfill.

## Objectives
- Reproduce the Airtable schema so historical climbs import cleanly.
- Support both session-level tracking (date, gym, duration, RPE) and per-problem attempts (grade, color, number of tries, send status).
- Enable future analytics (grade pyramid, session frequency, workload).

## Proposed Data Model
```clojure
:boulder-session/id         :uuid
:user/id                    :user/id
:boulder-session/date       :date
:boulder-session/gym        :string
:boulder-session/duration   {:optional true} :duration
:boulder-session/rpe        {:optional true} [:enum :easy :moderate :hard :limit]
:boulder-session/notes      {:optional true} :string

:boulder-attempt/id         :uuid
:boulder-session/id         :boulder-session/id
:boulder-attempt/problem-id {:optional true} :string   ; gym identifier / color
:boulder-attempt/grade      [:enum :v0 :v1 :v2 ... :v13 :project] ; adjust to Airtable list
:boulder-attempt/color      {:optional true} :string
:boulder-attempt/attempts   {:optional true} :int      ; total tries
:boulder-attempt/send?      :boolean
:boulder-attempt/notes      {:optional true} :string

;; Airtable provenance
:airtable/id                {:optional true} :string
:airtable/created-time      {:optional true} :instant
```

## Implementation Plan
1. **Schema + Routes**  
   - Add Malli schemas and CRUD modules for sessions/attempts (similar to exercise session/set).  
   - Provide nested forms or separate create flows (session first, then attempts via link).
2. **Airtable Import**  
   - Export existing bouldering Airtable base.  
   - Build converters for session + attempt tables, ensuring deterministic UUIDs and grade normalization.  
   - Import history before enabling new UI to avoid dual entry.
3. **Visualizations**  
   - Calendar heatmap for sessions (timestamp).  
   - Grade pyramid + send rate charts (future, once data is in).

## Questions
- Do we also track hangboard/campus workouts here or separately?
- Should non-send attempts include per-try timestamps, or is aggregated count enough?
- Any shared taxonomy with exercise timers (locations, RPE scales) worth reusing?
