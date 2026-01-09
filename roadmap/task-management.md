# Task Management

## Work Unit Summary
- Status: ready
- Problem / intent: Build a task system that absorbs large backlogs, surfaces behavioral signals (not declared priorities), and delivers a short actionable list without overwhelm.
- Constraints: Fast capture, no mandatory priorities, fixed attribute enums, derive urgency from behavior, integrate with existing projects, no recurring tasks in v1.
- Proposed approach: Single task entity with 6 states, optional attributes (effort/mode/domain), date-based snoozing, and computed signals (staleness, snooze count, churn). Default views for Now, Review Queue, Backlog, Done.
- Open questions: None blocking v1.

## Design Decisions

| Decision | Choice |
|----------|--------|
| States | 6: inbox, now, later, waiting, snoozed, done |
| Snooze | Date-based, auto-returns when date passes |
| Done vs Delete | Done = permanent history, soft-delete separate |
| Attributes | Fixed enums (effort, mode, domain) |
| Projects | Link to existing `:project/id` |
| Due dates | Optional, hard semantics (overdue warnings) |
| Review | Threshold-based queue + manual trigger |
| Recurring | Not in v1 |

## Schema

See `src/tech/jgood/gleanmo/schema/task_schema.clj`

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

## Default Views

1. **Now** — state = :now, snooze-until <= today or nil, limit 10
2. **Review Queue** — staleness > 14d OR snooze-count >= 3 OR overdue
3. **Backlog** — state in [:later :waiting], grouped by domain or project
4. **Done** — state = :done, recent first

## Milestones

### M1: Schema + CRUD
- Add task schema to registry
- Basic create/edit/list views via existing CRUD system
- State transitions via edit form

### M2: Triage Flow
- Inbox capture (minimal fields: title only)
- Triage screen to move inbox → now/later/waiting
- Snooze with date picker

### M3: Views + Signals
- Now view with limit
- Review queue based on computed signals
- Backlog grouped by domain/project
- Done history view

### M4: Polish
- Quick-add from other screens
- Keyboard shortcuts for state changes
- Signal thresholds configurable

## Notes

### Design philosophy

States = what it is right now
Attributes = when/how it works
Derived signals = what your behavior reveals

Priority, urgency, importance, motivation, difficulty are NOT stored — they emerge from staleness, snooze count, state churn, and overdue status.

### Integration

Tasks link to existing projects via `:task/project-id`. Time logging stays in `:project-log`; tasks do not require timers.
