---
title: "Gleanmo Roadmap"
goal: "Build a personal quantified-self system that is fast, reliable, and fully owned."
---

# Roadmap

## Current Focus

- [daily-focus.md](./daily-focus.md) - Daily planning ritual with progress stats and carry-forward
- [calendar.md](./calendar.md) - Year-at-a-glance calendar with event interactions and external sync
- [exercise.md](./exercise.md) - Exercise tracking with superset support and Airtable backfill
- [data-migration-status.md](./data-migration-status.md) - Tracker for Airtable backfills and remaining imports
- [performance.md](./performance.md) - Performance monitoring and profiling dashboard
- [dashboard-performance.md](./dashboard-performance.md) - Home page dashboard performance improvements
- [backlog.md](./backlog.md) - Minor improvements without full work-unit docs

## Work Units

### Active

- [daily-focus.md](./daily-focus.md) - Daily planning ritual with progress stats and carry-forward
- [calendar.md](./calendar.md) - Year-at-a-glance calendar with event interactions and external sync
- [exercise.md](./exercise.md) - Exercise tracking with superset support and Airtable backfill
- [data-migration-status.md](./data-migration-status.md) - Tracker for Airtable backfills and remaining imports
- [performance.md](./performance.md) - Performance monitoring and profiling dashboard
- [dashboard-performance.md](./dashboard-performance.md) - Home page dashboard performance improvements
- [backlog.md](./backlog.md) - Minor improvements without full work-unit docs
- [reading-tracker.md](./reading-tracker.md) - Lightweight Goodreads replacement with timer-backed sessions
- [reading-schema-proposal.md](./reading-schema-proposal.md) - Draft Malli schemas for reading entities

### Ready

(No work units currently meet the Definition of Ready. Promote from Draft after adding Scope, Context, Validation, and clearing Open Questions.)

### Draft

- [bm-log-bloating.md](./bm-log-bloating.md) - Add bloating tracking to bm-log schema with flexible modeling options
- [reading-log-pages.md](./reading-log-pages.md) - Add page number tracking to reading-log schema
- [config-cleanup.md](./config-cleanup.md) - Audit and consolidate configuration files to remove legacy artifacts
- [email-auth.md](./email-auth.md) - Restore email-based magic link authentication for user sign-in
- [inline-entity-creation.md](./inline-entity-creation.md) - Create related entities mid-form without losing context
- [schema-consistency.md](./schema-consistency.md) - Audit and standardize all Malli schemas for naming, field ordering, and conventions
- [form-tab-ordering.md](./form-tab-ordering.md) - Ensure logical tab order across all CRUD forms for keyboard accessibility
- [redirect-audit.md](./redirect-audit.md) - Audit all actions to implement intuitive redirects with query parameter support
- [biff-upgrade-v1-9.md](./biff-upgrade-v1-9.md) - Upgrade Biff and task libs to at least v1.9.0, then validate XTDB/Agrona changes and Java 25 compatibility
- [dynamic-server-port.md](./dynamic-server-port.md) - Make the server dynamically choose a port to run on to support git worktree and multiple project development
- [generic-viz.md](./generic-viz.md) - Generic visualizations for timestamp/interval entities
- [reading-airtable-spec.md](./reading-airtable-spec.md) - Airtable schema reference for reading migration
- [roam-integration.md](./roam-integration.md) - Project timers shipped; Roam metrics integration pending
- [screenshot-runner.md](./screenshot-runner.md) - Authenticated screenshot capture for docs and CI
- [search-filter.md](./search-filter.md) - Text search tool for CRUD and timer view pages
- [timer-overlap-metrics.md](./timer-overlap-metrics.md) - Show overlap-aware daily and per-project timer metrics with clear unique vs. raw totals
- [today-reorder-performance.md](./today-reorder-performance.md) - Fix slow response when reordering items on the today page
- [local-dev-db-locking.md](./local-dev-db-locking.md) - RocksDB file lock prevents running REPL and CLI migrations concurrently
- [pwa-experience.md](./pwa-experience.md) - Improve progressive web app experience for native-like feel on iOS and Android
- [entity-merge.md](./entity-merge.md) - Combine logs from duplicate entities into one target entity
- [ui-juice.md](./ui-juice.md) - Micro-interactions, animations, and haptic feedback for delight
- [plausible-user-identification.md](./plausible-user-identification.md) - Add user identifiers to Plausible analytics to distinguish individuals
- [life-chart.md](./life-chart.md) - Lifetime view with years as rows and weeks as cells
- [memento-mori.md](./memento-mori.md) - Finite-time visualization anchored to calendar data
- [mood.md](./mood.md) - Structured mood logging with Airtable backfill
- [bouldering.md](./bouldering.md) - Climbing sessions and problem attempts with Airtable backfill
- [cognitive-games.md](./cognitive-games.md) - Cognitive games to track performance trends
- [screenshots.md](./screenshots.md) - Visual changelog via periodic route screenshots
- [infrastructure.md](./infrastructure.md) - Database migration from Neon to DigitalOcean
- [task-activity-logs.md](./task-activity-logs.md) - Spawn time logs from tasks and link habits/calendar events to tasks
- [drawing-practice.md](./drawing-practice.md) - Timed drawing session habit with tracking and motivation tools
- [airtable-metadata-consistency.md](./airtable-metadata-consistency.md) - Retroactively align airtable lineage fields across all migrated entity schemas
- [workflow-optimization.md](./workflow-optimization.md) - Dashboard quick reference, minimize clicks for logging, and motivating stats
- [activity-timeline.md](./activity-timeline.md) - Chronological timeline view with day separation and quick edit access
- [today-navigation.md](./today-navigation.md) - Page through dates on today view and richer focus date filtering
- [today-mobile-redesign.md](./today-mobile-redesign.md) - Redesign the today task page for better mobile ergonomics and responsiveness
- [today-ux-polish.md](./today-ux-polish.md) - Improve task completion feedback and add project selection to quick-add
- [exercise-insights.md](./exercise-insights.md) - Visual daily exercise summary with muscle heatmap and session stats

### Done

- [timer-stale-start-time.md](./timer-stale-start-time.md) - Fix timer starting with old timestamp when PWA has been idle on timers page

### Archived

- [archived/medication.md](./archived/medication.md) - Medication logging with Airtable history import
- [archived/pain.md](./archived/pain.md) - Pain logs (unified into symptom schema 2026-02-21)
- [archived/query-optimization.md](./archived/query-optimization.md) - Batched related entity lookups - 93% query reduction
- [archived/rewrite-analysis.md](./archived/rewrite-analysis.md) - Language/stack evaluation (deferred - staying with Clojure)
- [archived/postgres-migration.md](./archived/postgres-migration.md) - Historical Postgres migration decision log
- [archived/backlog-2025-10-31.md](./archived/backlog-2025-10-31.md) - Archived backlog snapshot
- [archived/task-management.md](./archived/task-management.md) - Task system with behavioral signals and actionable lists

## Quick Ideas

- See [backlog.md](./backlog.md) for minor improvements not promoted to standalone work units yet
