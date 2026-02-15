---
title: "Medication Logging Roadmap"
status: done
description: "Medication logging with Airtable history import"
tags: []
priority: medium
created: 2026-02-02
updated: 2026-02-15
---

# Medication Logging Roadmap

## Work Unit Summary
- Problem / intent: Import Airtable medication history while keeping current CRUD and viz flows stable.
- Constraints: Preserve provenance fields and normalize enums without losing source data.
- Proposed approach: Audit schema enums, seed medication catalog, build converters, and run backfill.
- Open questions: Which Airtable values require new enums vs stored raw strings?

Document the remaining work to import decades of Airtable medication history while keeping the existing CRUD + visualization flows unchanged.

## Status
- COMPLETE (2026-02-15): Airtable medication history fully migrated to production.
  - 23 medications upserted, 1,305 medication logs written (2 batches).
  - 13 rows rejected (missing/blank labels).
  - Unit enum mapping fixed (mcg, capsule, trailing whitespace handling).
  - Migration CLI (`clj -M:dev migrate m001-airtable-import-medications`) supports `--dry-run` flag.
  - Deterministic UUIDs ensure idempotent re-runs.

## Objectives
1. Preserve the full Airtable record set (dose history, notes, injection sites) in XTDB with deterministic IDs.
2. Transform Airtable’s single-table structure into Gleanmo’s two-entity design (`:medication` catalog + `:medication-log` entries with FK).
3. Keep Airtable provenance fields for auditing and future re-imports.
4. Ensure migrated entries show up in dashboards, stats, and viz routes without additional toggles.

## Airtable Source Schema
All historical data currently lives in a single Airtable “Medication Log” table using the JSON schema below (single-select `medication`, numeric `dosage`, enum `unit`, optional `injection_site`, etc.). There is **no** separate medication dimension table—every row contains the medication label.  

```json
{
  "timestamp": "2024-01-01T12:00:00Z",
  "medication": "Adalimumab",
  "dosage": 40,
  "unit": "mg",
  "note": "Humira shot",
  "day": "2024-01-01",
  "injection_site": "left thigh"
}
```

## Migration Strategy
1. **Schema audit**  
   - Confirm `:medication` + `:medication-log` already include optional Airtable fields (`:airtable/id`, `:airtable/created-time`).  
   - Add any missing enums before ingest:  
     - `:medication/label`: ensure catalog includes every Airtable option (Acetaminophen, Ibuprofen, …, “Magnesium (glycinate)”).  
     - `:medication-log/unit`: extend enum to cover `["mg","g","Glob","Sprays","Mcg","Capsule"]`.  
     - `:medication-log/injection-site`: add `:left-thigh`, `:right-thigh`, `:left-lower-belly`, `:right-lower-belly` (or store raw strings if free-form is easier).  
2. **Medication catalog seeding**  
   - For each distinct Airtable `medication` value, create (or look up) a `:medication` entity once, using a deterministic UUID (e.g., hash of downcased label).  
   - Store Airtable metadata on the medication record (`:airtable/label`?) so future imports can match safely.  
3. **Converter**  
   - Build `airtable->medication-log` that:
     - Reads the Airtable export row, parses `timestamp` to instant, copies `note` to `:medication-log/notes`.  
     - Looks up the medication catalog entry to populate `:medication-log/medication-id`.  
     - Normalizes enums (units, injection sites) and copies optional fields (`dosage`, `unit`, `injection_site`).  
     - Attaches deterministic `:xt/id` via `(generate-deterministic-uuid airtable-id)` and stores `:airtable/id` + `:airtable/created-time`.  
   - Validate each transformed log via `med-schema/medication-log`, collecting rejects for manual cleanup.  
4. **Write pipeline**  
   - Submit medication catalog docs first (idempotent upsert), then batch `:medication-log` inserts (`:db/doc-type :medication-log`, `:user/id`).  
   - Batch `biff/submit-tx` at ~1k rows per transaction; run from prod REPL with dev server offline.  
5. **Verification**  
   - Cross-check counts between Airtable and XTDB (`q` by `::sm/type`).  
   - Spot-check timeline charts (`/app/viz/calendar/medication-log`) for distant history and ensure injection-site data renders.  
   - Optionally verify that each medication catalog entry now appears in `/app/crud/medication`.

## Follow-ups
- Add a notebook similar to `dev/notebooks/20231231_airtable_ingest.clj` that documents the medication import run (filenames, timestamps, record counts).  
- Optional: archive the Airtable export alongside `airtable_data/` with README instructions so reruns are reproducible.
