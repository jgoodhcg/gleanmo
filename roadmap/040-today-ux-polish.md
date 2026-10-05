---
title: "Today Page UX Polish"
status: draft
description: "Improve task completion feedback and add project selection to quick-add"
tags: [area/frontend, area/ux, type/improvement]
priority: high
created: 2026-02-16
updated: 2026-10-04
---

# Today Page UX Polish

> Folds into [087-essential-information-redesign.md](./087-essential-information-redesign.md) when the today page comes up there.

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

### Post-Completion Focus Management

- After checking off a task, focus should not automatically move to the next item's checkbox
- Options: (a) clear focus entirely, (b) move focus to a neutral location (e.g., page header, quick-add input)
- Current behavior (focus on next checkbox) causes accidental completions when user clicks multiple times

### Quick-Add Project Selection

- Add optional project dropdown/autocomplete to the quick-add form
- Should default to "no project" for fast entry
- Must not slow down the quick-add flow for users who don't need project assignment
- Consider: Tab-navigable, fuzzy search if many projects

#### Dogfood notes (2026-10-04): friction stops capture entirely

Dogfood list, verbatim:
"There is enough friction to adding projects to tasks that I don't want to add them on today screen event" (likely "even"),
with the note: "Can we make it easy to add a project and have sane defaults on the other attributes so I can just add stuff without hesitation? I don't like adding stuff and feeling like it's incomplete."

Interpretation: two needs, and the second is the new one.

1. **Project in quick-add** — the item above, confirmed by use. Use a Choices.js select per the UI rules, not a hand-rolled picker.
   Remember the last-used project between adds, as [093](./093-inbox-quick-capture.md) proposes for capture.
2. **No "incomplete" feeling** — a quick-added task should be a whole task, not a stub awaiting the full form.
   That means every other attribute gets a deliberate default at creation (state, focus date, domain, sensitivity, effort, and whatever else the task form shows as empty), chosen so the result reads as finished rather than unfilled.
   Possibly defaults derived from the chosen project, along the lines of [080](./080-relation-defaults.md) (per-relation prefills).
   Open: which fields the user actually reads as "missing" when a quick-added task is opened; inventory the task form against a quick-added task before choosing defaults.

Coordinate with [093](./093-inbox-quick-capture.md) (same optional-project capture, but to the inbox) so the two quick-add forms share the project field and default rules,
and with [094](./094-today-plan.md), whose lightweight day items deliberately have no project — the quick-add must stay clearly a *task* add.

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

- Related to [034-today-reorder-performance.md](./034-today-reorder-performance.md) for reordering UX
- Related to [035-workflow-optimization.md](./035-workflow-optimization.md) for general click-reduction philosophy
