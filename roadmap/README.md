# Gleanmo Roadmap

This directory contains detailed planning and requirements documentation for Gleanmo features and improvements.

## Active Features

### [Calendar](./calendar.md)
**Status**: Core functionality complete, polish in progress

Year-at-a-glance calendar with event creation, visualization, and timezone-aware queries. Currently working on UI polish (event shapes, icons, navigation) and planning external calendar sync.

**Key sections:** Completed features, UI/UX improvements, external sync (iCal/Google), recurring events architecture

### [Project Time Tracking](./roam-integration.md)
**Status**: Core MVP complete, issues remain

Timer-based project tracking with form pre-population and timezone handling. Working through redirect issues and planning Roam Research integration for project metrics.

**Key sections:** MVP schema, timer functionality, Roam integration, CRUD form enhancements, outstanding issues

## Planned Features

### [Life Chart Calendar](./life-chart.md)
Lifetime visualization with years as rows and weeks as columns. Track life periods (education, work, relationships, residence) and integrate with calendar events for a complete temporal view of life.

**Key sections:** Visual structure, core entities, period categories, interactions, technical implementation

### [Exercise Tracking](./exercise.md)
Comprehensive workout logging with superset support. Four-entity schema (exercise, session, set, rep) designed for flexible data entry and Airtable migration.

**Key sections:** Core entities, data entry workflow, superset support, schema issues, migration strategy

### [Generic Visualization System](./generic-viz.md)
Automatic visualizations for any entity with temporal data. Pattern detection based on Malli schemas generates calendar heatmaps, timelines, and distribution charts with zero configuration.

**Key sections:** Temporal pattern detection, chart type mapping, route generation, implementation phases

### [Automated Screenshots](./screenshots.md)
Visual changelog of application development through automated route screenshots. Supports local development, GitHub Actions, and Docker deployment approaches.

**Key sections:** Implementation approaches, route discovery, storage organization, configuration options

### [Performance Monitoring](./performance.md)
Track per-request and database latencies with an in-app dashboard. Uses Tufte profiling with XTDB-based time-series storage for historical metrics.

**Key sections:** Architecture, Tufte integration, metric aggregation, dashboard implementation

## Improvements & Refactoring

### [Backlog](./backlog.md)
Minor improvements and enhancements including CRUD system redesign, dark-themed tables, fuzzy search components, and entity-specific fixes.

**Key sections:** CRUD redesign, generic components, medication/meditation improvements

## Quick Reference

| Feature | Status | Priority | File |
|---------|--------|----------|------|
| Calendar Polish | In Progress | High | [calendar.md](./calendar.md) |
| Project Timer Fixes | In Progress | High | [roam-integration.md](./roam-integration.md) |
| Life Chart | Planned | Medium | [life-chart.md](./life-chart.md) |
| Exercise Tracking | Planned | Medium | [exercise.md](./exercise.md) |
| Generic Viz | Planned | Medium | [generic-viz.md](./generic-viz.md) |
| Screenshots | Planned | Low | [screenshots.md](./screenshots.md) |
| Performance | Planned | Low | [performance.md](./performance.md) |
| CRUD Redesign | Future | Medium | [backlog.md](./backlog.md) |

## Navigation

- **Active work**: See [calendar.md](./calendar.md) and [roam-integration.md](./roam-integration.md)
- **Next features**: [life-chart.md](./life-chart.md), [exercise.md](./exercise.md), [generic-viz.md](./generic-viz.md)
- **Infrastructure**: [screenshots.md](./screenshots.md), [performance.md](./performance.md)
- **Minor improvements**: [backlog.md](./backlog.md)
