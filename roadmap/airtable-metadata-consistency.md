---
title: "Airtable Metadata Consistency"
status: draft
description: "Retroactively align airtable lineage fields across all migrated entity schemas"
tags: [schema, airtable, tech-debt]
priority: medium
created: 2026-03-17
updated: 2026-07-28
---

# Airtable Metadata Consistency

## Problem

Airtable lineage fields were added incrementally as each entity was migrated, resulting in inconsistencies. The standard (defined in AGENTS.md) is:

**Direct imports:** `airtable/id`, `airtable/created-time`, `airtable/ported-at`
**Derived entities:** `airtable/ported-at`
**Transformed values:** `airtable/original-*` fields as needed

## Current State by Entity

Audited programmatically against all 29 entity schemas on 2026-07-28 (walking
the registry rather than reading files, so this reflects the schemas as they
actually are).

### Deployed — production data exists

| Entity | id | created-time | ported-at | Notes |
|---|---|---|---|---|
| medication | - | - | - | Missing all three. Only catalog fields. |
| medication-log | yes | yes | yes | Compliant. |
| habit | yes | - | yes | Missing `created-time`. |
| habit-log | - | - | - | Missing all three. Acknowledged in the schema. |
| bm-log | yes | yes | - | Missing `ported-at`. Also carries deprecated wrong-namespace duplicates `bm-log/airtable-id` and `bm-log/airtable-created-time` **alongside** the correct `airtable/*` pair. |
| book | yes | yes | yes | Compliant. Deployed 2026-03-21. |
| reading-log | yes | yes | yes | Compliant, plus `airtable/original-location`. Deployed 2026-03-21. |
| book-source (derived) | - | - | yes | Compliant for a derived entity. Deployed 2026-03-21. |
| location (derived) | - | - | yes | Compliant for a derived entity. Deployed 2026-03-21. |

### Not yet deployed — m003–m006 pending, schemas still free to change

| Entity | id | created-time | ported-at | Notes |
|---|---|---|---|---|
| symptom-log | yes | yes | yes | Compliant, plus `original-rating` / `original-areas` / `original-type`. |
| symptom-episode (derived) | - | - | yes | Compliant for a derived entity. |
| mood-log | yes | yes | yes | Compliant, plus `original-mood`. |
| exercise | yes | yes | yes | Compliant, plus `log-count` / `aliases` / `pt-recommended`. |
| exercise-session (synthesized) | - | - | yes | Compliant. Sessions are derived by gap-splitting, so no source record backs them. |
| exercise-set | yes | yes | yes | Compliant, plus `exercise-id` / `original-duration`. |
| exercise-line | yes | yes | yes | Compliant, plus `breaths` / `steps` / `angle` / `better-than-normal` / `worse-than-normal` / `original-reps`. |
| boulder-problem | yes | yes | yes | Compliant, plus `original-problem-number`. |
| boulder-session (synthesized) | - | - | yes | Compliant. One per Airtable day, no source record. |
| boulder-attempt | yes | yes | yes | Compliant. |

**Every entity added since this doc was last written is compliant.** The only
gaps are the four deployed entities in the first table, and they are unchanged
since 2026-03-17.

**Corrections to the previous version of this table:** it listed `exercise-log`,
which no longer exists — the exercise hierarchy was reworked to
session → set → line (2026-07-10). It also filed book / reading-log /
book-source / location under "not yet deployed"; those went to production on
2026-03-21. It predated mood-log and all three boulder entities.

## The backfill source is time-limited

The four outstanding fixes all need data that only Airtable still holds:

- `airtable_data/` no longer contains medication, habit, habit-log or bm-log
  exports — only the six recent families (books, bouldering problems/tries,
  exercise/exercise_log, mood, pain, reading).
- Re-exporting is possible today via `clj -M:dev download-airtable`, and only
  for as long as the Airtable base exists.

So these fixes have a deadline that the rest of the Airtable exit does not:
**after the base is shut down, the values can never be recovered.** That is the
main argument for doing them before retirement rather than "sometime".

## Retroactive Fixes (deployed entities)

These require schema changes + data backfill on production data. Medium priority while exiting Airtable: do the fixes when they improve import confidence or make final Airtable shutdown auditable.

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

### ~~6. exercise, exercise-log, exercise-set — standardize naming~~ DONE
- Replaced `airtable/ported` (boolean) with `airtable/ported-at` (instant)
- Added `airtable/id` and `airtable/created-time` to exercise-log and exercise-set
- Kept entity-specific fields alongside standard fields

### ~~7. symptom-episode — add `airtable/ported-at`~~ DONE
- Added `airtable/ported-at` for migration-created episodes

## Recommendation

- ~~Fix items 5-7 now (no production impact, prevents future debt).~~ All done.
- TODO comments added to deployed schemas referencing this doc.

**Revised 2026-07-28.** The earlier read — "nice-to-have, pursue only if
debugging" — understated one thing: the source data expires. Items 1–4 need
a fresh Airtable export to backfill from, so they are cheap now and impossible
after the base is retired.

What that buys, concretely:

- **A completeness check on the exit.** `airtable/id` on every imported record
  is what lets you ask "did all 23 medications and 1,305 logs actually arrive,
  and did anything double-import?" — by joining against the export. Without it,
  those four entities can only be eyeballed. That check is worth most in the
  window just before shutdown, which is the window we are in.
- **Idempotent re-runs**, which is the property that made m003–m006 safe to
  re-run on refreshed exports. The deployed four do not have it.

What it does not buy: much of anything after the base is gone. The lineage is
then archaeology — a record of where the data came from, not a key you can
still join on.

### Suggested split

- **Before retirement, if doing them at all:** medication and habit. Both are
  small catalogs (23 medications), both already have a working ingester or
  runner, and habit needs only one field added.
- **Judgement call:** habit-log — the volume is much larger and the doc already
  flags that matching may be complex, since there is no `airtable/id` to match
  *on*. Matching would have to be heuristic (timestamp + habit), which is
  exactly the kind of fuzzy backfill that can quietly mis-attribute records.
  Doing nothing may be better than doing it approximately.
- **Independent of Airtable:** bm-log's duplicate-field cleanup. Collapsing
  `bm-log/airtable-*` into `airtable/*` and adding `ported-at` is a pure
  data-shape fix on values already in the database, so it can happen any time
  and carries no deadline.
