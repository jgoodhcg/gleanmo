---
title: "Data Export"
status: draft
description: "One export page: a generic per-entity exporter that works for any CRUD entity, plus named composite exports that keep multi-level shapes intact"
created: 2026-08-18
updated: 2026-09-01
tags: [export, crud, integration, llm-context]
priority: high
---

# Data Export

## Intent

Get data out of Gleanmo and into the user's health-coach agentic project with a
copy and a paste. This was previously done from Airtable; Airtable is no longer
the system of record (exit complete 2026-07-31), so the same workflow needs a
new tap.

The bar is deliberately low: a time-windowed dump the user can select and copy.
A real API over a CLI or MCP server is the eventual shape
([ai-assistance.md](./ai-assistance.md)) and is out of scope here.

Two exporters, one page, because the data has two shapes:

1. **Entity export** — flat, generic, driven off the Malli schema. Works for
   any registered CRUD entity with no per-entity code. This is where new
   entities (nutrition, body composition — see
   [cronometer-integration.md](./cronometer-integration.md)) get export for
   free the day their schema lands.
2. **Composite export** — a named, hand-shaped export for entity groups whose
   nesting *is* the information. Exercise is the first
   ([exercise-export.md](./exercise-export.md)): session → set → line, where a
   set holding two lines is what makes it a superset. Flattening that into an
   exercise-line table preserves the bytes and loses the meaning for an LLM
   reader.

## Specification

### Surface

