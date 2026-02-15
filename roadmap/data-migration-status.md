---
title: "Data Migration Status (Airtable + Other Sources)"
status: active
description: "Tracker for Airtable backfills and remaining imports"
tags: []
priority: medium
created: 2026-02-02
updated: 2026-02-15
---

# Data Migration Status (Airtable + Other Sources)

## Work Unit Summary
- Problem / intent: Track Airtable backfills and remaining imports so we can fully exit Airtable.
- Constraints: Preserve lineage fields and deterministic IDs; document each import run.
- Proposed approach: Prioritize exercise migration, then move through pain, mood, reading, bouldering, and project time logs.
- Open questions: Which import should follow exercise, and what is the default validation threshold?

## Current State
- Habits & habit logs: fully migrated from Airtable. Legacy runner left only for reference (`dev/airtable/activity.clj`).
- BM logs: fully migrated; helper code in `dev/repl.clj` is archival/reference.
- Medication: fully migrated (2026-02-15). 23 medications + 1,305 logs written to prod via `m001-airtable-import-medications`.
- Symptom (pain/mood): schema defined in `symptom_schema.clj`, CRUD routes not wired, no migration code.
- Exercise: schema defined in `exercise_schema.clj` (needs type fix), no routes, no migration code.
- Tasks & Projects: CRUD live in-app; historical data lives in other apps/spreadsheets, no migration code.
- Not represented in app yet: reading, bouldering.
- Priority: define and implement entities incrementally, then port data one by one.

## Next Actions
- Pick the next entity to migrate (symptom/pain/mood recommended).

## Recommended Approach: Define-Then-Port Per Entity (Ascending Complexity)
Define schema → wire CRUD → build/run migration for each entity sequentially. This provides:
- Short feedback loops between schema implementation and real Airtable data validation
- Ability to adjust patterns based on actual data quirks
- Historical data visible sooner, providing motivation
- UI/UX pattern refinement before tackling complex entities

## Recommended Sequence

### 1. Medication (DONE)
- Migrated 2026-02-15 via CLI migration task `m001-airtable-import-medications`

### 2. Symptom (pain/mood)
- Schema defined, wire CRUD for `symptom-episode` and `symptom-log`
- Build ingester, export from Airtable, run migration
- **Estimated time: 1-2 days**

### 3. Task
- CRUD live, design migration from existing sources
- Build ingester, export data, run migration
- **Estimated time: 1-2 days**

### 4. Project
- CRUD live, export time logs from current apps
- Build ingester, run migration
- **Estimated time: 1 day**

### 5. Reading
- Define `book` and `reading-session` schemas per `roadmap/reading-tracker.md`
- Wire CRUD routes + metadata lookup UI (optional: defer lookup to phase 2)
- Build ingester, export from Airtable, run migration
- **Estimated time: 2-3 days**

### 6. Exercise (most complex, defer to end)
- Fix schema type error + address log/set/rep confusion
- Wire CRUD for 4 entities (exercise, session, set, rep)
- Build ingester, export from Airtable, run migration
- **Estimated time: 3-4 days**

### 7. Bouldering (complex, defer to end)
- Define schema (check Airtable structure first)
- Wire CRUD routes
- Build ingester, export from Airtable, run migration
- **Estimated time: 2-3 days**

## Total Estimated Time: 10.5-16.5 days (2-3 weeks)

### Breakdown
| Entity | Status | Estimated Time |
|--------|--------|----------------|
| Medication backfill | DONE (2026-02-15) | - |
| Symptom | Schema defined | 1-2 days |
| Task | CRUD live | 1-2 days |
| Project | CRUD live | 1 day |
| Reading | Needs schema | 2-3 days |
| Exercise | Schema exists, complex | 3-4 days |
| Bouldering | Needs schema, complex | 2-3 days |
| **Total** | | **10.5-16.5 days** |

### Assumptions
- Full-time focused work (no context switching)
- Airtable exports go smoothly
- Migration patterns from `dev/repl/airtable/core.clj` scale well
- Minimal UI/UX rework needed for simpler entities (symptom, task, project)
- Exercise and bouldering may require schema iteration based on data quirks

## Remaining Ingestions
- Symptom (pain/mood): Schema defined, wire CRUD + build ingester.
- Exercise: Fix schema (type, rep entity) per `roadmap/exercise.md`, then create Airtable ingester.
- Reading: Schema + ingester; export reading base (books + sessions).
- Bouldering: Schema + ingester; export Airtable table.
- Project time logs: Not in app; current data lives in other apps.

## Shared Guidance
- Use `dev/repl/airtable/core.clj` helpers (deterministic UUIDs, timestamp parsing, enum mapping, EDN readers).
- Keep Airtable trace fields (:airtable/id, :airtable/created-time, :airtable/ported-at) on imported records for lineage.
- Document each import run (sources, counts, timestamps) so backfills are repeatable.
- Migration CLI pattern established with `m001-airtable-import-medications` — reuse for future entities.
