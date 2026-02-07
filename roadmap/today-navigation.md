---
title: "Today Page Navigation and Date Filtering"
status: draft
description: "Page through dates on today view and add richer focus date filtering"
tags: [area/frontend, area/tasks, type/feature]
priority: medium
created: 2026-02-06
updated: 2026-02-07
---

# Today Page Navigation and Date Filtering

## Problem / Intent

The today page is locked to a single day with no way to quickly browse adjacent dates. If tasks end up on the wrong date (e.g. hitting "tomorrow" after midnight), recovering requires editing each task's focus date individually. Better date navigation and filtering would make it easy to find and fix misplaced tasks.

## Constraints

- Navigation should feel lightweight -- arrow keys or prev/next buttons, not a full calendar picker.
- Filtering should extend the existing focus date filter options without cluttering the UI.

## Proposed Approach

### Date paging on the today view

- Add previous/next controls to step through dates (yesterday, today, tomorrow, and beyond).
- Show the currently viewed date prominently so it's always clear what you're looking at.
- Bulk actions on the paged view (e.g. "move all to today") become natural since you can navigate to the wrong date and move tasks back.

### Richer focus date filtering

- Extend current filter options ("today", "any", "not today") with:
  - **Before today** -- tasks with a focus date in the past (overdue/forgotten).
  - **After today** -- tasks scheduled for the future.
  - Possibly a specific date picker for arbitrary date filtering.
- Makes it easy to find all tasks that accidentally landed on the wrong date.

## Open Questions

- Should date paging replace or supplement the existing focus date filters?
- Should the paged view support the same reordering and actions as the today view, or be read-only for non-today dates?
- Is a date picker needed or are relative filters (before/after today) sufficient?

## Notes

- Originated from a real scenario: pressing "tomorrow" after midnight moved tasks to the wrong date, and there was no efficient way to recover.
- Date paging also enables reviewing what was planned/done on previous days, which has value beyond error recovery.
