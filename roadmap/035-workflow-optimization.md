---
title: "Workflow Optimization"
status: draft
description: "Minimize clicks for common logging actions, fast landing page, and motivating stats page"
tags: [area/frontend, area/ux, type/improvement]
priority: high
created: 2026-02-06
updated: 2026-10-04
---

# Workflow Optimization

## Problem / Intent

The most frequent actions -- logging a habit, starting a meditation, beginning a project session -- should be as frictionless as possible. Every extra click or page load is a reason to skip it. Beyond logging, there should be a fast landing page with relevant-right-now information and a separate stats page that provides motivation through progress visibility.

## Constraints

- Landing page must load fast (ties into [013-dashboard-performance.md](./013-dashboard-performance.md) work).
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

The first slice (`063-qol-quick-actions.md`) hardcodes an order from a 28-day
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
- Related: [013-dashboard-performance.md](./013-dashboard-performance.md) for the performance side of this.

### Motivating stats page

- Streaks: current and longest for habits, meditation, project work.
- Totals and trends: weekly/monthly session counts, durations, habit completion rates.
- Comparisons: this week vs. last week, this month vs. last month.
- Visual: charts and progress indicators that make consistency feel rewarding.
- Could include per-entity stats (meditation minutes this month, drawing sessions this week) alongside aggregate views.

#### Dogfood notes (2026-10-04): generic descriptive stats

Dogfood list, verbatim: "I want some stats page for generic stuff",
with the note: "Min, max, median, mean, quartiles?, pace. What would be better than writing these on events and if they don't exist creating them on request in terms of server load/demand and page load times?"

Interpretation: a page that, for any loggable entity (and a filter, as goals already have), shows the distribution of a numeric measure over a window — count, min, max, median, mean, quartiles — plus pace (per day or week over the window).
"Generic" means driven by the schema/measurement registry, not hand-built per entity; the goals measurement registry (`goals/` and [081](./081-goals-dashboard.md)) already defines which numeric values each entity has and how to scope them, so reuse it rather than inventing a second one.
Overlaps: [016](./016-generic-viz.md) (generic charts over the same entities) and [092](./092-goal-comparisons-records.md) (best/worst periods are order statistics over the same series). Build the stats computation once and let all three read it.

On the load question — recorded answer to revisit when building, not a decision:

- **Don't write stats onto events.** Stored aggregates go stale on every edit, soft delete, sensitivity or time-zone change, and need a backfill and invalidation story per statistic. 081's performance follow-up rules the same way for goals ("do not introduce stored aggregates").
- **The arithmetic is not the cost; the read is.** Median and quartiles over a few thousand numbers is microseconds. What costs is fetching documents, so the query should project only `[?e ?t ?value]` (index-only scan, scan-then-pull rules in AGENTS.md) rather than pulling whole documents.
- **Compute on request, lazily per section**, like the home overview fragments — the stats page is browsed intentionally, so a few hundred ms per card is acceptable and nothing blocks first paint.
- **Measure before caching.** If a window proves slow (the 081 benchmark already found ten-year windows heavy), the next steps in order are: narrower projections, a per-request or short-TTL cache keyed by user + entity + filter + window, then precomputed daily rollups. Rollups are the only stored form worth considering, since a day's bucket is small to recompute when one of its events changes — and [077](./077-scheduled-work-multi-instance.md) applies to anything recomputed in the background.

## Open Questions

- Which stats do I actually look at frequently? (Discover through usage analytics or manual tracking)
- Should quick-log actions live on the landing page, in a global floating action button, or both?
- How many recent entities should be shown? Should they be filterable by type?
- What's the right balance of information density vs. speed on the landing page?
- Should the stats page be a single scrollable view or broken into tabs/sections by domain (habits, timers, tasks)?
- How much overlap is there with the generic-viz work ([016-generic-viz.md](./016-generic-viz.md)) and streak features from [030-drawing-practice.md](./030-drawing-practice.md)?

## Notes

- 2026-07-26: the first concrete, analytics-backed slice of this doc shipped
  as its own ready work unit — `063-qol-quick-actions.md` (static
  frequency-ordered sidebar/home quick actions, timer deep links bypassing
  the `/app/timers` hub, stop-in-place timers). This doc remains the vision
  for the adaptive/heavier pieces: usage-weighted ordering, repeat-last,
  stats page, landing-page performance.
- This cuts across multiple features rather than being one isolated system. Implementation will likely touch daily focus, dashboard, timer views, and habit views.
- The quick-log pattern could be generalized: any entity type with sensible defaults could support a "log with one tap" mode.
- Stats/motivation features may share infrastructure with the generic visualization work.
- Related: [052-activity-timeline.md](./052-activity-timeline.md) for a dedicated timeline view with day separation.
