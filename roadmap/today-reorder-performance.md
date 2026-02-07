---
title: "Today Reorder Performance"
status: idea
description: "Fix slow response when reordering items on the today page"
tags: [area/frontend, area/performance, type/bug]
priority: high
created: 2026-02-06
updated: 2026-02-06
---

# Today Reorder Performance

## Problem / Intent

Reordering items on the today page via drag-and-drop sorting triggers a noticeably slow response. This makes daily planning feel sluggish and discourages rearranging tasks.

## Constraints

- Must maintain correct sort order persistence after reorder.
- Should feel instant or near-instant to the user.

## Proposed Approach

- Profile the reorder-today endpoint to identify the bottleneck (database writes, query overhead, full page re-render, etc.).
- Likely candidates:
  - **Too many DB transactions:** If each item's sort position is written individually, batch them.
  - **Full page re-render:** If the HTMX response returns the entire today view, return only the reordered list.
  - **Unnecessary re-queries:** If the response re-fetches all today data, skip queries unrelated to the sort.
- Apply optimistic UI update on the client side so the drag result appears immediately, with the server confirming in the background.

## Open Questions

- What is the actual bottleneck -- server-side processing, response size, or client-side re-render?
- Is the current HTMX swap target too broad (swapping the whole page vs. just the list)?
- Could optimistic reordering on the client fully mask the latency?

## Notes

- Related to [dashboard-performance.md](./dashboard-performance.md) and [workflow-optimization.md](./workflow-optimization.md) -- all part of making the daily experience feel fast.
