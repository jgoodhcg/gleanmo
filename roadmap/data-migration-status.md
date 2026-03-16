---
title: "Data Migration Status (Airtable + Other Sources)"
status: active
description: "Tracker for Airtable backfills and remaining imports"
tags: []
priority: medium
created: 2026-02-02
updated: 2026-03-16
---

# Data Migration Status (Airtable + Other Sources)

## Work Unit Summary
- Problem / intent: Track Airtable backfills and remaining imports so we can fully exit Airtable.
- Constraints: Preserve lineage fields and deterministic IDs; document each import run.
- Proposed approach: Prioritize symptom migration (unified with pain), then mood, reading, bouldering, and project time logs.
- Open questions: Which import should follow symptom, and what is the default validation threshold?

## Current State
- Habits & habit logs: fully migrated from Airtable. Legacy runner left only for reference (`dev/airtable/activity.clj`).
- BM logs: fully migrated; helper code in `dev/repl.clj` is archival/reference.
- Medication: **COMPLETE** (2026-03-10). 23 medications, 1,305 logs. Injection site and notes included in final migration.
- Reading: **READY TO RUN** (2026-03-16). Schema defined (book-source, book, reading-log), CRUD wired, timer + viz routes live, e2e tests passing, migration task `m002-airtable-import-reading` updated for book-source entities and location-id references. Needs Airtable data download then dry-run.
- Symptom (unified with pain): schema defined in `symptom_schema.clj`, CRUD routes not wired, no migration code. Airtable pain data will port as symptom-log with type `:pain`.
- Mood: separate entity per `roadmap/mood.md`, needs schema definition.
- Exercise: schema defined in `exercise_schema.clj` (needs type fix), no routes, no migration code.
- Tasks & Projects: CRUD live in-app; historical data lives in other apps/spreadsheets, no migration code.
- Not represented in app yet: bouldering.
- Priority: define and implement entities incrementally, then port data one by one.

## Next Actions
- ~~Remediate medication migration (injection site, notes)~~ — DONE (2026-03-10).
- ~~Define reading schema, wire CRUD, build Airtable ingester for books + reading-logs~~ — DONE (2026-03-16).
- **NEXT: Download Airtable reading data, dry-run m002, validate artifacts, then write to dev DB.**
  ```bash
  clj -M:dev download-airtable -k $API_KEY -b $BASE_ID -n books
  clj -M:dev download-airtable -k $API_KEY -b $BASE_ID -n reading-log
  clj -M:dev migrate m002-airtable-import-reading \
    --books-file airtable_data/books_*.edn \
    --logs-file airtable_data/reading_log_*.edn \
    --email <your-email> --target dev --dry-run
  ```
  Review `tmp/migrations/reading/` artifacts, then re-run without `--dry-run`.
- Wire CRUD for symptom-episode and symptom-log, then build Airtable ingester (pain → :type :pain).

## Recommended Approach: Define-Then-Port Per Entity (Ascending Complexity)
Define schema → wire CRUD → build/run migration for each entity sequentially. This provides:
- Short feedback loops between schema implementation and real Airtable data validation
- Ability to adjust patterns based on actual data quirks
- Historical data visible sooner, providing motivation
- UI/UX pattern refinement before tackling complex entities

## Recommended Sequence

### 1. Medication — COMPLETE
- Initial migration 2026-02-15 via CLI migration task `m001-airtable-import-medications`
- Remediation completed 2026-03-10: injection site and notes backfilled
- 23 medications, 1,305 logs total

### 2. Symptom (unified with pain)
- Schema refactored 2026-02-21; pain unified into symptom-log with `:pain` and `:chronic-pain` types
- Wire CRUD for `symptom-episode` and `symptom-log`
- Build ingester, export Airtable pain table, run migration (maps to `:type :pain`)
- **Estimated time: 0.5-1.5 days**

### 3. Mood (separate entity)
- Define mood-log schema per `roadmap/mood.md`
- Wire CRUD, build ingester, export from Airtable
- **Estimated time: 0.5-1.5 days**

### 4. Task
- CRUD live, design migration from existing sources
- Build ingester, export data, run migration
- **Estimated time: 0.5-1.5 days**

### 5. Project
- CRUD live, export time logs from current apps
- Build ingester, run migration
- **Estimated time: 0.5-1 day**

