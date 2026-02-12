---
title: "Gleanmo Roadmap"
goal: "Build a personal quantified-self system that is fast, reliable, and fully owned."
---

# Roadmap

## Current Focus

- [daily-focus.md](./daily-focus.md) - Daily planning ritual with progress stats and carry-forward
- [calendar.md](./calendar.md) - Year-at-a-glance calendar with event interactions and external sync
- [exercise.md](./exercise.md) - Exercise tracking with superset support and Airtable backfill
- [medication.md](./medication.md) - Medication logging with Airtable history import
- [data-migration-status.md](./data-migration-status.md) - Tracker for Airtable backfills and remaining imports
- [reading-airtable-spec.md](./reading-airtable-spec.md) - Airtable schema reference for reading migration
- [generic-viz.md](./generic-viz.md) - Generic visualizations for timestamp/interval entities
- [performance.md](./performance.md) - Performance monitoring and profiling dashboard
- [dashboard-performance.md](./dashboard-performance.md) - Home page dashboard performance improvements
- [backlog.md](./backlog.md) - Minor improvements without full work-unit docs

## Work Units

### Active

- [daily-focus.md](./daily-focus.md) - Daily planning ritual with progress stats and carry-forward
- [calendar.md](./calendar.md) - Year-at-a-glance calendar with event interactions and external sync
- [exercise.md](./exercise.md) - Exercise tracking with superset support and Airtable backfill
- [medication.md](./medication.md) - Medication logging with Airtable history import
- [data-migration-status.md](./data-migration-status.md) - Tracker for Airtable backfills and remaining imports
- [reading-airtable-spec.md](./reading-airtable-spec.md) - Airtable schema reference for reading migration
- [generic-viz.md](./generic-viz.md) - Generic visualizations for timestamp/interval entities
- [performance.md](./performance.md) - Performance monitoring and profiling dashboard
- [dashboard-performance.md](./dashboard-performance.md) - Home page dashboard performance improvements
- [backlog.md](./backlog.md) - Minor improvements without full work-unit docs
- [roam-integration.md](./roam-integration.md) - Project timers shipped; Roam metrics integration pending

### Ready

- [biff-upgrade-v1-9.md](./biff-upgrade-v1-9.md) - Upgrade Biff and task libs to at least v1.9.0, then validate XTDB/Agrona changes and Java 25 compatibility
- [dynamic-server-port.md](./dynamic-server-port.md) - Make the server dynamically choose a port to run on to support git worktree and multiple project development
- [reading-schema-proposal.md](./reading-schema-proposal.md) - Draft Malli schemas for reading entities
- [screenshot-runner.md](./screenshot-runner.md) - Authenticated screenshot capture for docs and CI
- [search-filter.md](./search-filter.md) - Text search tool for CRUD and timer view pages
- [timer-overlap-metrics.md](./timer-overlap-metrics.md) - Show overlap-aware daily and per-project timer metrics with clear unique vs. raw totals
- [today-reorder-performance.md](./today-reorder-performance.md) - Fix slow response when reordering items on the today page

### Draft

- [plausible-user-identification.md](./plausible-user-identification.md) - Add user identifiers to Plausible analytics to distinguish individuals
- [reading-tracker.md](./reading-tracker.md) - Lightweight Goodreads replacement with timer-backed sessions
- [life-chart.md](./life-chart.md) - Lifetime view with years as rows and weeks as cells
- [memento-mori.md](./memento-mori.md) - Finite-time visualization anchored to calendar data
- [mood.md](./mood.md) - Structured mood logging with Airtable backfill
- [pain.md](./pain.md) - Pain logs with Airtable history and CRUD/viz
- [bouldering.md](./bouldering.md) - Climbing sessions and problem attempts with Airtable backfill
- [cognitive-games.md](./cognitive-games.md) - Cognitive games to track performance trends
- [screenshots.md](./screenshots.md) - Visual changelog via periodic route screenshots
- [infrastructure.md](./infrastructure.md) - Database migration from Neon to DigitalOcean
- [task-activity-logs.md](./task-activity-logs.md) - Spawn time logs from tasks and link habits/calendar events to tasks
- [drawing-practice.md](./drawing-practice.md) - Timed drawing session habit with tracking and motivation tools
- [workflow-optimization.md](./workflow-optimization.md) - Minimize clicks for logging, fast landing page, and motivating stats
- [today-navigation.md](./today-navigation.md) - Page through dates on today view and richer focus date filtering
- [today-mobile-redesign.md](./today-mobile-redesign.md) - Redesign the today task page for better mobile ergonomics and responsiveness

### Archived

- [archived/query-optimization.md](./archived/query-optimization.md) - Batched related entity lookups - 93% query reduction
- [archived/rewrite-analysis.md](./archived/rewrite-analysis.md) - Language/stack evaluation (deferred - staying with Clojure)
- [archived/postgres-migration.md](./archived/postgres-migration.md) - Historical Postgres migration decision log
- [archived/backlog-2025-10-31.md](./archived/backlog-2025-10-31.md) - Archived backlog snapshot
- [archived/task-management.md](./archived/task-management.md) - Task system with behavioral signals and actionable lists

## Quick Ideas

- See [backlog.md](./backlog.md) for minor improvements not promoted to standalone work units yet
