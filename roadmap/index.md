---
title: "Gleanmo Roadmap"
goal: "Get off Neon/Hikari pain and Airtable dependency while keeping Gleanmo fast, reliable, and fully owned."
---

# Roadmap

## Current Focus

- [017-infrastructure.md](./017-infrastructure.md) - Database migration from Neon to DigitalOcean (now the top priority — see note below)
- [014-data-migration-status.md](./014-data-migration-status.md) - Tracker for Airtable backfills and remaining imports (Airtable exit complete; retirement housekeeping left)
- [063-qol-quick-actions.md](./063-qol-quick-actions.md) - Navigation & logging QOL (analytics-backed; items 3-5 remain)
- [039-local-dev-db-locking.md](./039-local-dev-db-locking.md) - RocksDB file lock prevents running REPL and CLI migrations concurrently
- [078-data-export.md](./078-data-export.md) - One `/app/export` page: generic per-entity exports plus named composite exports, feeding the health-coach agent by copy-paste (interim to 061-ai-assistance.md)
- [075-exercise-export.md](./075-exercise-export.md) - The exercise composite (session → set → line) registered in the data-export page

## Prioritization Lens

Immediate work should remove infrastructure friction or reduce dependency on Airtable as a system of record. Prioritize in this order:

1. Move production database hosting off Neon and stabilize connection behavior.
2. Finish remaining Airtable imports and make Gleanmo the source of truth.
3. Fix local migration workflow friction that slows data-porting work.
4. Preserve lineage and cleanup metadata only where it helps migration confidence.
5. Resume product polish after the database and Airtable exit paths are complete.

**Airtable exit complete (2026-07-31).** m003–m006 all ran against production
on same-day exports: 1,132 symptom-logs, 24 mood-logs, 277 boulder-problems /
84 sessions / 676 attempts, and 471 exercises / 2,563 sessions / 10,190 sets +
lines. No Airtable-backed dataset remains — see
[014-data-migration-status.md](./014-data-migration-status.md). Remaining exit work is
retirement housekeeping: a final archive export and shutting the base down.
With item 2 of the lens discharged, priority 1 (Neon → DigitalOcean,
[017-infrastructure.md](./017-infrastructure.md)) is now the top of the list, and the
unfinished QOL batch items 3-5 are no longer competing with a migration.

## Secondary Focus

- [061-ai-assistance.md](./061-ai-assistance.md) - First task priority: conversational backlog cleanup and approved updates, followed by a one-week adoption check
- [021-performance.md](./021-performance.md) - Performance monitoring and profiling dashboard
- [013-dashboard-performance.md](./013-dashboard-performance.md) - Home page dashboard performance improvements
- [012-daily-focus.md](./012-daily-focus.md) - Existing daily task workflow; evaluate a manageable chosen list after conversational cleanup
- [010-calendar.md](./010-calendar.md) - Year-at-a-glance calendar with event interactions and external sync
- [008-backlog.md](./008-backlog.md) - Minor improvements without full work-unit docs

## Task Product Sequence (2026-09-07)

Task use stopped after the backlog became overwhelming.
Within task work, prioritize renewed use before expanding organization features.
This sequence does not reorder the infrastructure priorities above.

