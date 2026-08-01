---
title: "Relationship selects don't survive production data volumes"
status: ready
description: "CRUD single/many-relationship fields render every related entity as an option, which freezes forms for high-cardinality parents"
created: 2026-08-01
updated: 2026-08-01
tags: [crud, performance, forms]
priority: high
---

# Relationship selects don't survive production data volumes

## Intent

Make a CRUD edit form usable for an entity whose parent type has tens of
thousands of rows. Today the form is a freeze, not a form, and the failure
scales silently with success: it only appears once an entity has enough
history to matter.

## Problem

`relation-options` (`crud/forms/inputs.clj`) calls `all-for-user-query` with
no `:limit`, and `all-entities-for-user` with no limit pulls **every**
document of the related type before rendering one `<option>` each. Choices.js
then initializes over the result.

After the Airtable exit, the exercise entities alone make this concrete:

| Form | Relationship field | Options rendered |
|------|--------------------|------------------|
| `exercise-line` edit | `:exercise-line/set-id` | **10,190** |
| `exercise-line` edit | `:exercise-line/exercise-id` | 471 |
| `exercise-set` edit | `:exercise-set/session-id` | **2,563** |

This was hit for real on 2026-08-01: editing an exercise line on a phone was
impossible. The options are labeled by `relation-labels/entity->label`, which
for a set with no label falls back to a bare `yyyy-MM-dd HH:mm` timestamp — so
even a working 10,190-option select would be unpickable.

The workout screen now routes around this with its own inline line editors
(see [exercise.md](./exercise.md)), but every other high-cardinality parent
relationship in the app still points at the generic form.

## Specification

A relationship select renders a bounded option list. Candidate approach,
smallest blast radius first:

1. Cap `relation-options` at N (say 50) most-recent entities, ordered by the
   related type's sort key, and **always** include the currently-selected
   value so opening an edit form never silently rewrites the field.
2. Where the cap truncates, say so — Choices' `noResultsText` already bridges
   to inline create; it needs a sibling message for "showing the 50 most
   recent; search to narrow."
3. Back the search with a server-side lookup (`hx-get` on Choices' `search`
   event) rather than client filtering over a truncated list, otherwise
   picking an older parent becomes impossible rather than merely slow.

Step 1 alone is a strict improvement and is independently shippable; step 3 is
what makes older parents reachable again.

## Validation

- [ ] Unit: `relation-options` returns at most N + the current value, and the
      current value is present even when it falls outside the recent window.
- [ ] E2E: open `/app/crud/form/exercise-line/edit/<id>` on a seeded account
      with >1,000 sets; the form is interactive within a normal page budget
      and the field's existing value is still selected.
- [ ] Manual: confirm the previously-selected parent round-trips unchanged
      through a save that doesn't touch the relationship field.

## Scope

Not included: replacing Choices.js, changing `entity->label`, or reworking
how the workout screen edits lines (already done). This is about the generic
`:single-relationship` / `:many-relationship` renderers only.

## Context

- `src/tech/jgood/gleanmo/crud/forms/inputs.clj` — `relation-options`,
  `single-relationship-body`, `render :many-relationship`
- `src/tech/jgood/gleanmo/db/queries.clj` — `all-for-user-query`,
  `all-entities-for-user` (already supports `:limit` / `:order-key`)
- `src/tech/jgood/gleanmo/db/relation_labels.clj` — option labeling
- `resources/public/js/main.js` — `initializeChoices`, `wireInlineCreateSearch`
- Prior art for bounded reads: `roadmap/dashboard-performance.md` (scan-then-pull)

## Notes

Beware the interaction with inline create: the success path re-renders
`single-relationship-body` with the new entity selected. Any truncation must
keep that entity in the list, which the "always include the current value"
rule covers as long as it is applied to the re-render too.
