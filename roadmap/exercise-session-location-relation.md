---
title: "Exercise session location as a relation"
status: draft
description: "Replace the free-text location string on exercise-session with a proper location relation, matching every other log entity"
created: 2026-08-04
updated: 2026-08-04
tags: [schema, exercise, location, data-modeling]
priority: medium
---

# Exercise session location as a relation

## Intent

`exercise-session/location` is a free-text `:string`. Every other log entity
that has a location (`meditation-log`, `reading-log`, `project-log`) points at
the `location` entity by id, which gives inline create, the timer workspace
location picker, archived/sensitive flags, and a single canonical list of
places. Exercise sessions are the holdout, so a workout "at the gym" and a
meditation "at the gym" are two unrelated strings instead of one shared
reference. Bring exercise sessions in line with the rest of the app.

## Specification

This is an additive schema change, not an in-place type swap — exercise
sessions were already imported from Airtable (m006, 84 sessions in
production), so the existing string field has real documents behind it.

- Add `[:exercise-session/location-id {:optional true, :crud/priority 1,
  :crud/label "Location", :crud/inline-create true} :location/id]` to
  `exercise-session`, positioned where the string field is today.
- Mark the existing `:exercise-session/location` string field deprecated:
  `{:optional true :hide true :crud/suggest-existing true}`. It stays in the
  schema (these maps are `:closed true`) so old documents keep validating on
  their next write, but the form stops rendering it and readers stop looking at
  it.
- Readers (session detail, summary, lists, any viz) read
  `:exercise-session/location-id` and resolve through `location` — never
  through the deprecated string. No fallback chain.
- Follow the `reading-log` precedent exactly: it carries both
  `:reading-log/location-id` (the relation) and `:airtable/original-location`
  (the raw imported string preserved for lineage). Exercise sessions that
  already have a string value keep it on disk untouched; the import-time
  mapping, if any, lives in a one-off backfill rather than in the schema.

## Validation

- [ ] `just lint-fast` on the touched schema file.
- [ ] Schema compiles via `just check`.
- [ ] E2E: exercise-session create/edit form renders a Choices.js location
      select (the standard `:location/id` renderer) with inline-create wired,
      instead of the old free-text `:crud/suggest-existing` input.
- [ ] Manual: open an existing session imported from Airtable; it still opens
      and re-saves without validation failure (the deprecated string field is
      still accepted by the closed map).

## Scope

- Not included: migrating the historical string values into `location`
  entities. Whether and how to backfill is an open question below.
- Not included: changing `symptom-log/location` (a `body-location-enum`, which
  is anatomy, not a place) or the `user/current-location-id` setting (already a
  relation).
- Does not depend on [crud-relation-select-scale.md](./crud-relation-select-scale.md):
  `location` is low-cardinality for a single user, so the bounded-select
  problem doesn't bite here.

## Context

- Current field: `src/tech/jgood/gleanmo/schema/exercise_schema.clj:33` —
  `:exercise-session/location` as `:string` with `:crud/suggest-existing true`.
- The relation to adopt: `src/tech/jgood/gleanmo/schema/location_schema.clj`
  (`location` entity with `:location/label`, `:location/notes`,
  `:location/archived`, `:location/sensitive`).
- Peer precedents to copy from: `reading-log` (`reading_schema.clj:52`, carries
  both the relation and `:airtable/original-location`), `meditation-log`
  (`meditation_schema.clj:32`), `project-log` (`project_schema.clj:30`).
- Rule governing "add, don't rewrite": `AGENTS.md` → "Changing a field that
  already has data — add, don't rewrite".
- Parent work unit: [exercise.md](./exercise.md).

## Open Questions

- **Backfill or leave the strings alone?** Existing sessions carry strings like
  "gym", "home", "Living room". Options: (a) leave them — readers ignore the
  deprecated field and old sessions simply show no location until re-edited;
  (b) one-off script that creates/ matches `location` entities per distinct
  string and writes `:exercise-session/location-id` on the historical docs.
  (a) is cheaper and reversible; (b) makes history queryable by place from day
  one. Resolve before moving to `ready`.
- **Keep the string field visible on the form as a fallback during a
  transition, or hide it immediately?** The `reading-log` precedent hid the old
  field entirely. Lean toward hiding, but confirm the historical-string
  question above first — if we backfill, hiding is obviously right; if we
  don't, hiding means old sessions lose their displayed location until edited.
- **Naming for the deprecated field.** Leave it as
  `:exercise-session/location` (matches the `bm-log` / `habit` pattern of
  keeping the old name) or rename-on-add? Default: keep the name, just hide.

## Notes

- `:crud/suggest-existing` (what the string field uses today) and
  `:crud/inline-create` (what the relation would use) are different affordances
  — the former autocompletes from prior raw string values, the latter creates
  real `location` entities. The win is the latter: one canonical "Living room"
  instead of three typo variants.
