---
title: "Entity Merge"
status: draft
description: "Combine duplicate entities by repointing every schema-declared reference to one target, goal filters included"
created: 2026-02-21
updated: 2026-09-14
tags: [data, crud, utilities, goals]
priority: medium
---

# Entity Merge

## Intent

Merge two or more entities of the same type into one target entity.
Every record that referred to a source entity refers to the target afterward.
Duplicates appear by accident: two "Gratitude Journaling" projects, or the exercises "Pullup" and "Pullups".

Goals ([081](./081-goals-dashboard.md)) make duplicates more visible.
A goal can select both duplicates as a workaround, but the fix is one entity.

## Generic approach

Implement one merge operation that works for any entity type.
Do not write a separate merge for each type.

The operation takes an entity type, the source ids, and a target id.
It finds the attributes that reference that type by reading the schema registry, not a hand-kept list.
`schema-utils/extract-relationship-fields` already identifies both reference shapes:

- Single references, such as `:exercise-line/exercise-id` and `:project-log/project-id`.
- Set references, such as `:habit-log/habit-ids` and `:goal/exercise-ids`.

A new entity type then becomes mergeable with no merge-specific code.

For example, merging "Pullups" into "Pullup" repoints:

- every `:exercise-line/exercise-id` that holds the "Pullups" id;
- every `:goal/exercise-ids` set that contains it.

## Constraints

- Merge only entities of the same type and the same user.
- Repoint every referencing attribute in every schema, including goal relation filters.
- In a set reference, replace each source id with the target id and remove duplicates.
  A goal that selected both "Pullup" and "Pullups" then selects only "Pullup".
- Do not soft-delete a source before its references are repointed.
  The goals dashboard hides a goal whose selected record is missing or deleted (`visible?` in `goals/dashboard.clj`).
  A partial merge would make those goals disappear.
- Write through `db/mutations.clj`, so goal writes pass the rules in `schema/rules.clj`, including ownership.
- Preserve all logs and their intervals; a merge changes references only.
- Treat the merge as irreversible unless an undo design is added (see Open Questions).
- Keep the target's metadata (label, notes, flags); the user resolves conflicts before merging.

## Specification

### Acceptance criteria

1. **Selection UI**: Select the source entities and the target entity of one type.
   Reuse the Choices.js relationship select.
2. **Preview**: Show the number of references to repoint, for each referencing attribute.
   Example: "212 exercise lines, 2 goals."
3. **Execution**: Repoint all references, then remove the sources.
4. **Cleanup**: Soft-delete or archive the source entities, after repointing.
5. **Audit**: Record which entities were merged into which target, and when.

### Supported entity types

Every type that other schemas reference, found from the schema registry.
First use cases: exercise (Pullup/Pullups), project, habit.

### Query shape

Find references with equality-bound reads on each referencing attribute (`[?e attr source-id]`).
The cost then tracks the number of references, not the user's history.
Add these reads to `db/queries.clj`.

## Validation

- Unit tests for the merge logic, with a test XTDB node:
  - single references repointed;
  - set references repointed and deduplicated;
  - goal filters repointed, and the goal still visible on the dashboard;
  - another user's records untouched.
- E2E test: create "Pullup" and "Pullups" with exercise lines and a goal that selects both.
  Merge them, and verify the line counts, the goal's filter, and the goal's total.
- Manual test with real data, after a local backup.

## Context

- 2026-02-21: two projects for the same activity (gratitude journaling).
- 2026-09-14: "Pullup" and "Pullups" found while creating the first real workout goal.
  The goal's multi-exercise filter is the workaround until this merge exists.

## Open Questions

- Should merged-from entities be hard-deleted, soft-deleted, or kept with a marker such as `<entity>/merged-into`?
  A marker would give the audit trail and a possible undo.
- Is a preview step required, or is one confirmed action enough?
- Merging across entity types stays out of scope.
- Does any stored value, beyond references, need to be recomputed?
  Goals store no progress, so they need none.
