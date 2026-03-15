---
title: "Schema Consistency Audit"
status: draft
description: "Audit and standardize all Malli schemas for naming, field ordering, and conventions"
created: 2026-03-15
updated: 2026-03-15
tags: [schema, consistency, code-quality]
priority: medium
---

# Schema Consistency Audit

## Intent

Audit all entity schemas in `src/tech/jgood/gleanmo/schema/` to establish and enforce consistent naming conventions, field ordering, and structural patterns across the codebase.

## Specification

All schemas follow a consistent pattern:
- Standard field ordering (xt/id, entity-specific fields, timestamps, soft-delete)
- Consistent naming conventions (kebab-case, ::namespace/field format)
- Uniform use of `:closed true`
- Consistent optional/required field patterns
- Aligned enum naming across similar entity types

## Validation

- [ ] Document current schema inconsistencies
- [ ] Define canonical schema conventions
- [ ] Update all schemas to match conventions
- [ ] All existing tests pass after changes
- [ ] `just validate` passes

## Scope

- Malli schemas in `src/tech/jgood/gleanmo/schema/`
- Schema registry in `src/tech/jgood/gleanmo/schema.clj`
- Does not include data migration for renamed fields (separate work unit if needed)

## Context

- Schemas have evolved organically as new entity types were added
- Some schemas predate the current CRUD system patterns
- Field ordering and naming varies across entities
- Related: input rendering in `crud/forms/inputs.clj`, converters in `crud/forms/converters.clj`

## Open Questions

- Should we rename existing fields for consistency, risking data migration complexity?
- Are there fields that should be extracted into shared "mixins" (e.g., timestamps, soft-delete)?
- What is the priority order for entities to audit?

## Notes

Initial audit should catalog current state before making changes.
