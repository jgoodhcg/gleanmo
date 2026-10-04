---
title: "Today as a Daily Plan"
status: draft
description: "One short list for the day that brings together chosen tasks, today's habit and goal targets, and lightweight day items that belong to neither"
created: 2026-10-04
updated: 2026-10-04
tags: [tasks, habits, goals, today, ux, design]
priority: medium
---

# Today as a Daily Plan

## Intent

The user wants each day to be a plan with only a few focused things on it.
Today's page (`/app/task/today`) shows only tasks.
The things a day actually holds are spread across three kinds:

| Kind | Example | Where it belongs | Why |
|---|---|---|---|
| One-off outcome | "Ship Jason's gift" | **Task** | Done once, then gone; waits in the backlog until chosen |
| Repeated practice | "Journal page", "ink exercise" | **Habit** (+ **goal**) | The pattern and history are the point |
| Just for today | "Make the bed", "send Sam a text" | **Day item** (new) | Needs writing down, but isn't worth a record anywhere else |

The third row is the new idea.
Strictly speaking, "make the bed" is a habit and "send a text" is a task.
Forcing them into those shapes is rigid, and it inflates both entities.
A habit would get a log that nobody reviews, and the task backlog and done counts would fill with trivia.
The user wants a place to jot down "I need to do this today" with no other commitment.

Today becomes a **view** over all three kinds, not a fourth list to maintain.
Each item is still checked off in its own system.

## Specification (proposed, to be refined by mockups)

### Composed today view

- **Chosen tasks:** tasks with today's `:task/focus-date`, as now, in `:task/focus-order`.
- **Habit and goal targets for today:** habits that pinned or active goals ask for today, shown as checkable rows.
  Checking one creates the habit log, as logging does now.
  Goal progress comes from existing goal data (see [090-goal-live-today.md](./090-goal-live-today.md), [091-pinned-goals.md](./091-pinned-goals.md)).
- **Day items:** free text, quick to add, checked off in place.
- Keep the list short and calm.
  The page is a plan for the day, not a dashboard.
  Hard deadlines stay visible but distinct from voluntary choices (see [012-daily-focus.md](./012-daily-focus.md)).

### Day items

A lightweight entity, for example `day-item`, scoped to one local date:

- Fields are roughly label, date, done, and an order within the day.
  No project, state machine, snoozing, or behavioral signals.
- Entering one must be at least as light as today's quick-add.
- Optional **promote** action: turn a day item into a task when it turns out to be bigger,
  or into a habit when it keeps coming back.
- Excluded from task stats, task exports, and habit streaks.

### Mockups first

Do not build until the composed view has been explored in mockups, mobile first.
Follow the [087-essential-information-redesign.md](./087-essential-information-redesign.md) workflow
and the `mockups/README.md` conventions, with fictional mock data.
Mock at least: an ordinary day, a day with nothing chosen yet, and a busy day that shows how the list stays short.

## Validation

- [ ] Mockups reviewed and a direction chosen, with the decisions recorded in this unit.
- [ ] A day item can be added, checked, and reordered on today without leaving the page.
- [ ] Checking a habit row on today creates a habit log that goals count.
- [ ] Day items never appear in task lists, task stats, task exports, or habit streaks.
- [ ] E2E covers the composed today page; the series manifest includes it.

## Scope

Not included:

- Recurring tasks ([086-recurring-tasks.md](./086-recurring-tasks.md)) and inbox capture ([093-inbox-quick-capture.md](./093-inbox-quick-capture.md)).
  They are related, but they are separate entry points.
- Calendar events on today. They are a likely later addition, not part of the first slice.
- Agent-built daily plans ([061-ai-assistance.md](./061-ai-assistance.md)).
  An agent could later propose a day's plan through the same view.

## Context

- Current today page: `task-today/today-view`, routes in `src/tech/jgood/gleanmo/app/task.clj`.
- Daily focus history and carry-forward behavior: [012-daily-focus.md](./012-daily-focus.md).
- Habits and goals: `schema/habit_schema.clj`, `schema/goal_schema.clj` (`:goal/habit-ids`).
- Origin: 2026-10-04 task refresh.
  The user asked whether "take a shower" or "journal page" belongs in tasks.

## Open Questions (draft only)

- Unfinished day items: drop them silently at midnight, offer "move to tomorrow", or show them once as a review?
  Silent carry-forward would repeat the problem 012 describes, where commitments pile up.
- Does today's view replace `/app/task/today`, or become a new top-level page (perhaps home)?
- Which habits count as "for today": all of them, only those linked to pinned or active goals, or a per-habit setting?
- Should day items be marked sensitive, or stay out of everything except today?
- Do day items need any history, such as a count of what got done, or none at all?

## Notes
