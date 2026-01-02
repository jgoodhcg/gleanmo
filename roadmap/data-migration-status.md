# Data Migration Status (Airtable + Other Sources)

## Work Unit Summary
- Status: active
- Problem / intent: Track Airtable backfills and remaining imports so we can fully exit Airtable.
- Constraints: Preserve lineage fields and deterministic IDs; document each import run.
- Proposed approach: Prioritize exercise migration, then move through pain, mood, reading, bouldering, and project time logs.
- Open questions: Which import should follow exercise, and what is the default validation threshold?

## Current State
- Habits & habit logs: fully migrated from Airtable. Legacy runner left only for reference (`dev/airtable/activity.clj`).
- BM logs: fully migrated; helper code in `dev/repl.clj` is archival/reference.
- Medication: entities and logging are live in-app; Airtable backfill still pending.
- Not represented in app yet: exercise, pain, mood, reading, bouldering, project time logs (project timers exist, but historical logs live in other apps/spreadsheets).
- Priority: exercise migration is the first target for exiting Airtable.

## Next Actions (medication backfill)
1. Export Airtable `medication-log` table: `clj -M:dev download-airtable -k $API_KEY -b BASE_ID -n medication-log` → `airtable_data/...edn`.
2. Confirm namespace UUIDs in `dev/repl/airtable/medication.clj` (use stable values).
3. In REPL against prod, run runner in `dev/repl/airtable/medication_runner.clj`:
   - `write-medications-to-db` first, then `write-medication-logs-to-db`.
   - Ensure validation `passed == total`; abort if not.
4. Record the run: file name, record counts, timestamp, user email used.

## Remaining Ingestions to Design/Build
- Exercise: Fix schema (type, rep entity) per `roadmap/exercise.md`, then create Airtable ingester (deterministic IDs per record, enum normalization, validation). Export exercise tables to `airtable_data/` first.
- Pain: Define schema (include Airtable trace fields), add ingester; export pain table to `airtable_data/`.
- Mood: Same pattern—schema + ingester, then import from `airtable_data/`.
- Reading: Schema + ingester; export reading base (books + sessions) to `airtable_data/`.
- Bouldering: Schema + ingester; export Airtable table.
- Project time logs: Not in app; current data lives in other apps. Plan to export to spreadsheets, then write an importer (deterministic IDs, optional Airtable/lineage metadata).

## Shared Guidance
- Use `dev/repl/airtable/core.clj` helpers (deterministic UUIDs, timestamp parsing, enum mapping, EDN readers).
- Keep Airtable trace fields (:airtable/id, :airtable/created-time, :airtable/ported-at) on imported records for lineage.
- Document each import run (sources, counts, timestamps) so backfills are repeatable.***