### 6. Reading — READY TO RUN
- Schema defined (book-source, book, reading-log) with e2e tests passing
- CRUD, timer, viz routes all wired; book-source entity added for user-defined acquisition sources
- `reading-log/location` changed from enum to `reading-log/location-id` (references location entity)
- Migration task `m002-airtable-import-reading` handles: location reconciliation (matches existing DB locations), book-source extraction from "from" field, book/label defaulting from title
- **Remaining: download Airtable data, dry-run, validate, write**

### 7. Exercise (most complex, defer to end)
- Fix schema type error + address log/set/rep confusion
- Wire CRUD for 4 entities (exercise, session, set, rep)
- Build ingester, export from Airtable, run migration
- **Estimated time: 2-4 days**

### 8. Bouldering (complex, defer to end)
- Define schema (check Airtable structure first)
- Wire CRUD routes
- Build ingester, export from Airtable, run migration
- **Estimated time: 1-2.5 days**

## Current Calibrated Estimate (2026-02-16)

- **Execution estimate (entity work only): 6-13.5 focused days**
- **End-to-end estimate (including validation/cleanup/context-switch buffer): 8.5-16.5 focused days**
- **Most likely total:** ~12 focused days

### Breakdown
| Entity | Status | Estimated Time |
|--------|--------|----------------|
| Medication backfill | DONE (2026-02-15) | - |
| Symptom (incl. pain) | Schema defined | 0.5-1.5 days |
| Mood | Needs schema | 0.5-1.5 days |
| Task | CRUD live | 0.5-1.5 days |
| Project | CRUD live | 0.5-1 day |
| Reading | Schema defined, CRUD wired | 0.5-1 day (migration run) |
| Exercise | Schema exists, complex | 2-4 days |
| Bouldering | Needs schema, complex | 1-2.5 days |
| **Execution subtotal** | | **6-13.5 days** |
| Cross-cutting buffer (validation, cleanup, context switching) | - | 2.5-3 days |
| **End-to-end total** | | **8-14 focused days** |

## Estimate Baseline (for comparison)

- **Initial estimate (2026-02-02):** 10.5-16.5 days
- **Calibrated estimate (2026-02-16):** 8-14 focused days
- **Recalibrated estimate (2026-02-21):** 8.5-16.5 focused days (mood split out as separate entity)
- **Reason for calibration:** Medication migration paid one-time setup costs (first CLI migration pattern, REPL dead ends, initial model definition), so subsequent migrations should reuse the established pattern and move faster. Mood split from symptom adds 0.5-1.5 days.

## Actuals Tracking (Fill As Work Completes)

| Entity | Estimate | Actual Focused Days | Completed On | Notes |
|--------|----------|---------------------|--------------|-------|
| Symptom (incl. pain) | 0.5-1.5 days | TBD | TBD | |
| Mood | 0.5-1.5 days | TBD | TBD | |
| Task history | 0.5-1.5 days | TBD | TBD | |
| Project time logs | 0.5-1 days | TBD | TBD | |
| Reading | 1-2 days | TBD | TBD | |
| Exercise | 2-4 days | TBD | TBD | |
| Bouldering | 1-2.5 days | TBD | TBD | |
| Cross-cutting buffer | 2.5-3 days | TBD | TBD | |
| **Total (remaining)** | **8.5-16.5 focused days** | TBD | TBD | |

### Assumptions
- Full-time focused work (no context switching)
- Airtable exports go smoothly
- Migration patterns from `dev/repl/airtable/core.clj` scale well
- Minimal UI/UX rework needed for simpler entities (symptom, task, project)
- Exercise and bouldering may require schema iteration based on data quirks

## Remaining Ingestions
- Reading: **Download data, dry-run m002, validate, write.** All code is ready.
- Symptom (includes Airtable pain): Schema refactored, wire CRUD + build ingester. Pain maps to `:type :pain`.
- Mood: Needs schema + ingester; export from Airtable.
- Exercise: Fix schema (type, rep entity) per `roadmap/exercise.md`, then create Airtable ingester.
- Bouldering: Schema + ingester; export Airtable table.
- Project time logs: Not in app; current data lives in other apps.

## Shared Guidance
- Use `dev/repl/airtable/core.clj` helpers (deterministic UUIDs, timestamp parsing, enum mapping, EDN readers).
- Keep Airtable trace fields (:airtable/id, :airtable/created-time, :airtable/ported-at) on imported records for lineage.
- Document each import run (sources, counts, timestamps) so backfills are repeatable.
- Migration CLI pattern established with `m001-airtable-import-medications` — reuse for future entities.
