---
title: "Heatmap Visualization Performance"
status: ready
description: "Speed up heatmap viz page via year-bounded projection scans, shared rel-cache, and parallel lazy-loaded year cards"
tags: [performance, viz, xtdb, heatmap]
priority: high
created: 2026-07-06
updated: 2026-07-06
---

# Heatmap Visualization Performance

## Intent

The heatmap visualization page (`/app/viz/<entity>`) is slow because it sidesteps every optimization shipped in [dashboard-performance.md](./dashboard-performance.md). For users with thousands of rows it still takes seconds to load, mostly waiting on network doc-fetches to the remote Neon doc store. This work brings the heatmap page to parity with the home timeline's perf characteristics and adds a better multi-year UX along the way.

## Root Cause

`viz-page` (`viz/routes.clj:314-350`) calls `queries/all-for-user-query` with **no `:limit`** (`viz/routes.clj:320`). The two-phase scan-then-pull optimization in `all-entities-for-user` (`queries.clj:302-348`) only saves work when `:limit` is bounded — without one, it walks **every id of the type the user has ever created** and pulls each as a full `(pull ?e [*])` doc. For a user with 5,000 habit-logs that's 5,000 network doc-fetches per page load.

Two secondary amplifiers:

1. **N+1 relationship-label lookups with no cache.** Both desktop and mobile config builders call `resolve-relationship-labels` → `resolve-entity-label` → `get-entity-by-id` per row, per relationship field (`viz/routes.clj:41-57`, invoked at 138/155/229/246). The home page fixed this exact pattern with a request-scoped `::rel-cache` + batched `prewarm-relation-cache!` (`overview.clj:358-397, 975`). The viz file has no such cache.
2. **Duplicate work.** The full history is grouped/processed twice (desktop + mobile configs, `viz/routes.clj:325-326`), then serialized as two pretty-printed JSON blobs inline in the HTML (`{:pretty true}`, lines 303, 312).

The phase-1 index scan itself (`build-id-scan-query`, `queries.clj:245-272`) is already optimized (no `(not ...)` clauses, post-filtered) and is not the bottleneck.

## Specification

After this work ships:

1. **Initial page load is fast.** `GET /app/viz/<entity>` performs an index-only scan that returns the distinct **year list** with data (no doc pulls). The page renders N skeleton "year cards" stacked vertically, each carrying an HTMX lazy loader:
   ```html
   <div class="year-card"
        hx-get="/app/viz/habit-log/year/2026"
        hx-trigger="load" hx-target="this" hx-swap="outerHTML">
     <h2>2026</h2>
     <div class="animate-pulse h-[200px]">Loading…</div>
   </div>
   ```
   The browser fires all N year requests **in parallel** on load.

2. **Each year is bounded to its own range.** A new query `heatmap-data-for-user` scans only the requested year (range predicates `[(>= ?sort jan-1)] [(<= ?sort dec-31)]` pushed into the where clause) and uses a **projection pull** of minimal attributes only — no `(pull ?e [*])`. For habit-log that's `[:xt/id :habit-log/timestamp :habit-log/habit-ids ::sm/deleted-at]` (4 attrs vs all).

3. **Relationship labels are batched.** The `::rel-cache` + `prewarm-relation-cache!` pattern from `overview.clj` is extracted to a shared ns and reused by viz. Each per-year request prewarms the cache with one `fetch-entities-by-ids` batch, then reads from the atom during grouping.

4. **Grouping happens once per year.** A shared `build-grouped-chart-data` helper produces the date→(count, labels) shape; both desktop and mobile ECharts configs derive from it. The duplicate reduce at lines 325-326 is gone.

5. **JSON payload is compact.** Drop `{:pretty true}` from ECharts serialization (`viz/routes.clj:303, 312`).

6. **The route shows up on the perf dashboard.** Wrap `viz-page` and the new per-year handler in `defnp`/`p` profiling spans (the file currently has none).

### Minimum attributes per entity (schema-driven)

Computed dynamically from the entity's schema:
- `:xt/id` (identity)
- The temporal field (`<entity>/timestamp` for `:point` or `<entity>/beginning` for `:interval`; `:end` is unused by the chart)
- Each relationship field returned by `schema-utils/extract-relationship-fields` (e.g., `:habit-log/habit-ids`)
- `::sm/deleted-at` (always, for post-filter)
- `<entity>/sensitive` and `<entity>/archived` **only if** present in the schema (habit-log has neither; bm-log, meditation-log etc. may)

## Validation

- [ ] `just lint-fast <changed-files>` after every Clojure edit
- [ ] `just check` at end of each implementation step
- [ ] `SCREENSHOT_PHASE=before just e2e-screenshot /app/viz/habit-log` — capture current slow single-year state
- [ ] `SCREENSHOT_PHASE=after just e2e-screenshot /app/viz/habit-log` — capture new skeleton-then-filled multi-year state
- [ ] Visit `/app/monitoring/performance` post-deploy; confirm `viz-page` and `viz-year-page` spans appear with bounded scan times
- [ ] Manual: `/app/viz/habit-log` loads instantly with skeletons; year cards fill in parallel; switching entities works for all 6 wired types (habit-log, bm-log, medication-log, reading-log, meditation-log, project-log)
- [ ] Manual: confirm tooltips still show relationship labels correctly (rel-cache prewarm produces identical labels to the old per-row lookup)
- [ ] If a query test namespace exists: `clj -M:dev test tech.jgood.gleanmo.db.queries-test`

