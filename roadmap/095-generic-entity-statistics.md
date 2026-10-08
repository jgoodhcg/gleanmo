---
title: "Generic entity statistics"
status: draft
description: "Schema-driven occurrence charts and descriptive statistics over selected time windows"
created: 2026-10-08
updated: 2026-10-08
tags: [analytics, statistics, visualization]
priority: medium
---

# Generic entity statistics

## Intent

Give entities a reusable statistics view, following the generic CRUD and heatmap patterns.
Make simple insights available without building a separate analytics page for every entity.
This work owns the statistics idea previously captured in [035](./035-workflow-optimization.md).

## Specification

### Initial statistics

- Occurrence count and cumulative count over time.
- Daily or weekly occurrence series, plus frequency grouped by weekday.
- Mean, median, mode, minimum, and maximum for eligible quantitative values.
- The same descriptive statistics for daily or weekly occurrence counts within a selected window.
- Completed interval durations: total, cumulative duration, and descriptive statistics.
- Show the selected window, unit, and sample count beside results.

Use one shared view with an entity, measure, date window, and applicable relationship filters.
Reuse the heatmap's temporal detection and the goals measurement registry where their semantics match.
Identify missing measures explicitly; the existing goals registry does not describe every numeric schema field.
Entities inherit supported measures from shared metadata rather than custom page implementations.

### Meaning and eligibility

- Use activity timestamps or interval beginnings for occurrence dates in the user's timezone.
- Keep creation-time counts distinct from activity counts for entities without an activity timestamp.
- Include zero-occurrence days in daily count statistics for the selected window.
- Exclude missing quantitative values from samples; retain recorded zeroes.
- Show categorical distributions and modes for enums; keep arithmetic restricted to meaningful numeric measures.
- Preserve units and separate incompatible measurements, such as medication doses or exercise weights with different units.
- Identify tied modes and avoid presenting an arbitrary winner.
- Show an empty state for no observations and an unavailable result where a statistic does not apply.
- Make ongoing interval handling explicit before duration statistics are implemented.

### Architecture and presentation

Use bounded, user-scoped queries through `db/queries.clj`, with existing visibility settings.
Compute on request initially; measure query cost before introducing caching or stored aggregates.
Share calculations with [016](./016-generic-viz.md) and [092](./092-goal-comparisons-records.md) where definitions match.
Present a small useful default set, with secondary measures available on demand.
Apply [087](./087-essential-information-redesign.md) to hierarchy and shared styling.
Add the resulting screens to the [screen inventory](./screen-inventory.md) when routes are defined.

## Validation

- [ ] Verify calculations with known occurrence, numeric, categorical, and interval datasets.
- [ ] Cover empty samples, missing values, recorded zeroes, tied modes, and timezone boundaries.
- [ ] Verify visibility filtering, relationship scoping, and unit separation.
- [ ] Verify two contrasting entities use the same rendering and calculation paths.
- [ ] Measure query cost on representative short and long windows.
- [ ] Review mobile and desktop results with affected E2E tests and paired screenshots.

## Scope

Start with descriptive statistics and simple charts.
Correlations, predictive insights, and a custom dashboard builder are separate work.
Quartiles and pace remain follow-up candidates from 035.
Existing specialized analytics can keep domain-specific insights.

## Context

- `src/tech/jgood/gleanmo/viz/routes.clj`: temporal detection and generic heatmaps.
- `src/tech/jgood/gleanmo/goals/registry.clj`: existing measures, units, and counting semantics.
- `src/tech/jgood/gleanmo/db/queries.clj`: scoped reads.
- [035](./035-workflow-optimization.md): original statistics and performance discussion.
- [036](./036-timer-overlap-metrics.md): raw versus overlap-aware duration semantics.

## Open Questions

- Put statistics alongside each entity's heatmap or on a separate shared statistics route?
- Which default window and measures provide the most useful first view?
- Count interval durations by start date or split them across local dates and window boundaries?
- Exclude ongoing intervals or include elapsed duration with an explicit live label?
- What event date applies to quantitative child entities without their own activity timestamp?
