---
title: "Workflow Optimization"
status: draft
description: "Minimize clicks for common logging actions, fast landing page, and motivating stats page"
tags: [area/frontend, area/ux, type/improvement]
priority: high
created: 2026-02-06
updated: 2026-07-26
---

# Workflow Optimization

## Problem / Intent

The most frequent actions -- logging a habit, starting a meditation, beginning a project session -- should be as frictionless as possible. Every extra click or page load is a reason to skip it. Beyond logging, there should be a fast landing page with relevant-right-now information and a separate stats page that provides motivation through progress visibility.

## Constraints

- Landing page must load fast (ties into [dashboard-performance.md](./dashboard-performance.md) work).
- Quick-log actions should work without navigating away from the current page where possible.
- Stats page can be heavier since it's browsed intentionally, but still shouldn't feel sluggish.

## Areas

### Dashboard Quick Reference

The home dashboard should be a glanceable hub for things I want to see frequently:

**Frequent stats** (user-configurable or discovered over time):
- Current streak counts (habits, meditation)
- Today's progress indicators
- Active timer status
- Key metrics that matter to the user (TBD which ones through usage)

**Recent entities for quick editing**:
- Last N items I might want to correct (wrong timestamp, forgot a field)
- Direct edit links without navigating through list pages
- Entity-type filtering or mixed chronological view

**Action shortcuts**:
- One-click access to start common timers
- Quick-add buttons for frequent log types
- Keyboard shortcuts for power users
- Consider floating action button (FAB) pattern

### Quick logging (minimize clicks)

- **Habits:** One-tap completion from landing page or daily focus. No form navigation needed for simple done/not-done habits.
- **Meditation:** One-tap to start a session with sensible defaults (last used duration, etc.). Skip the creation form when defaults suffice.
- **Project sessions:** One-tap to start a timer for a recent/pinned project. Surface frequently used projects for instant access.
  - Current flow is 4 clicks: /app/timers → /app/timer/project-log → start specific timer → save form
  - Target: 1-2 clicks maximum from any page to start a project-log
  - Consider: Floating action button, today page integration, or direct "start recent" shortcut
- General pattern: identify the 2-3 most common logging workflows and make each achievable in 1-2 interactions from the landing page.

### Usage-weighted quick actions (adaptive ordering)

The first slice (`qol-quick-actions.md`) hardcodes an order from a 28-day
Plausible sample. The adaptive version orders quick-add/timer links from the
user's own data — no new telemetry needed: count the user's logs per entity
type over a trailing window (the data is already in XTDB) and sort the
quick-action component by that. Recompute lazily (per page load or cached
per-day), never mid-session (links that move under your thumb are worse than
a stale order). Ties into the "user-configurable or discovered over time"
idea above; a manual pin-to-top override should beat the computed order.

### "Repeat last" one-tap logging

Prefill a new log from the user's previous entry of the same type and submit
in one confirm. Strongest case from analytics: medication-log/new had 79
views in 28 days and is mostly the same meds at routine times. Also fits
habit-log and bm-log. Shape: a "repeat last (edit first)" affordance on the
quick-add surface or form header — prefill everything except timestamp
(default now), one tap to save. This is the concrete version of the "log
with one tap" generalization in Notes below.

### Landing page (relevant, fast)

- Show what matters right now: today's tasks, active timers, habit completion status, upcoming calendar events.
- Prioritize speed -- defer or lazy-load anything that isn't immediately relevant.
- Integrate quick-log actions directly so the landing page is both informational and actionable.
- Related: [dashboard-performance.md](./dashboard-performance.md) for the performance side of this.

### Motivating stats page

- Streaks: current and longest for habits, meditation, project work.
- Totals and trends: weekly/monthly session counts, durations, habit completion rates.
- Comparisons: this week vs. last week, this month vs. last month.
- Visual: charts and progress indicators that make consistency feel rewarding.
- Could include per-entity stats (meditation minutes this month, drawing sessions this week) alongside aggregate views.

## Open Questions

- Which stats do I actually look at frequently? (Discover through usage analytics or manual tracking)
- Should quick-log actions live on the landing page, in a global floating action button, or both?
- How many recent entities should be shown? Should they be filterable by type?
- What's the right balance of information density vs. speed on the landing page?
- Should the stats page be a single scrollable view or broken into tabs/sections by domain (habits, timers, tasks)?
- How much overlap is there with the generic-viz work ([generic-viz.md](./generic-viz.md)) and streak features from [drawing-practice.md](./drawing-practice.md)?

## Notes

- 2026-07-26: the first concrete, analytics-backed slice of this doc shipped
  as its own ready work unit — `qol-quick-actions.md` (static
  frequency-ordered sidebar/home quick actions, timer deep links bypassing
  the `/app/timers` hub, stop-in-place timers). This doc remains the vision
  for the adaptive/heavier pieces: usage-weighted ordering, repeat-last,
  stats page, landing-page performance.
- This cuts across multiple features rather than being one isolated system. Implementation will likely touch daily focus, dashboard, timer views, and habit views.
- The quick-log pattern could be generalized: any entity type with sensible defaults could support a "log with one tap" mode.
- Stats/motivation features may share infrastructure with the generic visualization work.
- Related: [activity-timeline.md](./activity-timeline.md) for a dedicated timeline view with day separation.
