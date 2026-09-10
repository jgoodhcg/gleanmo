---
title: "Task Focus Last Edited Filter"
status: draft
description: "Add sort and filter controls for last-edited tasks on the focus page"
created: 2026-05-18
updated: 2026-05-18
tags: [area/tasks, ux, filter]
priority: low
---

# Task Focus Last Edited Filter

## Intent

Make the task focus page better for review and cleanup by allowing tasks to be sorted or filtered by their last edited timestamp.

## Specification

- Add a control on the task focus page for sorting tasks by last edited time.
- Support newest-first and oldest-first ordering.
- Consider a simple last-edited filter for recently changed tasks if the underlying data is available.
- Keep the control compact so it does not distract from the primary focus workflow.

## Validation

- [ ] Task focus page can sort tasks by last edited newest first.
- [ ] Task focus page can sort tasks by last edited oldest first.
- [ ] Sorting/filtering works alongside existing focus page filters or date state.
- [ ] Empty or missing last-edited values have a predictable placement.

## Scope

- Does not change task edit behavior or timestamp semantics.
- Does not add a broader audit log.
- Does not redesign the task focus page.

## Context

- Relevant task focus page code is likely under `src/tech/jgood/gleanmo/app/`.
- Database reads should stay in `src/tech/jgood/gleanmo/db/queries.clj`.
- Verify whether tasks already have a reliable updated/modified timestamp before implementing.

## Open Questions

- Which field should count as "last edited" for tasks?
- Should filtering be a time window, a boolean "recently edited" view, or just sorting for v1?
