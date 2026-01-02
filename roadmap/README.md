# Gleanmo Roadmap

This directory contains detailed planning and requirements documentation for Gleanmo features and improvements.

Canonical state lives in `index.md`. This README is the catalog and structure guide.

## Roadmap Structure
- `index.md`: current focus, active links, and short status summary (keep it short).
- `roadmap/*.md`: work units with a short "Work Unit Summary" block at the top.
- `_template.md`: starting point for new work units.
- `log.md`: optional, append-only session log (create when needed).

**Rules for work units**
- Start each file with the "Work Unit Summary" section (status, problem/intent, constraints, approach, open questions).
- Avoid subtasks or checklists; keep details in narrative notes.
- Status should be one of: `idea`, `active`, `paused`, `done`, `dropped`.

## Catalog

## Active Features

### [Calendar](./calendar.md)
**Status**: Core functionality complete, polish in progress

Year-at-a-glance calendar with event creation, visualization, and timezone-aware queries. Currently working on UI polish (event shapes, icons, navigation) and planning external calendar sync.

**Key sections:** Completed features, UI/UX improvements, external sync (iCal/Google), recurring events architecture

### [Project Timers + Roam Metrics](./roam-integration.md)
**Status**: Project timers shipped; Roam metrics integration pending

Timer-based project tracking is live (schema-driven prefill, auto-refresh, redirects). This doc now mainly tracks the future Roam Research metrics integration and related analytics.

**Key sections:** MVP schema, timer functionality, Roam integration, CRUD form enhancements, outstanding issues

### [Generic Visualization System](./generic-viz.md)
**Status**: Phase 1–3 live, timeline view pending

Automatic calendar heatmaps now ship for any timestamp/interval entity using schema-driven route generation. Next up: timeline/Gantt charts and advanced filters.

**Key sections:** Temporal pattern detection, chart type mapping, route generation, implementation phases

### [Performance Monitoring](./performance.md)
**Status**: Profiling & dashboard live (textual MVP)

Tufte instrumentation collects per-route metrics, persists them every minute, and surfaces snapshots on an in-app monitoring page. Visual charting and cross-instance rollups remain future work.

**Key sections:** Architecture, Tufte integration, metric aggregation, dashboard implementation

## Planned Features

### [Life Chart Calendar](./life-chart.md)
Lifetime visualization with years as rows and weeks as columns. Track life periods (education, work, relationships, residence) and integrate with calendar events for a complete temporal view of life.

**Key sections:** Visual structure, core entities, period categories, interactions, technical implementation

### [Task Management (Projects + Tasks + Time)](./task-management.md)
Lightweight tasks linked to projects with optional time tracking and a reset-friendly Now/Later workflow.

**Key sections:** Task lifecycle, project linkage, reset/archival behaviors, open questions

### [Memento Mori Calendar](./memento-mori.md)
Minimal time-horizon view that highlights finite time with optional calendar overlays.

**Key sections:** View model, overlays, integration with life chart/calendar

### [Exercise Tracking](./exercise.md)
Comprehensive workout logging with superset support. Four-entity schema (exercise, session, set, rep) designed for flexible data entry and Airtable migration.

**Key sections:** Core entities, data entry workflow, superset support, schema issues, migration strategy

### [Reading Tracker](./reading-tracker.md)
Lightweight Goodreads replacement focused on quick book capture with metadata lookup and timer-backed reading sessions, with clear paths for later expansion.

**Key sections:** Minimal schema, metadata lookup flow, timer integration, phased rollout

### [Automated Screenshots](./screenshots.md)
Visual changelog of application development through automated route screenshots. Supports local development, GitHub Actions, and Docker deployment approaches.

**Key sections:** Implementation approaches, route discovery, storage organization, configuration options

### [Data Migration Status](./data-migration-status.md)
**Status**: Tracker for Airtable/backfill readiness

Summary of what’s already migrated (habits, BM), what’s live but needs Airtable backfill (medication), and what’s still unrepresented (exercise, pain, mood, reading, bouldering, project time logs).

## Improvements & Refactoring

### [Backlog](./backlog.md)
Minor improvements and enhancements including CRUD system redesign, dark-themed tables, fuzzy search components, and entity-specific fixes.

**Key sections:** CRUD redesign, generic components, medication/meditation improvements

### [Infrastructure Move (Neon to DigitalOcean)](./infrastructure.md)
Database hosting migration plan, cutover strategy, and rollback considerations.

**Key sections:** Hosting choice, data transfer plan, backup/rollback

## Quick Reference

| Feature | Status | Priority | File |
|---------|--------|----------|------|
| Calendar Polish | In Progress | High | [calendar.md](./calendar.md) |
| Project Timers + Roam Metrics | Timers shipped; Roam metrics pending | High | [roam-integration.md](./roam-integration.md) |
| Task Management | Planned | High | [task-management.md](./task-management.md) |
| Life Chart | Planned | Medium | [life-chart.md](./life-chart.md) |
| Memento Mori | Planned | Medium | [memento-mori.md](./memento-mori.md) |
| Exercise Tracking | Planned | Medium | [exercise.md](./exercise.md) |
| Reading Tracker | Planned | High | [reading-tracker.md](./reading-tracker.md) |
| Generic Viz | Phase 1–3 Live | Medium | [generic-viz.md](./generic-viz.md) |
| Screenshots | Planned | Low | [screenshots.md](./screenshots.md) |
| Performance | Profiling Dashboard Live | Low | [performance.md](./performance.md) |
| CRUD Redesign | Future | Medium | [backlog.md](./backlog.md) |
| Data Migration Status | Tracker | Medium | [data-migration-status.md](./data-migration-status.md) |
| Infrastructure Move | Planned | Medium | [infrastructure.md](./infrastructure.md) |

## Navigation

- **Active work**: [calendar.md](./calendar.md), [roam-integration.md](./roam-integration.md), [generic-viz.md](./generic-viz.md), [performance.md](./performance.md)
- **Next features**: [task-management.md](./task-management.md), [life-chart.md](./life-chart.md), [memento-mori.md](./memento-mori.md), [exercise.md](./exercise.md), [reading-tracker.md](./reading-tracker.md), [screenshots.md](./screenshots.md)
- **Minor improvements**: [backlog.md](./backlog.md), [infrastructure.md](./infrastructure.md)

## ✅ Status Update (2025-10-23)
- Calendar polish picks up header navigation, event editing from the grid, and better past-day treatments; review the new implementation notes inside `calendar.md`.
- Timer UX improvements are live: schema-aware prefill, HTMX auto-refresh, and redirect handling all ship in the new timer routes while Roam metrics stay on deck.
- Generic visualization routes now power entity calendar heatmaps (habit, meditation, BM, medication, project) with responsive layouts and dashboard entry points.
- Performance instrumentation persists Tufte snapshots every minute and exposes a super-user dashboard, covering the MVP called for in `performance.md`.
