---
title: "Airtable Metadata Consistency"
status: backlog
description: "Retroactively align airtable lineage fields across all migrated entity schemas"
tags: [schema, airtable, tech-debt]
priority: low
created: 2026-03-17
updated: 2026-03-17
---

# Airtable Metadata Consistency

## Problem

Airtable lineage fields were added incrementally as each entity was migrated, resulting in inconsistencies. The standard (defined in AGENTS.md) is:

**Direct imports:** `airtable/id`, `airtable/created-time`, `airtable/ported-at`
**Derived entities:** `airtable/ported-at`
**Transformed values:** `airtable/original-*` fields as needed

## Current State by Entity

### Already deployed (production data exists)

| Entity | id | created-time | ported-at | Notes |
|---|---|---|---|---|
| medication | - | - | - | Missing all three. Only has catalog fields, no airtable lineage. |
| medication-log | yes | yes | yes | Fully compliant. |
| habit | yes | - | yes | Missing `created-time`. |
| habit-log | - | - | - | Missing all three. Comment in schema acknowledges this. |
| bm-log | yes | yes | - | Missing `ported-at`. Also has deprecated incorrectly-namespaced duplicates (`bm-log/airtable-id`, `bm-log/airtable-created-time`). |

### Not yet deployed (schemas can be freely changed)

| Entity | id | created-time | ported-at | Notes |
|---|---|---|---|---|
| book | yes | yes | yes | Compliant. |
| reading-log | yes | yes | yes | Compliant. Also has `airtable/original-location`. |
| book-source (derived) | - | - | yes | Compliant for derived entity. |
| location (derived) | - | - | yes | Compliant for derived entity. |
| symptom-log | yes | yes | yes | Compliant. |
| symptom-episode | - | - | - | Needs at minimum `ported-at` if episodes are created during migration. |
| exercise | yes | yes | - | Has non-standard `airtable/ported` (boolean) instead of `ported-at` (instant). Also has entity-specific fields (`airtable/exercise-log`, `airtable/log-count`). |
| exercise-log | - | - | - | Has `airtable/ported` (boolean) and `airtable/missing-duration`. Non-standard. |
| exercise-set | - | - | - | Has `airtable/ported` (boolean), `airtable/exercise-id`, `airtable/missing-duration`. Non-standard. |

## Retroactive Fixes (deployed entities)

These require schema changes + data backfill on production data. Low priority since the data is already imported and these fields are only useful for auditing.

### 1. medication — add `airtable/id`, `airtable/created-time`, `airtable/ported-at`
- Schema change: add 3 optional fields
- Data backfill: re-run transformation against original Airtable export to populate fields on existing records
- Risk: low (additive, optional fields)

### 2. habit — add `airtable/created-time`
- Schema change: add 1 optional field
- Data backfill: match by `airtable/id`, populate from Airtable export
- Risk: low

### 3. habit-log — add `airtable/id`, `airtable/created-time`, `airtable/ported-at`
- Schema change: add 3 optional fields
- Data backfill: would need to re-run against original Airtable export and match records
- Risk: low but matching may be complex

### 4. bm-log — add `airtable/ported-at`, remove deprecated namespaced fields
- Schema change: add `ported-at`, mark `bm-log/airtable-id` and `bm-log/airtable-created-time` for removal
- Data backfill: set `ported-at` on records that have `airtable/id`
- Cleanup: migrate values from `bm-log/airtable-*` to `airtable/*` if not already duplicated, then remove old fields
- Risk: medium (field removal requires careful migration)

## Pre-deployment Fixes (can be done now)

### ~~5. book-source — add `airtable/ported-at`~~ DONE
- Schema field added. Migration code already sets it.

### 6. exercise, exercise-log, exercise-set — standardize naming
- Replace `airtable/ported` (boolean) with `airtable/ported-at` (instant)
- Rename entity-specific fields or keep alongside standard fields
- Do this before first exercise migration run

### 7. symptom-episode — add `airtable/ported-at` if created during migration
- Decide during symptom migration implementation

## Recommendation

- Fix items 5-7 now (no production impact, prevents future debt).
- Items 1-4 are nice-to-have auditing improvements. Only pursue if actively debugging migration issues or doing a second pass on those entities.
