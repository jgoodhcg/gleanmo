---
title: "Exercise Data Export"
status: ready
description: "Quick, copy-paste export of exercise data over a time window for ingestion by an external health-coach agent — interim to the full AI-assistance API/CLI/MCP"
created: 2026-08-07
updated: 2026-08-07
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

The bar is deliberately low: a time-windowed dump the user can copy and paste
is sufficient. A real API over a CLI or MCP server is the eventual shape
([ai-assistance.md](./ai-assistance.md)) but is out of scope here — this is the
minimal interim that keeps the health-coach project fed.

Sequencing: ships immediately after the timer-running-flag deploy
([timer-running-flag.md](./timer-running-flag.md)) and is the next thing
deployed.

## Specification

A read-only export of exercise data for a caller-supplied time window, rendered
in a paste-friendly format.

### Surface

- New route `GET /app/exercise/export` (linked from the exercise dashboard)
  rendering:
  - A date-range control (from / until, with sensible defaults — e.g. last 30
    days).
  - The assembled export as a copyable block with a **Copy** button. Reuse any
    shared clipboard component if one exists in the app rather than hand-rolling
    one.
- All reads go through `db/queries.clj`; no direct XTDB. Reuse
  `recent-sessions-for-user` / a windowed session scan, then
  `sets-for-sessions`, then `lines-for-sets` to assemble the three-level
  hierarchy (session → set → line), resolving exercise labels via the
  `exercise` entities.

### Format

- **Markdown** (the initial and only format for this cut): one section per
  exercise-session, each set nested under its session, each line (exercise
  label, reps, weight + unit, distance + unit, notes) nested under its set,
  with timestamps preserved.
- The assembly is format-agnostic; a JSON serializer is a trivial follow-on if
  the health-coach agent turns out to want structured input. Out of scope for
  the first cut.

### Data shape

- One block per exercise-session whose `beginning` falls in `[from, until]`
  (beginning/end, location, notes).
- Under each session, one block per set (beginning/end), listing its lines.
- Deleted docs excluded (existing queries already post-filter
  `::sm/deleted-at`).

## Validation

- [ ] Unit: assembler produces the expected Markdown for a fixture of 2
      sessions / 3 sets / 5 lines, including a superset (one set, two lines,
      two exercises).
- [ ] Unit: empty window produces an empty (non-erroring) export.
- [ ] Unit: a deleted session/set/line in the fixture does not appear.
- [ ] `just e2e-test exercise-export` — load the page, set a range, copy,
      assert the clipboard payload contains a known fixture session.
- [ ] `just lint-fast` and `just check` pass on touched files.

## Scope

### In scope

- Read-only, exercise-only export over a time window, Markdown,
  copy-to-clipboard.
- The exercise entities only: exercise-session, exercise-set, exercise-line
  (+ exercise labels).

### Out of scope

- No API, CLI, or MCP server (that's
  [ai-assistance.md](./ai-assistance.md)).
- No writes back to Gleanmo.
- No other entity types (no boulder, no mood/medication/etc.) — exercise is
  non-sensitive and is the only thing the health-coach project needs now.
- No auth surface beyond the existing app session (personal single-user app;
  export sits behind normal login).
- No streaming/pagination — a windowed export is bounded by the date range.

## Context

- Data model: `src/tech/jgood/gleanmo/schema/exercise_schema.clj`
  (exercise, exercise-session, exercise-set, exercise-line).
- Reads to reuse: `recent-sessions-for-user`, `sets-for-sessions`,
  `lines-for-sets` in `src/tech/jgood/gleanmo/db/queries.clj`. A windowed
  session scan (sessions with `beginning` in `[from, until]`) likely needs
  adding to `queries.clj` following the scan-then-pull pattern — do not inline
  it at the call site.
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
