---
title: "Essential-information UI redesign"
status: draft
description: "Reduce every page to its essential information, then use those findings to decide on a holistic minimal redesign with clear hierarchy, efficient use of space, and good ergonomics on phone and desktop"
created: 2026-09-18
updated: 2026-10-08
tags: [ui, ux, design, redesign, mobile]
priority: medium
---

# Essential-information UI redesign

## Intent

Pages have accreted fields, controls and decoration one feature at a time.
The goal is a **minimal design** that:

- gives information a clear **visual hierarchy**, so the most important thing
  on a screen reads first;
- uses **space efficiently**, with density that serves the content instead of
  padding around it;
- is **ergonomic on a phone and on desktop**: reachable targets and one-handed
  use on mobile, no wasted width on desktop.

The page-by-page exercise below is how the holistic redesign gets decided. It
is not a series of isolated page makeovers. Reducing each page to its
essentials surfaces the patterns every page shares (lists, entries, forms,
charts, navigation), and the holistic design is built to fit those patterns
rather than being invented in the abstract.

This is the umbrella for UI redesign. Narrower drafts fold into it:
`038-today-mobile-redesign.md`, `040-today-ux-polish.md`, and the "CRUD System
Redesign" section of `008-backlog.md`.

## Specification

### Phase 1: Establish a general style

Explore representative screens to establish a shared visual direction.
Use a list, form, daily workspace, and chart to test typography, spacing, density, colour, and emphasis.
Record the chosen direction and alternatives in `.decisions/`.
The style can evolve as screen iterations reveal shared needs.
Design tooling remains an open choice.

### Phase 2: Iterate through every screen

[screen-inventory.md](./screen-inventory.md) is the running redesign checklist.
It includes standalone pages, generated CRUD screens, and important embedded states.
The screenshot manifest remains the curated visual-timeline list; it is not an exhaustive screen inventory.

For each screen:

- Identify its job, primary question, and most important action or information.
- Rank necessary content and controls by importance.
- Remove unnecessary items and record anything merged or retired.
- Rearrange the screen so the primary information receives attention first.
- Keep necessary secondary controls subtle, discoverable, and accessible.
- Review phone and desktop layouts, then repeat until the hierarchy works.
- Record the brief, iteration artifacts, outstanding issues, and review status in the inventory.

Shared patterns inform later screens, but every screen receives an explicit review.
A shared CRUD improvement applies across entities; entity-specific fields and exceptions still need review.

### Phase 3: Implement holistically

- Build the shared layer first (tokens, layout shell, navigation, the pattern
  components), then move pages onto it in the order below.
- Reuse existing interaction components (Choices.js selects, shared icons,
  form inputs) per AGENTS.md "UI/UX patterns". The design sets layout,
  hierarchy and visual language. It does not introduce new interaction
  primitives, and it restyles shared components instead of replacing them.

Suggested page order, most-used and pattern-setting first:

1. Home (`/app`) and the activity timeline
2. Task today / focus
3. Timers workspace
4. Generic CRUD list and form (inherited by every entity)
5. Workout session
6. Dashboards (entities, activity logs, stats)
7. Viz and stats pages (a shared template first, then per-entity exceptions)
8. Remaining pages

### Artifacts

Briefs, pattern explorations and the chosen direction live under
`mockups/<yyyy-mm>-essential-redesign/`, following `mockups/README.md`.
Mock data stays fictional.

## Validation

- [ ] Every inventory screen has a brief and review status
- [ ] Registered page routes and important embedded states are reconciled against the inventory
- [ ] A design direction is chosen and recorded in `.decisions/`
- [ ] Each redesigned page is reviewed on a phone-width and desktop viewport
- [ ] Series capture (`just e2e-shot-series`) before the shared layer lands and
  after each page moves onto it
- [ ] Affected e2e tests pass; `data-original-value` highlighting still works
  on redesigned forms

## Scope

- No new features or data model changes. A page whose brief turns up a
  missing capability gets its own work unit.
- Pages that the briefs show to be redundant can be merged or dropped, but
  that decision is recorded rather than folded silently into the redesign.

## Context

- `mockups/README.md`: mockup layout conventions. The goals dashboard
  (`081-goals-dashboard.md`) is a prior example of mockup-then-implement.
- [screen-inventory.md](./screen-inventory.md): running redesign checklist.
- `e2e/scripts/manifest.ts`: curated visual-timeline routes.
- `027-screenshot-runner.md`: series capture used for before/after.
- `083-lucide-icon-consolidation.md`: the shared icon set to design with.

## Open Questions (draft only)

- Which design tool and artifact format will support the initial style exploration?
- Ship the shared layer behind a toggle so pages can move over one at a time,
  or switch everything at once?
- Is any page a candidate to cut outright rather than redesign?
