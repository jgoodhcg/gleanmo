---
title: "Airtable Metadata Consistency"
status: done
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

**Every entity added since this doc was last written is compliant.** The four
deployed entities in the first table are unchanged since 2026-03-17 and are
settled by decision — see Status below.

**Corrections to the previous version of this table:** it listed `exercise-log`,
which no longer exists — the exercise hierarchy was reworked to
session → set → line (2026-07-10). It also filed book / reading-log /
book-source / location under "not yet deployed"; those went to production on
2026-03-21. It predated mood-log and all three boulder entities.

## Status: settled — no action planned (decided 2026-07-28)

The owner considers medication, habit, habit-log and bm-log **fully ported**.
All four have been in production use for months. The missing lineage fields are
an audit convenience, not a correctness problem, and nothing depends on them.

**The bm-log "duplicates" are not a defect.** `:airtable/*` was always the
intended namespace; an agent wrote `:bm-log/airtable-*` during development and
the mistake was found after the migration had already run. The schema then
gained the correctly-namespaced pair *alongside* the originals deliberately —
backward compatible with the data already written, while documenting the
intent. Keeping both is the fix, not evidence of one.

Any future work on these four should be limited to **documentation or bug
fixes**. Do not schedule backfills, and do not treat the table above as a
to-do list — it is a record of how each entity was migrated, which is the
useful thing to keep.

One open detail, recorded rather than actioned: it is not known whether the
`:airtable/*` values on bm-log were ever populated, or whether the data still
sits only under `:bm-log/airtable-*`. The schema carries both, so nothing is
lost either way, and a single read against production would answer it if it
ever matters.

## Completed fixes (historical record)

Done during development, before the affected entities were deployed:

- **book-source** — added `airtable/ported-at`; the migration already set it.
- **exercise / exercise-log / exercise-set** — replaced `airtable/ported`
  (boolean) with `airtable/ported-at` (instant); added `airtable/id` and
  `airtable/created-time`. Entity-specific fields kept alongside the standard
  ones. (`exercise-log` has since been replaced by the
  session → set → line hierarchy.)
- **symptom-episode** — added `airtable/ported-at` for migration-created
  episodes.

## Note on export availability

For the record, since it shaped an earlier version of this doc:
`airtable_data/` holds only the six recent export families (books, bouldering
problems/tries, exercise, mood, pain, reading). Medication, habit and bm-log
exports are no longer in the repo, and re-pulling them requires
`clj -M:dev download-airtable` against a live base. Given the decision above,
this is context, not a deadline.
