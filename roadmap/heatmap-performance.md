---
title: "Heatmap Visualization Performance"
status: ready
description: "Speed up heatmap viz page via year-bounded index-only tuple scans, shared rel-cache, and lazy-loaded year cards"
tags: [performance, viz, xtdb, heatmap]
priority: high
created: 2026-07-06
updated: 2026-07-07
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
   The browser fires the visible year requests **in parallel** on load. Only the most recent **3 years** render eager-loading cards by default; earlier years render behind a "Show earlier years" expander (each card still lazy-loads when revealed). This caps concurrent scans per page load — contention on the prod box is the documented killer (see dashboard-performance.md "Verdict: Contention, Not Scan Cost").

   The initial scan also returns a **per-year count**, used to size each skeleton card and to label the expander (e.g. "Show 4 earlier years · 2,310 entries").

2. **Each year is bounded to its own range and never touches the doc store.** A new query `heatmap-data-for-user` scans only the requested date range (range predicates `[(>= ?sort range-start)] [(<= ?sort range-end)]` pushed into the where clause) and binds the needed attributes **directly in the `:where` clauses as index tuples** — e.g. `:find [?e ?ts ?habit-id]` with `[?e :habit-log/timestamp ?ts] [?e :habit-log/habit-ids ?habit-id]` — instead of any form of `pull`. All required attributes (timestamp, relationship ids) live in XTDB's indexes, so this avoids the remote Neon doc store entirely. Handle `::sm/deleted-at` (and `sensitive`/`archived` where present in the schema) the way `active-timers-for-user` does: a set-difference against a second minimal index scan of flagged ids, not a per-row `(not ...)` clause and not a doc post-filter. The query takes a general `[range-start range-end]` (not hardcoded to calendar years) so stats pages can reuse it.

3. **Relationship labels are batched.** The `::rel-cache` + `prewarm-relation-cache!` pattern from `overview.clj` is extracted to a shared ns and reused by viz. Each per-year request prewarms the cache with one `fetch-entities-by-ids` batch, then reads from the atom during grouping.

4. **Grouping happens once per year.** A shared `build-grouped-chart-data` helper produces the date→(count, labels) shape; both desktop and mobile ECharts configs derive from it. The duplicate reduce at lines 325-326 is gone.

5. **JSON payload is compact.** Drop `{:pretty true}` from ECharts serialization (`viz/routes.clj:303, 312`).

6. **Completed years are cached.** Every year except the current one is effectively immutable. The per-year endpoint sets an `ETag` (or `Cache-Control: private, max-age=...`) for past years so repeat visits only pay for the current year. Current year responses stay uncached.

7. **The route shows up on the perf dashboard.** Wrap `viz-page` and the new per-year handler in `defnp`/`p` profiling spans (the file currently has none).

### Tuple attributes per entity (schema-driven)

Attributes bound in the tuple query, computed dynamically from the entity's schema:
- `?e` / `:xt/id` (identity, for dedup of cardinality-many tuples)
- The temporal field (`<entity>/timestamp` for `:point` or `<entity>/beginning` for `:interval`; `:end` is unused by the chart)
- Each relationship field returned by `schema-utils/extract-relationship-fields` (e.g., `:habit-log/habit-ids` — cardinality-many binds one tuple per value; group by `?e`)

Excluded via set-difference against separate flagged-id scans (never bound in the main query):
- `::sm/deleted-at` (always)
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
- New `heatmap-data-for-user` (index-only tuples, general date range) and `years-with-data-for-user` (with per-year counts) queries in `db/queries.clj`
- Shared rel-cache helpers (extracted from `overview.clj`)
- Refactored `viz-page` + new per-year handler + route
- Skeleton-card UX with parallel lazy load, capped to last 3 years eager + "Show earlier years" expander
- ETag/cache headers on past-year responses
- Profiling spans
- Compact JSON

