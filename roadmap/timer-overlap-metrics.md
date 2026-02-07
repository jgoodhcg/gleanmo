---
title: "Overlap-Aware Timer Metrics"
status: ready
description: "Show unique active time per day and per project by de-duplicating overlapping logs"
tags: [area/timers, area/analytics, area/ux, type/improvement]
priority: high
created: 2026-02-07
updated: 2026-02-07
---

# Overlap-Aware Timer Metrics

## Problem / Intent

The timer page currently sums all log durations directly. When two logs overlap, shared minutes are counted more than once, which can make "time logged today" feel inflated and misleading. The goal is to present overlap-aware totals so daily stats reflect actual time spent, while still preserving access to raw cumulative totals.

## Constraints

- Keep both interpretations of time available: raw cumulative and overlap-aware (unique active time).
- The primary metric should be easy to understand at a glance.
- Per-project breakdown should remain useful even when multiple projects run concurrently.
- Avoid introducing confusing math or labels that make the stats page harder to trust.

## Proposed Approach

### Core metric model

- **Active time (unique):** Union of all timer intervals for the selected day, counting overlap once.
- **Logged time (raw):** Sum of all timer durations (existing behavior).
- **Overlap removed:** `raw - unique`.

### Initial UI direction

- Make **Active time (unique)** the primary daily stat.
- Show **Logged time (raw)** and **Overlap removed** as secondary supporting metrics.
- Add concise tooltip/help copy clarifying what each metric means.

### Per-project metrics (phased)

#### Phase 1 (MVP)

- Show per-project **raw** totals first.
- Keep day-level unique/raw/overlap summary visible above the project table.

#### Phase 2 (optional follow-up)

- Add a second per-project mode for overlap-aware attribution (for example, split overlapping minutes across concurrently running projects).
- Expose this through a simple toggle so users can switch between raw and attributed views.

## Open Questions

- For per-project overlap-aware attribution, should overlapping time be split evenly or assigned to a user-selected primary project?
- Should the page default to unique metrics everywhere, or only at the day-level summary while project rows remain raw by default?
- Is a dual-column project table ("Raw" and "Attributed") clearer than a mode toggle?

## Notes

- This work complements existing timer UX and analytics efforts, especially [workflow-optimization.md](./workflow-optimization.md).
- Example behavior: two fully overlapping one-hour logs should report one hour unique, two hours raw, and one hour overlap removed.
