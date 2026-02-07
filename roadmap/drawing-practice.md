---
title: "Drawing Practice"
status: draft
description: "Build a habit of short timed drawing sessions with tracking and motivation tools"
tags: [area/habits, area/timers, type/feature]
priority: medium
created: 2026-02-06
updated: 2026-02-07
---

# Drawing Practice

## Problem / Intent

Want to build a consistent habit of short timed drawing sessions. The app should help promote and sustain the habit, not just passively record it. The right solution might be one approach or a combination of several -- the exact shape is still open.

## Constraints

- Should lower the barrier to starting a session (quick launch, minimal friction).
- Must provide some form of feedback or motivation to keep the habit going.
- Should integrate with existing timer and task infrastructure where possible.

## Possible Approaches

These are not mutually exclusive -- the final design may combine several:

### Configurable habit goals with tracking
- Define a habit target (e.g. "draw 15 min/day" or "draw 5 days/week").
- Track completion against the goal with a simple yes/no or duration-based metric.
- Visual progress indicator on dashboard or daily focus.

### Recurring tasks
- Auto-generate a daily or scheduled "draw" task.
- Completion feeds into habit tracking.
- Pairs well with the task activity logs idea ([task-activity-logs.md](./task-activity-logs.md)) to spawn a timer from the task.

### Associated project logs
- Link drawing sessions to a "Drawing" project via project timer logs.
- Review history of sessions, durations, and frequency over time.

### Dedicated drawing entity and log type
- A drawing-specific schema with fields like medium, subject/reference, satisfaction rating, notes.
- Richer than a generic timer log but more overhead to build.

### Countdown timer
- A purpose-built countdown mode ("draw for 15 minutes") vs. the current stopwatch-style project timer.
- Audio or visual alert when time is up.
- Could be a general-purpose countdown timer that drawing just happens to use.

### Streaks and data insights
- Current streak, longest streak, weekly/monthly totals.
- Charts showing session frequency and duration trends.
- Could apply generically to any habit, not just drawing.

## Open Questions

- Which combination of the above gives the best motivation-to-effort ratio?
- Should this be drawing-specific or should it drive a generic "habit with timed sessions" feature that drawing is the first user of?
- Is a countdown timer valuable enough on its own to build as a standalone feature?
- How does this relate to the existing meditation timer (which is already a timed session habit)?

## Notes

- Meditation timer is a useful precedent -- it's essentially a timed session habit with a dedicated entity.
- The task activity logs idea would let a recurring "draw" task spawn a timer, which might be the simplest MVP path.
- A generic habit-goal system with streaks could benefit drawing, meditation, exercise, and any future timed habit.
