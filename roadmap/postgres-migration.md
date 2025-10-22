# Postgres Migration Roadmap

## Overview & Goals
- Replace XTDB 1 with Postgres (JSONB doc store + append-only event log) to simplify ops and accelerate visualization delivery.
- Keep migration lightweight: idempotent data port, direct code cutover, downtime acceptable.
- Preserve flexibility to reintroduce XTDB 2 or other temporal stores by retaining a full history event log.

## Target Architecture
- **event_log**: append-only table storing complete entity snapshots per write; indexed by `entity_id`, `entity_type`, `user_id`, and `tx_time` for replay or temporal queries.
- **entities**: current-state table keyed by `entity_id` with JSONB `doc`, soft delete via `deleted_at`, and GIN index for fast attribute lookups.
- **Read path**: default queries read from `entities` (real-time). Optional materialized views provide accelerators for charts that outgrow the baseline performance.
- **Write path**: each transaction inserts into `event_log` then upserts into `entities`, both wrapped in a single Postgres transaction.

## Implementation Phases
1. **Database & Schema Setup**
   - Provision Postgres (Neon prod + local container) and capture connection details in config/env.
   - Create `event_log` and `entities` tables with primary keys, required columns, GIN index on `entities.doc`, and supporting secondary indexes.
2. **Migration Utility**
   - Build an idempotent script that exports XTDB history, bulk-inserts into `event_log`, derives `entities`, and can resume safely (checkpoint on `entity_id` + `tx_time`).
   - Provide a dry-run mode and simple row-count validation so you can re-run until satisfied.
3. **Application Refactor**
   - Swap `db.queries` and `db.mutations` to use next.jdbc/HoneySQL against Postgres while keeping Malli validation untouched.
   - Remove XTDB-specific helpers (e.g., `xt/entity-history`); rewrite the small set of call sites to use event log queries when needed.
   - Update Biff components to initialize the Postgres datasource and inject it via `:biff/db`.
4. **Verification & Cutover**
   - Run migration script, spot-check entity counts and a handful of critical records.
   - Flip environment config to Postgres, deploy, and archive the XTDB storage directory for contingency.
   - Once stable, eliminate unused XTDB dependencies/config from the project.

## Active Workstream: Dev Branch Setup
- Create a feature branch and sketch the initial Postgres schema (`event_log`, `entities`) plus a minimal `migrations.sql` so the app can start against Postgres quickly.
- Follow Jacob O'Bryant's Postgres guide to add a `use-postgres` component, replacing `biff/use-xtdb` in `src/tech/jgood/gleanmo.clj`; document any gaps since we haven't built custom Biff components before.
- Stub or short-circuit XTDB-dependent call sites (queries, mutations, calendar flows) so the dev system boots even if functionality is incomplete; note every temporary stub in this doc for follow-up.
- Keep tests light: disable XTDB-specific fixtures for now and collect a list of suites that need dependency injection or pure-unit refactors.
- Decide how to handle background jobs and observability later—log current assumptions and confirm whether to port them once the core read/write paths work.
- Capture quick-start instructions (Docker/Postgres commands, env vars) in the repo once the environment stabilizes to speed up future sessions.

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
- Introduce materialized views only when specific chart queries exceed latency goals; each MV can double as a typed view for external tools.
- Add lightweight monitoring (slow query log, MV refresh durations) after cutover.
- Revisit architecture if temporal/as-of requirements grow, if MV refresh time exceeds five minutes, or if data volume crosses approximately one million events.
- Maintain export tooling (EDN/JSON dumps) to honor privacy and local-first workflows.

## Future Scalability
- **Partitioning:** Keep tables unpartitioned initially; plan to partition `event_log` (e.g., by month or quarter) and, if needed, `entities` (by entity type or hashed user buckets) once row counts exceed ~10 M or MV refreshes approach multi-minute runtimes.
- **Typed projections:** When growth or analytics demands outpace JSONB + GIN, backfill typed read-model tables from the append-only log, dual-write during transition, and migrate hot queries first.
- **Scale-out playbook:** Pair partitioning with autovacuum tuning, introduce read replicas or dedicated analytics nodes, and evaluate hybrid read models (e.g., XTDB 2) if temporal queries become common.
