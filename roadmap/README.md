# Gleanmo Roadmap

This directory contains detailed planning and requirements documentation for Gleanmo features and improvements.

## Active Features

### [Calendar](./calendar.md)
**Status**: Core functionality complete, polish in progress

Year-at-a-glance calendar with event creation, visualization, and timezone-aware queries. Currently working on UI polish (event shapes, icons, navigation) and planning external calendar sync.

**Key sections:** Completed features, UI/UX improvements, external sync (iCal/Google), recurring events architecture

### [Project Time Tracking](./roam-integration.md)
**Status**: Timer UX refreshed; Roam metrics still outstanding

Timer-based project tracking with schema-driven pre-population, automatic refresh, and redirects back to the timer dashboard. Remaining work focuses on Roam Research metric ingestion and richer analytics.

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

### [Exercise Tracking](./exercise.md)
Comprehensive workout logging with superset support. Four-entity schema (exercise, session, set, rep) designed for flexible data entry and Airtable migration.

**Key sections:** Core entities, data entry workflow, superset support, schema issues, migration strategy

### [Automated Screenshots](./screenshots.md)
Visual changelog of application development through automated route screenshots. Supports local development, GitHub Actions, and Docker deployment approaches.

**Key sections:** Implementation approaches, route discovery, storage organization, configuration options

## Improvements & Refactoring

### [Backlog](./backlog.md)
Minor improvements and enhancements including CRUD system redesign, dark-themed tables, fuzzy search components, and entity-specific fixes.

**Key sections:** CRUD redesign, generic components, medication/meditation improvements

## Quick Reference

| Feature | Status | Priority | File |
|---------|--------|----------|------|
| Calendar Polish | In Progress | High | [calendar.md](./calendar.md) |
| Project Timer Fixes | Auto-refresh shipped; Roam metrics pending | High | [roam-integration.md](./roam-integration.md) |
| Life Chart | Planned | Medium | [life-chart.md](./life-chart.md) |
| Exercise Tracking | Planned | Medium | [exercise.md](./exercise.md) |
| Generic Viz | Phase 1–3 Live | Medium | [generic-viz.md](./generic-viz.md) |
| Screenshots | Planned | Low | [screenshots.md](./screenshots.md) |
| Performance | Profiling Dashboard Live | Low | [performance.md](./performance.md) |
| CRUD Redesign | Future | Medium | [backlog.md](./backlog.md) |

## Navigation

- **Active work**: [calendar.md](./calendar.md), [roam-integration.md](./roam-integration.md), [generic-viz.md](./generic-viz.md), [performance.md](./performance.md)
- **Next features**: [life-chart.md](./life-chart.md), [exercise.md](./exercise.md), [screenshots.md](./screenshots.md)
- **Minor improvements**: [backlog.md](./backlog.md)

## ✅ Status Update (2025-10-23)
- Calendar polish picks up header navigation, event editing from the grid, and better past-day treatments; review the new implementation notes inside `calendar.md`.
- Timer UX improvements are live: schema-aware prefill, HTMX auto-refresh, and redirect handling all ship in the new timer routes while Roam metrics stay on deck.
- Generic visualization routes now power entity calendar heatmaps (habit, meditation, BM, medication, project) with responsive layouts and dashboard entry points.
- Performance instrumentation persists Tufte snapshots every minute and exposes a super-user dashboard, covering the MVP called for in `performance.md`.
