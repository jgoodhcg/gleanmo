---
title: "Exercise Insights Page"
status: draft
description: "Visual daily exercise summary with muscle heatmap and session stats"
created: 2026-03-12
updated: 2026-03-12
tags: [exercise, visualization, insights]
priority: medium
---

# Exercise Insights Page

## Intent

Provide an at-a-glance visual summary of daily exercise activity, showing which muscle groups were worked and key session metrics for motivation and pattern recognition.

## Specification

An insights page for exercise data featuring:

- **Muscle heatmap diagram**: Visual representation of a human body with muscle groups color-coded by intensity/frequency of work (e.g., chest, back, shoulders, arms, core, legs)
- **Daily stats cards**: 
  - Total exercises performed
  - Total weight lifted
  - Total sets completed
  - Session duration/length
  - Number of sessions per day
- **Day-by-day view**: Scrollable or paginated view showing each day's summary

## Validation

[How to know it's done:]
- [ ] Muscle diagram renders with correct heatmap coloring
- [ ] Stats calculate correctly from exercise logs
- [ ] Mobile-responsive layout
- [ ] E2E screenshot tests pass

## Scope

- Initial version: read-only visualization
- Future considerations (out of scope for now):
  - Weekly/monthly aggregations
  - Muscle recovery recommendations
  - Historical comparison charts

## Context

- Exercise tracking is already implemented via [exercise.md](./exercise.md)
- Uses existing exercise logs and sets data
- Consider using ECharts for the muscle diagram visualization

## Open Questions (draft only)

- Should muscle heatmap show absolute volume or relative intensity?
- How to handle exercises that work multiple muscle groups?
- What time range should be the default view (week, month, all-time)?

## Notes

[Design details, context, implementation notes as work progresses.]
