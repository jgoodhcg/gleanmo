---
title: "Today Page UX Polish"
status: draft
description: "Improve task completion feedback and add project selection to quick-add"
tags: [area/frontend, area/ux, type/improvement]
priority: high
created: 2026-02-16
updated: 2026-02-16
---

# Today Page UX Polish

## Intent

The today page has two friction points that hurt the user experience:
1. Task completion is slow and visually jarring, causing users to click multiple times
2. Quick-add doesn't support project assignment, forcing full-form navigation

## Constraints

- Completion animation must not block or delay the actual completion action
- Quick-add with project should remain keyboard-friendly and fast
- Must work on mobile and desktop

## Specification

### Task Completion Animation

- When a task is checked off, provide immediate visual feedback (e.g., fade-out, slide-away)
- Prevent "dead time" where the UI appears unresponsive
- Goal: User never wonders if their click registered
- Consider: CSS transition for opacity/transform, HTMX `hx-on::before-request` for loading state

### Quick-Add Project Selection

- Add optional project dropdown/autocomplete to the quick-add form
- Should default to "no project" for fast entry
- Must not slow down the quick-add flow for users who don't need project assignment
- Consider: Tab-navigable, fuzzy search if many projects

## Validation

- Manual: Complete a task and verify animation feels responsive, not jarring
- Manual: Quick-add an item with a project without leaving the today page
- E2E: Screenshot before/after for visual comparison

## Context

User reports clicking multiple times on task completion because the UI feels unresponsive, then accidentally clicking other items when the refresh finally happens. Also frequently wants to assign projects during quick-add but currently must use the full form.

## Open Questions

- What animation duration feels snappy vs. too slow? (100-200ms typical)
- Should completed tasks fade out, slide away, or just get a strikethrough and move to a "completed" section?
- Should project selection in quick-add be a dropdown, typeahead, or hidden behind a keyboard shortcut?

## Notes

- Related to [today-reorder-performance.md](./today-reorder-performance.md) for reordering UX
- Related to [workflow-optimization.md](./workflow-optimization.md) for general click-reduction philosophy
