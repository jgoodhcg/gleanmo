# Daily Focus

## Work Unit Summary
- Status: **Shipped (V1 & V2)**
- Problem / intent: Task list exists but doesn't support a daily planning ritual. Need a way to gather tasks for today, execute in order, and feel motivated by visible progress.
- Constraints: Must be smooth and fun or it won't get used. Minimize friction (single-click actions). Build incrementally.
- Implemented approach: Added `focus-date` to tasks. Dedicated "Today" page with progress stats, ordered list, and satisfying completion feedback. Unfinished tasks carry forward automatically with a visual indicator.
- Current State: V1 (Core Loop) and V2 (Polish/Drag-and-Drop) are complete.

## Design Philosophy

The daily ritual has three phases:

1. **Gather** (morning): Browse backlog with filters, quick-add tasks to today
2. **Execute** (during day): See only today's tasks in planned order, check off with visual reward
3. **Flow** (end of day): Unfinished tasks slide to tomorrow automatically

The "goal" is zero tasks — backlog size is the countdown. Completing tasks reduces what's left.

## Implemented Schema

Added to task schema:
- `:task/focus-date` — LocalDate, optional. When set, task appears in that day's focus list.
- `:task/focus-order` — Integer, optional. Sort order within a day's focus list.

Behavior:
- Tasks with `focus-date = today` appear in Today view
- Tasks with `focus-date < today` and state != done appear with a "carried over" badge
- Completing a task marks it done (removes from active list, counts toward stats)
- "Defer" action sets focus-date to tomorrow

## UI: Today Page (`/app/task/today`)

### Layout

```
┌─────────────────────────────────────────────────────────┐
│  Today                                     Tue Jan 28   │
├─────────────────────────────────────────────────────────┤
│  Today's Progress: 3/7                                  │
│  [████████████░░░░░░░] 42%                              │
│                                                         │
│  All time: 247 completed                                │
│  This week: 14 (+5 vs last week)                        │
├─────────────────────────────────────────────────────────┤
│  [✓] 1. Review PR for auth refactor   [tomorrow] [x]    │
│  [✓] 2. Fix habit streak bug          [tomorrow] [x]    │
│  [✓] 3. Write migration script  (carried over)          │
│                                                         │
│  ✨ All done for today! (Empty State)                   │
├─────────────────────────────────────────────────────────┤
│  [+ Add from backlog] -> Redirects to Focus View        │
└─────────────────────────────────────────────────────────┘
```

### Interactions

- **Check off**: Single click on checkbox → task marked done, counter updates, satisfying feedback (HTMX).
- **Defer (Tomorrow)**: Push to tomorrow (focus-date = tomorrow).
- **Remove**: Clear focus-date (back to backlog, not deleted).
- **Reorder**: Drag-and-drop to change focus-order (SortableJS).
- **Add from backlog**: Button links to Focus view where tasks have a "📌 Today" toggle.

### Stats Block

| Stat | Source | Why motivating |
|------|--------|----------------|
| Today progress | `done / (done + remaining)` for focus-date <= today | Immediate feedback loop |
| All-time total | Count of all tasks with state = done | Number always goes up |
| Weekly comparison | This week vs last week done count | Shows trend without complex charts |

## Implementation Status

### V1: Core Loop (Completed)
- [x] Schema: Add focus-date and focus-order fields
- [x] Today page with ordered task list
- [x] Quick complete action with counter update
- [x] Stats block (today progress, all-time, weekly)
- [x] Add from backlog mechanism (via Focus view toggles)
- [x] Defer and remove actions

### V2: Polish (Completed)
- [x] Drag-to-reorder within today's list
- [x] Carry-forward visual indicator ("carried over" badge)
- [x] Empty state for "all done today"
- [x] Navigation: Added "Today" link to sidebar

### V3: Richer Stats (Future)
- Calendar heat map (GitHub-style, tasks completed per day)
- Backlog trend (is it growing or shrinking?)
- Rate sparkline (tasks/day over past 30 days)
- Streak counter ("5 days in a row completing 3+ tasks")

### V4: Segments & Goals (Future)
- Stats filtered by project, domain, etc.
- Optional goals ("complete 50 this month") with countdown
- Weekly/monthly review summaries

## Notes

### Carry-forward behavior
Tasks with focus-date in the past and state != done appear in Today view inline with a yellow "carried over" badge. This provides explicit visibility without magic date changes in the DB.

### Relationship to existing Focus page
Today page is complementary:
- Focus page: Triage, review, bulk state changes, full backlog visibility. Now includes "Today" toggle buttons.
- Today page: Daily execution mode, narrow focus, progress motivation.