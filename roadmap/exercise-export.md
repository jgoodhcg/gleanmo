---
title: "Exercise Data Export"
status: ready
description: "The exercise composite export — session → set → line as nested Markdown or JSON (denormalized to CSV), the first consumer of the data-export page"
created: 2026-08-07
updated: 2026-08-18
tags: [export, exercise, integration, llm-context]
priority: high
---

# Exercise Data Export

## Intent

Get exercise/workout data out of Gleanmo and into the user's health-coach
agentic project. This was previously done by copy-pasting from Airtable —
acceptable effort, but Airtable is no longer the system of record (Airtable
exit complete 2026-07-31; Gleanmo now holds 471 exercises / 2,563 sessions /
10,190 sets+lines, per
[data-migration-status.md](./data-migration-status.md)). The same workflow
needs a new source.

**This unit is now scoped as one composite export inside
[data-export.md](./data-export.md), not as its own page.** The page, the date
controls, the copy button, and the renderers live there. What lives here is the
exercise-specific assembly: the three-level session → set → line shape, and why
it cannot be a flat entity export.

The reason exercise is a composite and not a generic entity export: a set
holding two lines *is* the superset. Flatten the hierarchy into an
exercise-line table with session ids and the information survives only as a
foreign key the reader has to rejoin. That is fine for a spreadsheet and bad
for an LLM.

## Specification

A read-only composite export of exercise data for a caller-supplied time
window, registered in the composite registry defined by
[data-export.md](./data-export.md).

### Registration

- Register under key `:exercise` with `{:label "Exercise sessions" :assemble _
  :render _}`.
- `assemble` takes `{:keys [db user-id from until]}` and returns nested data:
  sessions, each with its sets, each with its lines, exercise labels resolved.
- `render` takes that data plus a format and returns a string.
- Both are pure past the db read, so both are testable against a fixture with
  no HTTP and no running app.

### Reads

- All reads go through `db/queries.clj`; no direct XTDB. Reuse
  `recent-sessions-for-user` / a windowed session scan, then
  `sets-for-sessions`, then `lines-for-sets` to assemble the three-level
  hierarchy, resolving exercise labels via the `exercise` entities.
- A windowed session scan (sessions with `beginning` in `[from, until]`) likely
  needs adding to `queries.clj` following the scan-then-pull pattern — do not
  inline it at the call site.

### Format

All three formats defined in [data-export.md](./data-export.md) apply. The
assembly is format-agnostic — one `assemble`, three renderers over its output.

- **Markdown** (default): one section per exercise-session, each set nested
  under its session, each line (exercise label, reps, weight + unit, distance +
  unit, notes) nested under its set, with timestamps preserved.
- **JSON**: the same nesting, sessions → sets → lines, under the shared
  provenance wrapper. This is the shape to hand a coach agent that parses.
- **CSV**: fully denormalized — one row per exercise-line, repeating its set's
  and session's columns. A superset therefore appears as two rows sharing a set
  id, which is the flattening this unit exists to avoid for LLM reading but is
  exactly right for a spreadsheet.

### Data shape

- One block per exercise-session whose `beginning` falls in `[from, until]`
  (beginning/end, location, notes).
- Under each session, one block per set (beginning/end), listing its lines.
- Deleted docs excluded (existing queries already post-filter
  `::sm/deleted-at`).

## Validation

- [ ] Unit: `assemble` produces the expected nested data for a fixture of 2
      sessions / 3 sets / 5 lines, including a superset (one set, two lines,
      two exercises).
- [ ] Unit: `render` produces the expected Markdown for that fixture.
- [ ] Unit: `render` produces the expected JSON nesting for that fixture.
- [ ] Unit: `render` denormalizes that fixture to CSV, with the superset's two
      lines emitted as two rows sharing one set id.
- [ ] Unit: empty window produces an empty (non-erroring) export.
- [ ] Unit: a deleted session/set/line in the fixture does not appear.
- [ ] `just e2e-test export` — load `/app/export`, pick the exercise
      composite, set a range, copy, assert the clipboard payload contains a
      known fixture session.
- [ ] New test namespaces are required in `test/tech/jgood/gleanmo/test.clj`,
      or the runner skips them silently.
- [ ] `just lint-fast` and `just check` pass on touched files.

## Scope

### In scope

- The exercise composite: assembly + Markdown, JSON, and CSV rendering over a
  time window.
- The exercise entities only: exercise-session, exercise-set, exercise-line
  (+ exercise labels).
- The windowed session query in `db/queries.clj`.

### Out of scope

- The export page, date controls, copy button, format and relation controls —
  all [data-export.md](./data-export.md).
- No API, CLI, or MCP server (that's
  [ai-assistance.md](./ai-assistance.md)).
- No writes back to Gleanmo.
- No other entity types — anything flat is served by the generic entity
  exporter, not by a second composite.
- No auth surface beyond the existing app session.

## Context

- Depends on [data-export.md](./data-export.md); ship that page first, or ship
  both together with this as its first registered composite.
- Data model: `src/tech/jgood/gleanmo/schema/exercise_schema.clj`
  (exercise, exercise-session, exercise-set, exercise-line).
- Reads to reuse: `recent-sessions-for-user`, `sets-for-sessions`,
  `lines-for-sets` in `src/tech/jgood/gleanmo/db/queries.clj`.
- DB-layer rule: all access via `db/queries.clj`; never `xt/q`/`xt/entity`
  outside it.
- Relationship to the bigger goal: [ai-assistance.md](./ai-assistance.md) is
  the eventual API/CLI/MCP; this copy-paste path is retired (or kept as a
  no-auth convenience) when that ships.
- Sensitivity: exercise data is non-sensitive; no need to apply
  `resolve-user-settings` sensitive filtering, though routing through the same
  read helpers is harmless.

## Notes

The reason this is its own work unit and not folded into ai-assistance.md: the
full integration (auth, sensitivity model, MCP/API surface, write paths) is
weeks of work and the health-coach project needs data now. A paste is the
proven workflow — it just needs a new tap.

**2026-08-18 restructure.** Originally specified as a standalone
`GET /app/exercise/export` page owning its own controls and clipboard block.
Split when the health-coach goal grew to include nutrition
([cronometer-integration.md](./cronometer-integration.md)): a second
hand-written export page would have duplicated every control, and a generic
per-entity exporter alone would have flattened the superset structure. The page
and the generic exporter moved to [data-export.md](./data-export.md); this unit
kept the exercise-specific assembly. Nothing about the exercise output shape
changed.
