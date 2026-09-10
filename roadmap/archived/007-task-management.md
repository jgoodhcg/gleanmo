---
title: "Task Management"
status: done
description: "Task system with behavioral signals and actionable lists"
tags: []
priority: medium
created: 2026-02-02
updated: 2026-02-02
---

# Task Management

## Work Unit Summary
- Problem / intent: Build a task system that absorbs large backlogs, surfaces behavioral signals (not declared priorities), and delivers a short actionable list without overwhelm.
- Constraints: Fast capture, no mandatory priorities, fixed attribute enums, derive urgency from behavior, integrate with existing projects, no recurring tasks in v1.
- Proposed approach: Single task entity with 6 states, optional attributes (effort/mode/domain), date-based snoozing, and computed signals (staleness, snooze count, churn). A single Focus page replaces separate triage/now views; filtering and sorting drive the workflow.
- Open questions: None blocking v1.

## Design Decisions

| Decision | Choice |
|----------|--------|
| States | 6: inbox, now, later, waiting, snoozed, done |
| Snooze | Date-based, auto-returns when date passes |
| Primary UI | Single Focus page with filters/sorts and inline state actions |
| Done vs Delete | Done = permanent history, soft-delete separate |
| Attributes | Fixed enums (effort, mode, domain) |
| Project Relationship | One-to-many (Task -> Project). Rejected many-to-many to preserve "fast capture" and simplify time logging attribution. |
| Due dates | Optional, hard semantics (overdue warnings) |
| Review | Threshold-based queue + manual trigger |
| Recurring | Not in v1 |

## Schema

See `src/tech/jgood/gleanmo/schema/task_schema.clj`

Core fields: `label`, `notes`, `state`, `sensitive`

### States

- **inbox** — captured, not clarified
- **now** — actionable by me
- **later** — actionable, not current
- **waiting** — blocked on someone/something
- **snoozed** — intentionally hidden until snooze-until date
- **done** — complete

### Attributes (fixed enums)

- **effort**: low | medium | high
- **mode**: solo | social
- **domain**: work | personal | home | health | admin

### Derived Signals (computed at query time)

- **staleness**: days in current state (`now - last-state-change-at`)
- **overdue**: `due-on < today`
- **time-in-inbox**: days since creation when state = inbox

## Default Views (Focus filters)

1. **Now** — state = :now, snooze-until <= today or nil
2. **Review Queue** — staleness > 14d OR snooze-count >= 3 OR overdue
3. **Backlog** — state in [:later :waiting]
4. **Done** — state = :done
5. **Inbox** — state = :inbox (rare; accessible via filters)

## Milestones

### M1: Schema + CRUD (done)
- Add task schema to registry
- Basic create/edit/list views via existing CRUD system
- State transitions via edit form

### M2: Focus Page MVP (done)
- Single Focus page with filters/sorts
- Inline state actions + snooze actions
- Show project label on each task row

### M3: Data correctness + HTMX (done)
- Move task queries into db layer and respect global sensitive/deleted rules
- Add snooze filters for no-date, expired, and future snoozes
- Focus filters as GET with HTMX partial refresh (no full page reload, URL is bookmarkable)

### M4: Pre-rollout (done)
- Visual signal indicators on task rows (staleness, overdue, snooze count)
- Expired snoozes auto-surface in Now view
- HTMX action buttons preserve filter state

~~Review Queue preset filter~~ — deferred; bookmarkable URLs + existing filters sufficient for now. Add if usage reveals need.

~~Quick capture on Focus page~~ — deferred; existing quick-add + CRUD form sufficient for MVP.

~~Empty state guidance~~ — deferred; user is developer, knows the system.

### M5: Polish (active)
- Keyboard shortcuts for state changes
- Signal thresholds configurable
- Bulk state transitions
- Task stats on overview page
- Filter enhancements:
  - "No project" option in project filter (show tasks without projects)
  - "Not done" state filter option (show all active tasks: inbox, now, later, waiting)

## Notes

### Design philosophy

States = what it is right now
Attributes = when/how it works
Derived signals = what your behavior reveals

Priority, urgency, importance, motivation, difficulty are NOT stored — they emerge from staleness, snooze count, state churn, and overdue status.

### Integration

Tasks link to existing projects via `:task/project-id`. Time logging stays in `:project-log`; tasks do not require timers.
