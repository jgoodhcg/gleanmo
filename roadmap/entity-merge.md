---
title: "Entity Merge"
status: draft
description: "Combine logs from duplicate entities into one target entity"
created: 2026-02-21
updated: 2026-02-21
tags: [data, crud, utilities]
priority: medium
---

# Entity Merge

## Intent

Provide a way to merge two or more entities of the same type, migrating all associated logs to a single target entity. Useful when duplicate entities are created accidentally (e.g., two "Gratitude Journaling" projects that should be one).

## Constraints

- Only merge entities of the same type (project-to-project, habit-to-habit, etc.)
- Merge is irreversible (or requires careful undo design)
- Must handle conflicts in metadata (names, descriptions, settings)
- Preserve all time logs and associations

## Specification

### Acceptance Criteria

1. **Selection UI**: Select source entity/entities and target entity
2. **Preview**: Show what will be merged (log counts, any conflicts)
3. **Execution**: Re-associate all logs from source(s) to target
4. **Cleanup**: Optionally soft-delete or archive source entities after merge
5. **Audit**: Record merge action for traceability

### Supported Entity Types (MVP)

- Projects (most common use case)
- Habits
- Categories (if applicable)

## Validation

- Unit tests for merge logic in mutations
- E2E test: create two projects with logs, merge them, verify log counts
- Manual test with real data

## Context

User accidentally created two projects for the same activity (gratitude journaling) and wants to consolidate without losing historical logs.

## Open Questions

- Should we support merging across entity types? (Probably no for MVP)
- How to handle conflicting metadata (different names, descriptions)?
- Should merged-from entities be hard-deleted, soft-deleted, or kept as "merged"?
- Is a preview step required or can it be a single action with confirmation?

## Notes

- Consider a generic approach: merge function takes entity type, source IDs, target ID
- May need to update denormalized fields or cached counts
- Check if any views/aggregations need rebuilding after merge
