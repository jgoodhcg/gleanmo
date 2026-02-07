---
title: "Workflow Optimization"
status: idea
description: "Minimize clicks for common logging actions, fast landing page, and motivating stats page"
tags: [area/frontend, area/ux, type/improvement]
priority: high
created: 2026-02-06
updated: 2026-02-06
---

# Workflow Optimization

## Problem / Intent

The most frequent actions -- logging a habit, starting a meditation, beginning a project session -- should be as frictionless as possible. Every extra click or page load is a reason to skip it. Beyond logging, there should be a fast landing page with relevant-right-now information and a separate stats page that provides motivation through progress visibility.

## Constraints

- Landing page must load fast (ties into [dashboard-performance.md](./dashboard-performance.md) work).
- Quick-log actions should work without navigating away from the current page where possible.
- Stats page can be heavier since it's browsed intentionally, but still shouldn't feel sluggish.

## Areas

### Quick logging (minimize clicks)

- **Habits:** One-tap completion from landing page or daily focus. No form navigation needed for simple done/not-done habits.
- **Meditation:** One-tap to start a session with sensible defaults (last used duration, etc.). Skip the creation form when defaults suffice.
- **Project sessions:** One-tap to start a timer for a recent/pinned project. Surface frequently used projects for instant access.
- General pattern: identify the 2-3 most common logging workflows and make each achievable in 1-2 interactions from the landing page.

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

- Should quick-log actions live on the landing page, in a global floating action button, or both?
- What's the right balance of information density vs. speed on the landing page?
- Should the stats page be a single scrollable view or broken into tabs/sections by domain (habits, timers, tasks)?
- How much overlap is there with the generic-viz work ([generic-viz.md](./generic-viz.md)) and streak features from [drawing-practice.md](./drawing-practice.md)?

## Notes

- This cuts across multiple features rather than being one isolated system. Implementation will likely touch daily focus, dashboard, timer views, and habit views.
- The quick-log pattern could be generalized: any entity type with sensible defaults could support a "log with one tap" mode.
- Stats/motivation features may share infrastructure with the generic visualization work.