- New page `GET /app/export`, linked from the home data section (which
  currently says "Full data export and a read API. Not built yet — on the
  roadmap." — `src/tech/jgood/gleanmo/home.clj:186`).
- The page lists, in two sections:
  - **Composites** — each registered composite by label.
  - **Entities** — each registered entity by plural label.
- Shared controls: a date range (from / until, default last 30 days), an output
  format, and a relation-rendering mode.
- The assembled output renders into a copyable block with a **Copy** button.
  Reuse the app's existing clipboard component if one exists rather than
  hand-rolling one.

### Entity export (generic)

- New `src/tech/jgood/gleanmo/export/routes.clj` exposing
  `gen-routes {:entity-key :schema :plural-str :entity-str}`, mounted per
  entity namespace beside `crud/gen-routes`. This is the established pattern:
  `crud/routes.clj`, `viz/routes.clj`, and `timer/routes.clj` all take the same
  argument map, and entity namespaces opt in one line at a time — see
  `src/tech/jgood/gleanmo/app/reading_log.clj:9-22`. Mount the resulting
  `export-routes` vars in the `/app` route vector in
  `src/tech/jgood/gleanmo/app.clj:561`.
- Opt-in per entity, like viz and timer routes. An entity with no
  `export-routes` var does not appear on the page.
- Field selection: default to the schema's `:crud/priority` fields plus the
  entity's timestamp or beginning/end fields; allow selecting the full field
  set.
- The date window filters on the entity's `beginning` field when it has one,
  otherwise its `timestamp` field, otherwise `::sm/created-at`.

### Relation rendering

- One control with three modes: `id`, `label`, `both`.
- `label` is the default — an LLM reader wants "Deadlift", not a UUID.
- `id` matters when the paste has to be re-joined against another export.
- Reuse the existing resolution rather than writing a second one:
  `format-cell-value :single-relationship` and `:many-relationship` in
  `src/tech/jgood/gleanmo/crud/views/formatting.clj:73,91` already turn
  relation ids into labels for list views.

### Composite registry

- A map of composite key → `{:label _ :assemble fn :render fn}`.
- `assemble` takes `{:keys [db user-id from until]}` and returns plain data.
  `render` takes that data and a format and returns a string. Both pure past
  the db read, so both are unit-testable against a fixture with no HTTP and no
  running app.
- Exercise is the first entry ([exercise-export.md](./exercise-export.md)). A
  nutrition day composite (day → meals → foods → macros) is the likely second.

### Formats

Three formats, offered for every export — entity and composite alike. Format is
a render-time choice, never an assembly-time one: `assemble` returns data,
`render` picks the encoding. Adding a fourth format later must not touch any
assembly code.

- **Markdown** — the default. The consumer is an LLM chat surface and the
  transport is a clipboard paste: Markdown survives pasting, stays readable to
  the user proofreading it in the browser, and expresses nesting without a
  parser. Flat entity exports render as a Markdown table; composites render as
  nested headings and lists.
- **CSV** — for spreadsheet use. Flat entity exports render one row per doc.
  Composites render **fully denormalized**: one row per leaf record, with
  parent columns repeated on every row (for exercise: one row per
  exercise-line, carrying its set's and session's columns). Emit via
  `clojure.data.csv` (`deps.edn:11`) so RFC 4180 quoting is not hand-rolled.
- **JSON** — for when the coach project parses rather than reads. Emit via
  `cheshire` (`deps.edn:19`), already the project's JSON library. Flat exports
  are an array of objects; composites keep their nesting.

JSON encoding rules, so the output is stable across exporters:

- Wrap every payload with its provenance:
  `{"entity"|"composite": "...", "from": "...", "until": "...", "count": n, "rows": [...]}`.
- Strip the namespace from field keys (`:exercise-session/beginning` →
  `"beginning"`). Within one entity's rows the namespace is constant, so it is
  pure token cost for the LLM reader. The wrapper's `entity` field carries the
  type.
- Instants and dates as ISO-8601 strings; UUIDs as strings; keywords as their
  bare name.
- The relation-rendering mode applies unchanged: `label` emits the label
  string, `id` the UUID string, `both` an object `{"id": _, "label": _}`.
- Use the same stripped field names as the CSV header row, so the two formats
  describe the same table.

### Data rules

- All reads go through `src/tech/jgood/gleanmo/db/queries.clj`. Never `xt/q` or
  `xt/entity` outside it. A windowed scan per entity type likely needs adding
  there, following the scan-then-pull pattern; do not inline it at the call
  site.
- Deleted docs are excluded (existing queries already post-filter
  `::sm/deleted-at`).
- Sensitive entities are excluded from the entity list unless the user's
  `:user/show-sensitive` is set, matching the rest of the app.

## Validation

- [ ] Unit: the generic assembler produces the expected rows for a fixture of
      one entity type, in all three relation modes.
- [ ] Unit: an empty window produces an empty, non-erroring export.
- [ ] Unit: a deleted doc in the fixture does not appear.
- [ ] Unit: all three renderers (Markdown, CSV, JSON) agree on cell content and
      field naming for the same flat fixture.
- [ ] Unit: the CSV renderer denormalizes a composite fixture to one row per
      leaf with parent columns repeated.
- [ ] Unit: the JSON renderer emits the provenance wrapper, ISO-8601 instants,
      and namespace-stripped keys.
- [ ] `just e2e-test export` — load the page, pick an entity, set a range,
      copy, assert the clipboard payload contains a known fixture row.
- [ ] New test namespaces are required in `test/tech/jgood/gleanmo/test.clj`,
      or the runner skips them silently.
- [ ] `just lint-fast` and `just check` pass on touched files.

## Scope

### In scope

- Read-only export over a caller-supplied time window.
- The `/app/export` page, the generic entity exporter, the composite registry,
  and the relation-rendering control.
- Markdown, CSV, and JSON renderers, each available for entity and composite
  exports.

### Out of scope

- No API, CLI, or MCP server — that is [ai-assistance.md](./ai-assistance.md).
- No writes back into Gleanmo.
- No scheduled or pushed exports; the user drives every export.
- No streaming or pagination — the date window bounds the result.
- No auth surface beyond the existing app session (personal single-user app;
  the page sits behind normal login).
- No file download; copy-to-clipboard only in this cut.

## Context

- Route generation pattern: `src/tech/jgood/gleanmo/crud/routes.clj`,
  `src/tech/jgood/gleanmo/viz/routes.clj`,
  `src/tech/jgood/gleanmo/timer/routes.clj`.
- Per-entity mounting: `src/tech/jgood/gleanmo/app.clj:561` and the
  `crud-routes` / `viz-routes` / `timer-routes` vars in
  `src/tech/jgood/gleanmo/app/*.clj`.
- Relation label resolution:
  `src/tech/jgood/gleanmo/crud/views/formatting.clj`.
- DB-layer rule: all access via `db/queries.clj`.
- Consumers: [exercise-export.md](./exercise-export.md) (first composite),
  [cronometer-integration.md](./cronometer-integration.md) (first new entities
  to ride the generic exporter).
- Retirement: this page is retired, or kept as a convenience, when
  [ai-assistance.md](./ai-assistance.md) ships.

## Open Questions

- [ ] Should the page support exporting several entities in one block, or one
      at a time with repeated copies? One block is friendlier to paste; it
      complicates the format for CSV.
- [ ] Should a file download be offered alongside copy, for exports too large
      to paste comfortably?
- [ ] Does the relation-rendering control belong per-export or as a user
      setting?

## Notes

This unit exists because a single hand-written export per entity group does not
scale, and a single generic exporter cannot express exercise. Splitting the two
lets the generic path absorb every future entity with one line per namespace,
while the composite path stays free to shape the handful of entity groups where
nesting carries meaning.
