---
title: "Essential-information UI redesign"
status: draft
description: "Reduce every page to its essential information, then use those findings to decide on a holistic minimal redesign with clear hierarchy, efficient use of space, and good ergonomics on phone and desktop"
created: 2026-09-18
updated: 2026-09-18
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

### Phase 1: Essential brief for every page

For each page, write a short brief before any visual work:

- the job the page does and the questions a visitor comes with;
- the minimum information and actions that answer them, ranked by importance
  (this ranking becomes the hierarchy);
- what the current page shows that is not essential, marked "cut unless it
  earns its way back";
- how the page is used on a phone versus desktop, if that differs.

`e2e/scripts/manifest.ts` is the page list, so coverage is checkable against
the routes the visual timeline captures. Briefs for all pages come before any
design direction is chosen, so shared patterns are visible up front.

### Phase 2: Explore and decide in Claude Design

- Group the briefs by the patterns they share: list/feed, single-entry detail,
  log form, dashboard/chart, focus/today, timers, navigation shell.
- Explore each pattern in Claude Design, mobile first, from the briefs rather
  than from screenshots of the current UI, using representative pages as test
  cases.
- Converge on one **holistic design direction**: type scale, spacing and
  density, colour and emphasis, the navigation shell, and the shared
  components that carry the patterns above.
- Record the decision and the rejected alternatives as a decision matrix in
  `.decisions/` (AGENTS.md "Decision Artifacts"), since it changes every page.

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

- [ ] Every manifest route has a brief
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
- `e2e/scripts/manifest.ts`: the canonical route list.
- `027-screenshot-runner.md`: series capture used for before/after.
- `083-lucide-icon-consolidation.md`: the shared icon set to design with.

## Open Questions (draft only)

- How the Claude Design output is recorded in the repo: exported HTML, a link,
  or screenshots?
- Ship the shared layer behind a toggle so pages can move over one at a time,
  or switch everything at once?
- Is any page a candidate to cut outright rather than redesign?
