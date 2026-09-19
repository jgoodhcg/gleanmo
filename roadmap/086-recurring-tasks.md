---
title: "Recurring tasks"
status: draft
description: "Tasks that come back on a schedule, so recurring chores need no separate reminders app"
created: 2026-09-18
updated: 2026-09-18
tags: [tasks, recurrence]
priority: low
---

# Recurring tasks

## Intent

Tasks cannot repeat, so recurring chores have to live in a separate reminders
app, which keeps that app in use alongside Gleanmo. Typical shapes:

| Example | Interval | Anchor |
|---|---|---|
| Replace a filter | Every N months | From completion |
| Renew a registration | Yearly | Fixed date |
| Monthly review | Monthly | Fixed date |
| Annual occasion prep | Yearly | Fixed date |

## Specification

- A task can carry a recurrence rule. Completing it creates (or reschedules)
  the next occurrence rather than leaving nothing behind.
- Two anchor modes, because the examples need both: **fixed schedule** (due
  on the same date each period regardless of when it was done) and **from
  completion** (N units after it was last done).
- The next occurrence stays hidden until a lead time before it is due, so a
  yearly task does not sit in the list for 11 months — likely by setting
  `snooze-until` from the rule.
- Completed occurrences stay as their own done tasks, so history and done-at
  stats keep working.

## Validation

- [ ] Each example shape above can be expressed and produces the right next date
- [ ] Completing an occurrence yields exactly one next occurrence
- [ ] Unit tests for the next-date calculation, including month-end dates

## Scope

No RRULE parser or calendar integration. The calendar's recurring events
(`010-calendar.md`) are a separate model; share code only if it falls out
naturally.

## Context

- `roadmap/index.md` "Task Product Sequence" defers recurrence until task use
  is re-established; this unit records the need so it is ready when that gate
  passes.
- `src/tech/jgood/gleanmo/schema/task_schema.clj`, `app/task.clj`
  (`set-state!` is where completion would spawn the next occurrence)
- `030-drawing-practice.md` and `032-task-activity-logs.md` also mention
  recurring tasks.

## Open Questions (draft only)

- Next occurrence as a new document on completion, or one document whose due
  date advances (loses per-occurrence history)?
- Rule shape: `{:every 6 :unit :month :anchor :completion}` or a small enum of
  presets?
- Default lead time per interval (a week for yearly, a day for monthly)?