**Out of scope (deferred follow-ups — same root pattern elsewhere):**
- **Timer feeds** (`timer/routes.clj:83,158,307,354,374`): `fetch-completed-logs` and `today-logs` pull full history unbounded then `take`/filter in Clojure — pass `:limit` (over-fetch to survive post-filters) and date-bound `today-logs`
- **Stats pages** (`habit_log.clj:40`, `bm_log.clj:59`, `meditation_log.clj:63`, `medication_history.clj:192,207`): unbounded full-history pulls filtered by date in Clojure — migrate onto `heatmap-data-for-user` once it ships (hence the general date-range parameter)
- **Form dropdowns** (`crud/forms/inputs.clj:234,270`): full doc pulls just for id+label — index-only `[?e ?label]` tuple query
- User setting for default year range — current default is "last 3 years eager, rest behind expander"
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

1. **Query strategy: date-bounded index-only tuple scan.** Range predicates `[(>= ?sort range-start)] [(<= ?sort range-end)]` bound the scan; needed attributes are bound as tuples in `:where` (no `pull` of any kind), so the query is answered entirely from XTDB indexes and never hits the Neon doc store. Sparse flags (`deleted-at`, `sensitive`, `archived`) are excluded via set-difference against separate flagged-id scans, per the `active-timers-for-user` pattern. This supersedes an earlier "projection pull" design — XTDB 1.x `pull` may fetch the whole document regardless of pull spec (dashboard-performance.md:192-194), which would have gutted that approach; index tuples make the question moot.

2. **Multi-year UX: capped parallel lazy load.** Initial render shows eager-loading skeleton cards for the last 3 years; earlier years sit behind a "Show earlier years" expander whose cards lazy-load on reveal. This bounds concurrent scans per page load, since contention (not scan cost) is the known prod bottleneck. Server endpoint renders one year per request, bounded scan + prewarmed rel-cache; past-year responses carry cache headers.

3. **Rel-cache placement: shared ns.** Move `entity->label`, `collect-relation-ids`, `prewarm-relation-cache!`, `relationship-label` out of `overview.clj` into a shared location (likely a new `db/relation_labels.clj` or folded into `db/queries.clj`). Both `overview.clj` and `viz/routes.clj` consume the same helpers; no behavior change to the home page.

### Risks to validate post-deploy

- **Parallel requests per page load.** Capped at 3 eager year cards by default, but that's still 3 simultaneous bounded scans on the same snapshot, and contention is a known issue on the prod box (see dashboard-performance.md "Verdict: Contention, Not Scan Cost"). If this still hurts, options: sequence the requests via `hx-trigger="load delay:0.5s"` per card, or fall back to SSE streaming.
- **Cardinality-many tuple fan-out.** A log with N relationship ids yields N tuples from the index scan; grouping by `?e` reconstructs the row. Verify the fan-out doesn't outweigh the doc-fetch savings for entities with large relationship sets (habit-log habit-ids are typically 1-3, so expected fine).
- **Verify on prod, not local.** Per dashboard-performance.md, prod is XTDB `:jdbc` on Neon Postgres where doc pulls are network fetches — local won't show the win. Confirm via the perf dashboard that `viz-year-page` spans show index-only timings (no doc-store round trips).

### Implementation steps (suggested order)

1. Add `years-with-data-for-user` (with per-year counts) and `heatmap-data-for-user` (index-only tuples, general date range) to `db/queries.clj` (no caller changes yet)
2. Extract rel-cache helpers to shared ns; update `overview.clj` to consume from new location; verify home page unchanged
3. Build `viz-year-page` handler + add `"/year/:year"` route in `gen-routes`; cache headers on past years
4. Refactor `viz-page` to render skeleton cards: last 3 years eager `hx-get`, older years behind "Show earlier years" expander
5. Extract `build-grouped-chart-data` shared helper; both desktop and mobile configs derive from it
6. Add `defnp`/`p` profiling spans
7. Drop `{:pretty true}` from ECharts JSON
8. Run validation checklist; capture before/after screenshots
