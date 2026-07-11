---
title: "Data Migration Status (Airtable + Other Sources)"
status: active
description: "Tracker for Airtable backfills and remaining imports"
tags: []
priority: high
created: 2026-02-02
updated: 2026-05-16
---

# Data Migration Status (Airtable + Other Sources)

## Work Unit Summary
- Problem / intent: Track Airtable backfills and remaining imports so we can fully exit Airtable.
- Constraints: Preserve lineage fields and deterministic IDs; document each import run.
- Proposed approach: Finish Airtable-owned datasets first: symptom/pain, mood, exercise, then bouldering. Defer non-Airtable source imports until Airtable is no longer needed as a system of record.
- Open questions: What is the default validation threshold for each import, and which Airtable exports need to be refreshed before final porting?

## Current State
- Habits & habit logs: fully migrated from Airtable. Legacy runner left only for reference (`dev/airtable/activity.clj`).
- BM logs: fully migrated; helper code in `dev/repl.clj` is archival/reference.
- Medication: **COMPLETE** (2026-03-10). 23 medications, 1,305 logs. Injection site and notes included in final migration.
- Reading: **COMPLETE** (2026-03-21). Production migration successful: 12 book-sources, 27 books, 366 reading-logs, 6 new locations created, 0 failures.
- Symptom (unified with pain): schema + CRUD + viz wired (2026-07-10); no migration code yet. Airtable pain data will port as symptom-log with type `:pain`.
- Mood: schema (circumplex: valence/arousal/stress) + CRUD + viz wired (2026-07-10); no ingester.
- Exercise: schema reworked (exercise-log removed; session → exercise-block (timed, superset-capable) → exercise-set (reps × weight of one exercise)) + CRUD wired + custom workout screen at `/app/exercise/session` (2026-07-10); no ingester.
- Bouldering: boulder-session + boulder-attempt schema + CRUD + viz wired (2026-07-10); no ingester.
- Tasks & Projects: CRUD live in-app; historical data lives in other apps/spreadsheets, no migration code.
- Priority: define and implement Airtable-backed entities incrementally, then port data one by one until Airtable can be retired.

## Next Actions
- ~~Remediate medication migration (injection site, notes)~~ — DONE (2026-03-10).
- ~~Define reading schema, wire CRUD, build Airtable ingester for books + reading-logs~~ — DONE (2026-03-16).
- ~~Download Airtable reading data, dry-run m002, validate artifacts, write to dev DB~~ — DONE (2026-03-18).
- ~~Deploy reading schema changes to production, then run migration on prod~~ — DONE (2026-03-21).
- ~~Wire CRUD/UI for symptom, mood, exercise, bouldering~~ — DONE (2026-07-10). All remaining Airtable-backed entities have schema + CRUD + viz (plus custom workout screen).
- **NEXT: Build Airtable ingesters, one entity at a time: symptom/pain → mood → exercise → bouldering.**

## Recommended Approach: Airtable Exit First
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

### 4. Reading — COMPLETE
- Schema defined (book-source, book, reading-log) with e2e tests passing
- CRUD, timer, viz routes all wired; book-source entity added for user-defined acquisition sources
- `reading-log/location-id` references location entity; location reconciliation matches existing DB locations
- Dev migration successful (2026-03-18): 12 book-sources, 27 books, 364 reading-logs, 0 failures
- Production migration completed (2026-03-21): 12 book-sources, 27 books, 366 reading-logs, 6 new locations, 0 failures
- Airtable lineage fields compliant: `airtable/id`, `airtable/created-time`, `airtable/ported-at`, `airtable/original-location`

### 5. Exercise
- Fix schema type error + address log/set/rep confusion
- Wire CRUD for 4 entities (exercise, session, set, rep)
- Build ingester, export from Airtable, run migration
- **Estimated time: 2-4 days**

### 6. Bouldering
- Define schema (check Airtable structure first)
- Wire CRUD routes
- Build ingester, export from Airtable, run migration
- **Estimated time: 1-2.5 days**

### 7. Project time logs (non-Airtable source)
- CRUD live, export time logs from current apps
- Build ingester, run migration
- **Estimated time: 0.5-1 day**

### 8. Task history (non-Airtable source)
- CRUD live, design migration from existing sources
- Build ingester, export data, run migration
- **Estimated time: 0.5-1.5 days**

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
| Reading | DONE (2026-03-21) | - |
| Exercise | Schema exists, complex | 2-4 days |
| Bouldering | Needs schema, complex | 1-2.5 days |
| Project time logs | CRUD live | 0.5-1 day |
| Task history | CRUD live | 0.5-1.5 days |
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
| Reading | 1-2 days | ~5 days | 2026-03-21 | Schema, CRUD, timer, viz, migration CLI, e2e tests, prod deploy |
| Exercise | 2-4 days | TBD | TBD | |
| Bouldering | 1-2.5 days | TBD | TBD | |
| Project time logs | 0.5-1 days | TBD | TBD | Deferred until Airtable exit is complete |
| Task history | 0.5-1.5 days | TBD | TBD | Deferred until Airtable exit is complete |
| Cross-cutting buffer | 2.5-3 days | TBD | TBD | |
| **Total (remaining)** | **8.5-16.5 focused days** | TBD | TBD | |

### Assumptions
- Full-time focused work (no context switching)
- Airtable exports go smoothly
- Migration patterns from `dev/repl/airtable/core.clj` scale well
- Minimal UI/UX rework needed for simpler Airtable-backed entities (symptom, mood)
- Exercise and bouldering may require schema iteration based on data quirks

## Remaining Ingestions
- ~~Reading~~: **COMPLETE** (2026-03-21). 12 book-sources, 27 books, 366 reading-logs, 6 new locations.
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
