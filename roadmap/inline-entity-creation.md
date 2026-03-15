---
title: "Inline Entity Creation"
status: draft
description: "Create related entities mid-form without losing context, then resume the original form"
created: 2026-03-15
updated: 2026-03-15
tags: [ux, forms, crud, relationships]
priority: high
---

# Inline Entity Creation

## Intent

Enable users to create missing related entities while filling out a form, then seamlessly return to complete the original form. Reduces friction when logging data that references entities that may not yet exist.

## Specification

A generic inline creation flow that works across all entity relationships:

1. **Trigger**: User types/selection in a relationship field reveals "Create new [Entity]" option when no match found
2. **Inline Modal/Drawer**: Opens a minimal creation form for the related entity
3. **Context Preservation**: Original form state (filled fields, selections) is preserved
4. **Auto-link**: Newly created entity is automatically selected in the original form
5. **Resume**: User continues where they left off

**Priority relationships:**
- Habit logs → Habits
- Bouldering sessions → Boulder problems
- Exercise sets → Exercises

## Validation

- [ ] E2E flow: Create habit log → inline create missing habit → complete habit log
- [ ] E2E flow: Create bouldering session → inline create boulder problem → complete session
- [ ] E2E flow: Create exercise set → inline create exercise → complete set
- [ ] Visual: Inline creation UI matches existing modal/drawer patterns
- [ ] Accessibility: Keyboard navigation works through the entire flow

## Scope

**In scope:**
- Generic infrastructure for any entity relationship
- Modal or drawer presentation (TBD)
- Form state preservation
- Auto-selection of newly created entity

**Out of scope:**
- Inline editing of existing entities (separate feature)
- Bulk creation of multiple entities
- Nested inline creation (creating an entity that itself needs a missing related entity)

## Context

- CRUD forms live in `src/tech/jgood/gleanmo/crud/`
- Entity relationships are defined in Malli schemas
- HTMX powers form interactions
- Choices.js handles select/autocomplete fields

## Open Questions

- Modal vs. drawer vs. inline expansion for the creation UI?
- Should newly created entities require all required fields, or allow minimal creation with "fill in later" prompt?
- How to handle validation errors in the inline form—block or allow dismissing?
- Should this support "quick create" with just a name, deferring other fields?

## Notes

High-impact for workflows where users discover missing entities during logging:
- Bouldering: new problems added frequently
- Exercises: trying new movements
- Habits: spontaneous new habits

This is a common pattern in mature CRMs and project management tools (e.g., Notion, Linear).
