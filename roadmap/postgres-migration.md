# Postgres Migration Roadmap

## Overview & Goals
- Why now: XTDB’s temporal features are barely used, the immutable history adds friction, the app needs faster iteration plus easier inspection in standard Postgres tools and BI clients, and moving off Neon/AWS reduces dependency risk while cutting monthly costs with a DigitalOcean managed Postgres instance.
- Replace XTDB 1 with Postgres using a single `entities` JSONB table to reduce friction and speed up dashboard delivery.
- Keep the cutover lightweight: idempotent data port of latest entity state, direct code swap, downtime acceptable.
- Preserve optional paths to richer history (event log, typed projections) only if future requirements demand them.

## Target Architecture
- **entities**: single table keyed by `entity_id` with `user_id`, `entity_type`, `doc` (JSONB), `created_at`, `updated_at`, and `deleted_at` (soft delete). Add a GIN index on `doc` plus btree indexes on lookup columns.
- **Materialized views**: opt-in accelerators for heavy analytics (e.g., monthly habit aggregates). Refresh ad hoc or on a scheduled job as patterns emerge.
- **Read path**: default queries load JSONB documents from `entities`, falling back to MVs when the UI needs pre-aggregated slices.
- **Write path**: each transaction upserts into `entities`, updating `updated_at` and clearing `deleted_at` as needed.

## Implementation Phases
1. **Database & Schema Setup**
   - Provision Postgres (Neon prod + local container) and capture connection details in config/env.
   - Create `entities` table, required indexes, and a minimal migrations file so schema is reproducible.
2. **Migration Utility**
   - Export the latest XTDB entity documents and bulk-insert into `entities`.
   - Provide a dry-run mode plus row-count validation so the script can rerun safely.
3. **Application Refactor**
   - Swap `db.queries` and `db.mutations` to use next.jdbc/HoneySQL against Postgres while keeping Malli validation untouched.
   - Remove XTDB-specific helpers (e.g., `xt/entity-history`) and refactor the few call sites that referenced historical state.
   - Update Biff components to initialize the Postgres datasource and inject it via `:biff/db`.
4. **Verification & Cutover**
   - Run the migration script, spot-check entity counts and a handful of critical records.
   - Flip environment config to Postgres, deploy, and archive the XTDB storage directory for contingency.
   - Once stable, eliminate unused XTDB dependencies/config from the project.

## Active Workstream: Dev Branch Setup
- Create a feature branch, define the `entities` schema, and add a lightweight `migrations.sql`. ✅ `resources/migrations/001_init.sql` establishes `schema_migrations` and `entities`.
- Follow Jacob O'Bryant's Postgres guide to add a `use-postgres` component, replacing `biff/use-xtdb` in `src/tech/jgood/gleanmo.clj`; document any gaps since we haven't built custom Biff components before.
- Stub or short-circuit XTDB-dependent call sites (queries, mutations, calendar flows) so the dev system boots even if functionality is incomplete; note every temporary stub in this doc for follow-up.
- Keep tests light: disable XTDB-specific fixtures for now and collect a list of suites that need dependency injection or pure-unit refactors.
- Decide how to handle background jobs and observability later—log current assumptions and confirm whether to port them once the core read/write paths work.
- Capture quick-start instructions (Docker/Postgres commands, env vars) in the repo once the environment stabilizes to speed up future sessions. ✅ Added Docker Compose service and README notes.
- Next steps: add JSON encode/decode helpers for namespaced entity maps, wire a dev `next.jdbc` datasource, and smoke-test round-tripping before refactoring queries.

## Local Development Setup
- Run Postgres locally in Docker (e.g., `docker compose up postgres`) with a named volume so the dev database persists between sessions.
- Point tests and REPL at the local container; use a reset helper to truncate tables between runs if needed.
- Optionally script container lifecycle commands inside `dev/tasks` for a single-command local setup.

## Cutover Plan
1. Back up current XTDB storage plus Neon database snapshots.
2. Execute the migration utility (dev → verify → prod) until row counts match expectations.
3. Update secrets/config to Postgres, restart the app, and validate key flows (CRUD screens, calendar heatmaps, dashboards).
4. Monitor logs for errors; keep XTDB snapshot available for 90 days before final decommissioning.

## Follow-ups & Tripwires
- Introduce materialized views when specific dashboard queries need sub-second responses; document refresh cadence per view.
- Add lightweight monitoring (slow query log, MV refresh durations) after cutover.
- Revisit architecture if temporal/as-of requirements grow, if MV refresh time exceeds five minutes, or if data volume crosses approximately one million entities.
- Maintain export tooling (EDN/JSON dumps) to honor privacy and local-first workflows; consider adding an append-only change log only if real audit requirements surface.

## Future Scalability
- **Partitioning:** Keep the `entities` table unpartitioned initially; evaluate partitioning or table sharding once row counts exceed ~10 M or MV refreshes approach multi-minute runtimes.
- **Typed projections:** When growth or analytics demands outpace JSONB + GIN, backfill typed read-model tables or additional MVs from `entities`, dual-write during transition, and migrate hot queries first.
- **Scale-out playbook:** Pair partitioning with autovacuum tuning, introduce read replicas or dedicated analytics nodes, and revisit hybrid read models (e.g., XTDB 2, event logs) if temporal queries become common.