1. Deliver scoped agent access for conversational cleanup, next-action selection, and approved batch updates in [061-ai-assistance.md](./061-ai-assistance.md).
2. Evaluate one week of normal use for voluntary return, useful task choices, and reduced review effort.
3. Address observed daily friction through [012-daily-focus.md](./012-daily-focus.md), including a proposed review of automatic carry-forward.
4. Add [offline task capture](./043-pwa-experience.md#offline-task-capture-proposed-follow-up) once the workflow proves useful.

Defer Things-style tags, project headings, recurrence, and additional statistics until use establishes their value.
Existing task polish drafts remain candidates; they do not precede the adoption check unless a defect blocks that trial.
PWA notification scaffolding does not establish working reminder delivery.

## Work Units

### Active

- [017-infrastructure.md](./017-infrastructure.md) - Database migration from Neon to DigitalOcean
- [014-data-migration-status.md](./014-data-migration-status.md) - Tracker for Airtable backfills and remaining imports
- [015-exercise.md](./015-exercise.md) - Exercise tracking with superset support and Airtable backfill
- [021-performance.md](./021-performance.md) - Performance monitoring and profiling dashboard
- [013-dashboard-performance.md](./013-dashboard-performance.md) - Home page dashboard performance improvements
- [012-daily-focus.md](./012-daily-focus.md) - Existing daily task workflow; further changes depend on the AI-assisted adoption check
- [010-calendar.md](./010-calendar.md) - Year-at-a-glance calendar with event interactions and external sync
- [008-backlog.md](./008-backlog.md) - Minor improvements without full work-unit docs
- [025-reading-tracker.md](./025-reading-tracker.md) - Lightweight Goodreads replacement with timer-backed sessions
- [024-reading-schema-proposal.md](./024-reading-schema-proposal.md) - Draft Malli schemas for reading entities
- [066-unified-timer-page.md](./066-unified-timer-page.md) - One timer workspace: all running timers, search-to-start, one-tap start/stop without form bounces (implemented 2026-07-27; e2e/screenshot validation pending)
- [071-timer-running-flag.md](./071-timer-running-flag.md) - Replace the two-full-scan set difference behind active timers with an indexed flag derived at write time, reconciled daily (built and tested 2026-08-04; deploy + m007 backfill, prod confirmation pending)

### Ready

- [075-exercise-export.md](./075-exercise-export.md) - The exercise composite export (session → set → line as nested Markdown or JSON, denormalized to CSV); depends on 078-data-export.md
- [074-query-shape-audit.md](./074-query-shape-audit.md) - Find clauses that cost without narrowing, and ranges that never prune, across db/queries.clj
- [063-qol-quick-actions.md](./063-qol-quick-actions.md) - Navigation & logging QOL: sidebar reorder, stop-in-place timers, boulder problem discoverability, home quick actions
- [048-inline-entity-creation.md](./048-inline-entity-creation.md) - Create related entities mid-form without losing context (executed as item 3 of 063-qol-quick-actions.md)
- [059-heatmap-performance.md](./059-heatmap-performance.md) - Heatmap viz page perf via year-bounded projection scans, shared rel-cache, parallel lazy-loaded year cards
- [027-screenshot-runner.md](./027-screenshot-runner.md) - Manifest-driven series capture for the visual timeline (manifest + runner + agent policy shipped; Biff-task glue + CI archive pending)
- [069-crud-relation-select-scale.md](./069-crud-relation-select-scale.md) - Relationship selects render every related entity; ~10k options froze the exercise-line edit form on mobile
- [079-htmx-navigation-consistency.md](./079-htmx-navigation-consistency.md) - One navigation model: boost all 42 native form POSTs through htmx, delete the hand-rolled double-submit guard in favor of hx-sync, drop the two unused vendor scripts

### Draft

- [078-data-export.md](./078-data-export.md) - Export page, generic per-entity exporter, and the composite registry, in Markdown/CSV/JSON; interim to the full ai-assistance API/CLI/MCP
- [076-performance-regression-tracking.md](./076-performance-regression-tracking.md) - Make performance comparable across deploys — scheduled snapshots, cross-instance aggregation, per-SHA grouping, and a synthetic benchmark so low traffic still yields data
- [077-scheduled-work-multi-instance.md](./077-scheduled-work-multi-instance.md) - Scheduled tasks, tx listeners and queues all run per-container with no singleton guarantee — make them safe before the app ever scales past one instance
- [070-screenshot-series-heartbeat.md](./070-screenshot-series-heartbeat.md) - Capture the visual timeline in CI against a fixed fixture, published to an orphan branch
- [020-mood.md](./020-mood.md) - Structured mood logging with Airtable backfill
- [009-bouldering.md](./009-bouldering.md) - Climbing sessions and problem attempts with Airtable backfill
- [039-local-dev-db-locking.md](./039-local-dev-db-locking.md) - RocksDB file lock prevents running REPL and CLI migrations concurrently
- [049-schema-consistency.md](./049-schema-consistency.md) - Audit and standardize all Malli schemas for naming, field ordering, and conventions
- [042-entity-merge.md](./042-entity-merge.md) - Combine logs from duplicate entities into one target entity
- [072-exercise-session-location-relation.md](./072-exercise-session-location-relation.md) - Replace the free-text location string on exercise-session with a proper location relation, matching every other log entity
- [037-biff-upgrade-v1-9.md](./037-biff-upgrade-v1-9.md) - Upgrade Biff and task libs to at least v1.9.0, then validate XTDB/Agrona changes and Java 25 compatibility
- [031-dynamic-server-port.md](./031-dynamic-server-port.md) - Make the server dynamically choose a port to run on to support git worktree and multiple project development
- [016-generic-viz.md](./016-generic-viz.md) - Generic visualizations for timestamp/interval entities
- [029-search-filter.md](./029-search-filter.md) - Text search tool for CRUD and timer view pages
- [036-timer-overlap-metrics.md](./036-timer-overlap-metrics.md) - Show overlap-aware daily and per-project timer metrics with clear unique vs. raw totals
- [052-activity-timeline.md](./052-activity-timeline.md) - Chronological timeline view with day separation and quick edit access
- [073-home-timeline-interval-times.md](./073-home-timeline-interval-times.md) - Render an interval's stop time alongside its start time in the home activity timeline, so a row shows its full span
- [032-task-activity-logs.md](./032-task-activity-logs.md) - Spawn time logs from tasks and link habits/calendar events to tasks
- [035-workflow-optimization.md](./035-workflow-optimization.md) - Dashboard quick reference, minimize clicks for logging, and motivating stats
- [054-bm-log-bloating.md](./054-bm-log-bloating.md) - Add bloating tracking to bm-log schema with flexible modeling options
- [055-reading-log-pages.md](./055-reading-log-pages.md) - Add page number tracking to reading-log schema
- [050-config-cleanup.md](./050-config-cleanup.md) - Audit and consolidate configuration files to remove legacy artifacts
- [060-keyboard-navigation.md](./060-keyboard-navigation.md) - Audit of keyboard accessibility across forms and index views, with phased remediation roadmap
- [044-form-tab-ordering.md](./044-form-tab-ordering.md) - Ensure logical tab order across all CRUD forms for keyboard accessibility (superseded by 060-keyboard-navigation.md)
- [045-redirect-audit.md](./045-redirect-audit.md) - Audit all actions to implement intuitive redirects with query parameter support
- [023-reading-airtable-spec.md](./023-reading-airtable-spec.md) - Airtable schema reference for reading migration
- [026-roam-integration.md](./026-roam-integration.md) - Project timers shipped; Roam metrics integration pending
- [034-today-reorder-performance.md](./034-today-reorder-performance.md) - Fix slow response when reordering items on the today page
- [043-pwa-experience.md](./043-pwa-experience.md) - Verify PWA scaffolding; offline task capture follows the task adoption check
- [064-timer-dashboard-inline-create.md](./064-timer-dashboard-inline-create.md) - True inline parent creation on timer dashboards (follow-up to 048-inline-entity-creation.md)
- [041-ui-juice.md](./041-ui-juice.md) - Micro-interactions, animations, and haptic feedback for delight
- [067-global-action-modal.md](./067-global-action-modal.md) - One shared HTMX modal shell for confirm-and-act prompts, replacing per-page overlays and inline prompt slots
- [022-plausible-user-identification.md](./022-plausible-user-identification.md) - Add user identifiers to Plausible analytics to distinguish individuals
- [018-life-chart.md](./018-life-chart.md) - Lifetime view with years as rows and weeks as cells
- [019-memento-mori.md](./019-memento-mori.md) - Finite-time visualization anchored to calendar data
- [011-cognitive-games.md](./011-cognitive-games.md) - Cognitive games to track performance trends
- [028-screenshots.md](./028-screenshots.md) - Visual changelog via periodic route screenshots
- [030-drawing-practice.md](./030-drawing-practice.md) - Timed drawing session habit with tracking and motivation tools
- [033-today-navigation.md](./033-today-navigation.md) - Page through dates on today view and richer focus date filtering
- [058-task-focus-last-edited-filter.md](./058-task-focus-last-edited-filter.md) - Add sort and filter controls for last-edited tasks on the focus page
- [038-today-mobile-redesign.md](./038-today-mobile-redesign.md) - Redesign the today task page for better mobile ergonomics and responsiveness
- [040-today-ux-polish.md](./040-today-ux-polish.md) - Improve task completion feedback and add project selection to quick-add
- [046-exercise-insights.md](./046-exercise-insights.md) - Visual daily exercise summary with muscle heatmap and session stats
- [056-cronometer-integration.md](./056-cronometer-integration.md) - Pull nutrition and body composition from Cronometer: reverse-engineered mobile API for recent days, CSV import for the backfill (phase 0 session-safety check gates the API path)
- [061-ai-assistance.md](./061-ai-assistance.md) - Restore task use through conversational cleanup, scoped agent access, and approved batch changes
- [080-relation-defaults.md](./080-relation-defaults.md) - Curated per-relation defaults that prefill log fields from the related entity (meditation position first; medication dose, book format, exercise unit to follow)

### Done

- [051-email-auth.md](./archived/051-email-auth.md) - Passwordless email sign-in — magic links and 6-digit codes — through MailerSend under reCAPTCHA; shipped 2026-07-04
- [068-labeled-rating-scales.md](./archived/068-labeled-rating-scales.md) - Store ratings as numbers, pick them by label (`:crud/scale`); shipped 2026-07-31 ahead of the m003/m006 prod imports
- [065-navigation-redesign.md](./archived/065-navigation-redesign.md) - Layered navigation, shared page shell across every page, and the mobile tab bar (absorbed 062-mobile-tab-bar.md)
- [062-mobile-tab-bar.md](./archived/062-mobile-tab-bar.md) - Absorbed into 065-navigation-redesign.md
- [053-airtable-metadata-consistency.md](./archived/053-airtable-metadata-consistency.md) - Settled: the four deployed entities need no action
- [057-auth-expired-home-layout.md](./archived/057-auth-expired-home-layout.md) - Fix home page showing login form inside authenticated layout when session expires
- [047-timer-stale-start-time.md](./archived/047-timer-stale-start-time.md) - Fix timer starting with old timestamp when PWA has been idle on timers page

### Archived

- [archived/002-medication.md](./archived/002-medication.md) - Medication logging with Airtable history import
- [archived/003-pain.md](./archived/003-pain.md) - Pain logs (unified into symptom schema 2026-02-21)
- [archived/005-query-optimization.md](./archived/005-query-optimization.md) - Batched related entity lookups - 93% query reduction
- [archived/006-rewrite-analysis.md](./archived/006-rewrite-analysis.md) - Language/stack evaluation (deferred - staying with Clojure)
- [archived/004-postgres-migration.md](./archived/004-postgres-migration.md) - Historical Postgres migration decision log
- [archived/001-backlog-2025-10-31.md](./archived/001-backlog-2025-10-31.md) - Archived backlog snapshot
- [archived/007-task-management.md](./archived/007-task-management.md) - Task system with behavioral signals and actionable lists

## Quick Ideas

- See [008-backlog.md](./008-backlog.md) for minor improvements not promoted to standalone work units yet
