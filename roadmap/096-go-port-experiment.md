---
title: "Go Port Experiment and Rewrite Preconditions"
status: draft
description: "Record of a time-boxed AI port to Go + SQL (2026-10-07) and what a real rewrite plan needs before it starts"
created: 2026-10-08
updated: 2026-10-08
tags: [rewrite, go, rust, sql, css, experiment]
priority: low
---

# Go Port Experiment and Rewrite Preconditions

## Intent

Record what a single unplanned AI session produced when asked to port Gleanmo,
along with the user's first impressions. This follows up
[006-rewrite-analysis](./archived/006-rewrite-analysis.md), which deferred a
rewrite until AI tooling had matured. The experiment answered one question
("how far does one session get?"). It does not decide whether to rewrite.
A rewrite would start only from a real plan, and this unit holds the inputs
for that plan.

## The Experiment (2026-10-07)

- **Prompt:** port all main functionality to Go with SQLite for dev and
  Postgres for prod. Keep the malli data shapes and the data-driven way of
  defining entities with generated CRUD. Keep every endpoint and the UX,
  improve performance, refine the style, and use a calmer palette. No
  questions were allowed and the session had a 4-hour cap.
- **Result:** `~/projects/gleanmo-go`. The session took about 75 minutes
  and produced about 15k lines of Go. The code has not been committed and is
  not a git repo.
  - All 28 malli schemas were ported field for field into a Go registry.
    Adding an entity is data only, and its table, side tables for sets,
    indexes, routes, forms, and list views are generated.
  - Every screen and route was ported, including timers, goals, workout,
    bouldering, the calendar, stats, and auth.
  - The Go tests pass on both SQLite and Postgres 16. They include
    schema-generated CRUD tests for every entity.
  - Pages render server-side in 1–4 ms against about 90 days of seeded data.
  - Simplified compared with the original: the goals ruler, comparisons, and
    rhythm panel; in-memory monitoring; approximate target suggestions.
  - No XTDB data migration was done and the Playwright e2e suite was not
    ported.

## Impressions (user, 2026-10-08)

1. **Tailwind is the wrong fit for a black-box port.** It adds a build step
   and a dependency, and it brings a hidden rule ("classes must be literal
   strings so the scanner finds them") for code nobody reads. Well-organized
   vanilla CSS is probably enough.
2. **The first thing tried was broken.** Creating an exercise inline from a
   fresh workout session did nothing. On the empty "No exercises yet" state,
   the inline form's Create button targeted an element that was not on the
   page, so HTMX silently refused to send the request. The Go tests posted
   to the endpoint directly, so they never saw it. A later session fixed it
   (`internal/app/inline_test.go`). The lesson is that server-side tests do
   not catch HTMX wiring bugs, and a black-box port needs browser-level
   acceptance tests.
3. **Make a real plan before unleashing AI on a port.**
4. **Go or Rust?** This is still open (see below).

## Preconditions for a Real Port

- [ ] **Acceptance suite as the spec.** Extend the Playwright e2e suite to
      cover each screen in [screen-inventory](./screen-inventory.md), and run
      it green against the Clojure app. A port is done when the same suite
      passes against it. This is the main defense against item 2.
- [ ] **CSS decision.** Choose vanilla CSS with design tokens as custom
      properties, native nesting, and a small set of component classes,
      kept in a few files by layer. Consider pairing this with the style
      work in [087-essential-information-redesign](./087-essential-information-redesign.md)
      so the port does not copy markup classes one for one.
- [ ] **Language decision** (see Open Questions).
- [ ] **Data migration path.** Export from XTDB, import into the SQL schema,
      and verify per-entity counts and spot checks. The Airtable importers
      (m003–m006) are the precedent.
- [ ] **Parity checklist.** Run it from the screen inventory, including the
      goals panels the experiment simplified.
- [ ] **Sequencing.** Decide whether the port replaces the Neon →
      DigitalOcean work in [017-infrastructure](./017-infrastructure.md).
      Moving to Postgres would also retire 039 (RocksDB lock) and most of
      the XTDB query-shape work. It must not become another plumbing
      detour from the insight and visualization features.

## Open Questions (draft only)

- **Go or Rust for a black-box port?** Initial assessment from the session:
  - Rust's stricter compiler does not help much here. The core is
    schema-driven, so entity data is dynamic (`map[string]any` in Go,
    `serde_json::Value` in Rust), and generic CRUD SQL is built at runtime.
    That means sqlx's compile-time query checks would not cover it.
  - The observed bug class (HTMX/client wiring) is invisible to both
    compilers.
  - Rust adds slower compile loops and a heavier web stack (axum, sqlx).
  - Leaning Go, unless Rust is wanted for its own sake. A cheap test would
    be to give a Rust port of one vertical slice (generic CRUD + one
    entity + the workout flow) the same time box, then compare correctness
    against the acceptance suite.
- Keep `gleanmo-go` as a reference or throw it away once a plan exists?
- Is SQLite in production (single instance, Litestream backups) viable, or
  is managed Postgres a requirement?

## Context

- Experiment code: `~/projects/gleanmo-go` (README covers the storage
  mapping and how to add an entity)
- Prior analysis: [006-rewrite-analysis](./archived/006-rewrite-analysis.md)
- The `db/queries.clj` public-signature rule in `AGENTS.md` was written as
  the interface spec for a database swap.