## Scope

**In scope:**
- New `heatmap-data-for-user` and `years-with-data-for-user` queries in `db/queries.clj`
- Shared rel-cache helpers (extracted from `overview.clj`)
- Refactored `viz-page` + new per-year handler + route
- Skeleton-card UX with parallel lazy load
- Profiling spans
- Compact JSON

**Out of scope (deferred):**
- User setting for default year range (e.g., "last 3 years") — current default is "all years with data"; easy follow-up
- Collapsible/expandable year cards
- SSE/streaming alternative to parallel HTMX requests
- Cross-year rel-cache (current is per-request scope, matching `overview.clj`)
- Timeline/Gantt charts (separate work unit: [generic-viz.md](./generic-viz.md) Phase 4)

## Context

**Key files:**
- `src/tech/jgood/gleanmo/viz/routes.clj` — heatmap handlers (lines 41-57 N+1; 117-206 mobile config; 208-290 desktop config; 292-312 responsive render; 314-350 viz-page; 352-358 gen-routes)
- `src/tech/jgood/gleanmo/db/queries.clj` — `build-id-scan-query` (245-272), `fetch-entities-by-ids` (274-286), `all-entities-for-user` (302-348), `all-for-user-query` (444-476)
- `src/tech/jgood/gleanmo/app/overview.clj` — `entity->label` (347-356), `relationship-label` (358-368), `collect-relation-ids` (370-386), `prewarm-relation-cache!` (388-397), used at 963-982
- `src/tech/jgood/gleanmo/schema/utils.clj` — `extract-relationship-fields` (184-194)
- `src/tech/jgood/gleanmo/schema/habit_schema.clj` — reference schema for minimum-attrs computation

**Reference docs:**
- [dashboard-performance.md](./dashboard-performance.md) — canonical pattern doc for scan-then-pull, not-clause removal, rel-cache batching (lines 187-228, 368-388, 390-407)
- [generic-viz.md](./generic-viz.md) — original viz system work unit (now shipped)

**Pattern constraints (from AGENTS.md):**
- All DB reads via `db/queries.clj`; never `xt/q`/`xt/entity` outside that file
- New list/feed/count queries follow scan-then-pull: minimal index-only tuple scan, then `fetch-entities-by-ids`, post-filter sparse flags on pulled docs
- Avoid `(not ...)` clauses in hot-path scans (XTDB 1.x runs each as a per-row subquery)
- Use `resolve-user-settings` (ctx-first), not `get-user-settings`

## Notes

### Design decisions (resolved during planning)

1. **Query strategy: year-bounded projection scan.** Combines an index-only `[?e ?sort]` scan with `[(>= ?sort year-start)] [(<= ?sort year-end)]` range predicates and a projection `(pull ?e [...minimal...])`. Bounds the scan to one year and pulls only 4-6 attributes per row instead of all attributes for all time.

2. **Multi-year UX: parallel lazy load.** Initial render shows skeleton cards for all years-with-data; each fires its own HTMX request on load. Browser handles concurrency. Server endpoint renders one year per request, bounded scan + prewarmed rel-cache.

3. **Rel-cache placement: shared ns.** Move `entity->label`, `collect-relation-ids`, `prewarm-relation-cache!`, `relationship-label` out of `overview.clj` into a shared location (likely a new `db/relation_labels.clj` or folded into `db/queries.clj`). Both `overview.clj` and `viz/routes.clj` consume the same helpers; no behavior change to the home page.

### Risks to validate post-deploy

- **N parallel requests per page load.** For users with 5+ years this is 5+ simultaneous bounded scans on the same immutable snapshot. Each scan is bounded (one year) so per-request cost is small, but contention is a known issue on the prod box (see dashboard-performance.md "Verdict: Contention, Not Scan Cost"). If this hurts, options: cap to "last N years" by default, sequence the requests via `hx-trigger="load delay:0.5s"` per card, or fall back to SSE streaming.
- **Prod doc-store topology.** Per dashboard-performance.md, prod is XTDB `:jdbc` on Neon Postgres; doc pulls are network fetches. The projection pull still hits the network but with smaller payloads. Verify the actual win on prod via the perf dashboard, not just local.
- **Projection pull effectiveness on XTDB 1.x.** Per a note in dashboard-performance.md (line 192-194), XTDB 1.x `pull` may fetch the entire document from the doc store regardless of pull spec — if so, the projection pull's main win is smaller payloads over the wire, not fewer doc-store reads. The year-bounding is the bigger lever. Worth confirming empirically.

### Implementation steps (suggested order)

1. Add `years-with-data-for-user` and `heatmap-data-for-user` to `db/queries.clj` (no caller changes yet)
2. Extract rel-cache helpers to shared ns; update `overview.clj` to consume from new location; verify home page unchanged
3. Build `viz-year-page` handler + add `"/year/:year"` route in `gen-routes`
4. Refactor `viz-page` to render skeleton cards with parallel `hx-get` loaders
5. Extract `build-grouped-chart-data` shared helper; both desktop and mobile configs derive from it
6. Add `defnp`/`p` profiling spans
7. Drop `{:pretty true}` from ECharts JSON
8. Run validation checklist; capture before/after screenshots
