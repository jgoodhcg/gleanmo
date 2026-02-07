---
title: "Task Activity Logs"
status: idea
description: "Spawn time logs from tasks and link habits/calendar events to tasks"
tags: [area/tasks, area/timers, type/feature]
priority: medium
created: 2026-02-06
updated: 2026-02-06
---

# Task Activity Logs

## Problem / Intent

When working on a task, you often want to log associated activity -- start a project timer, run a meditation timer, track exercise, etc. Currently tasks and time logs are disconnected: you create a task, then separately start a timer or log an activity with no link between them. The goal is to let tasks spawn associated activity logs so there's a clear record of what you did while working on a task.

Eventually this extends to linking habits and calendar events to tasks as well, creating a richer picture of how time and effort map to planned work.

## Constraints

- Must work with the existing timer system (project timers, meditation timers).
- Should be lightweight -- spawning a log from a task should be one or two clicks, not a form-heavy process.
- Links between tasks and logs should be queryable for visualization and review.

## Proposed Approach

### Phase 1: Spawn time logs from tasks

- Add a "Start timer" action on task items that creates a timer log linked back to the task.
- Support existing timer types initially: project timer, meditation timer.
- Store the task reference on the timer log entity (e.g. `::timer/task-ref`).
- Show linked timer logs on the task detail view.

### Phase 2: Additional activity types

- Extend to exercise logs and other activity types as they mature.
- Generic "log activity" action that lets you pick the type.

### Phase 3: Link habits and calendar events

- Allow linking existing habits to tasks (e.g. "meditate daily" habit linked to a recurring task).
- Allow linking calendar events to tasks (e.g. a meeting block linked to a prep task).
- Show linked items on the task detail view for a unified picture.

## Open Questions

- Should the task-to-log link be a first-class schema field or a generic "related entity" reference?
- How should linked logs appear in the daily focus view -- inline under the task, or as separate entries with a visual link?
- Should spawning a log auto-start the timer, or just pre-fill and let the user confirm?

## Notes

- Existing timer infrastructure lives in the project timer and meditation timer modules.
- The task system already has a detail view that could host linked activity summaries.
