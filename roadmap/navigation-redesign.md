---
title: "Navigation & Page Architecture Redesign"
status: draft
description: "Layered navigation (primary surfaces vs. drill-down pages), consistent styling across all pages, and an intentional information architecture"
created: 2026-07-27
updated: 2026-07-27
tags: [ux, navigation, design-system, architecture]
priority: medium
---

# Navigation & Page Architecture Redesign

## Intent

User-requested (2026-07-27), immediately after the sidebar frequency reorder
(`qol-quick-actions.md` item 1) shipped: the flat sidebar reorder is a good
patch, but the user wants **more drastic changes** — "layering functionality
and pages as necessary and making sure all pages have consistent styling
applied."

Two threads:

1. **Layered navigation / information architecture.** Today every
   destination is one flat sidebar list (~20 links). A layered model would
   promote a small set of primary surfaces (home, timers, quick add, tasks)
   and demote everything else behind them (dashboards → entity management →
   CRUD lists; stats behind a stats hub; per-entity timer pages behind the
   workspace — that layering already started with `unified-timer-page.md`).
   Group by user intent (log something / see status / manage data / analyze)
   rather than by implementation type (CRUD vs. custom screen).

2. **Consistent styling across all pages.** Pages were built at different
   times with drifting conventions: heading sizes/emoji use, container
   widths (`container.mx-auto` vs `max-w-4xl`), section header styles, card
   borders, empty-state patterns, sticky/fixed layering (see the home
   timeline z-index bug in `backlog.md`). An audit + a small shared layout
   vocabulary (page shell, section header, card, empty state) applied
   everywhere.

## Open questions (draft)

- Relationship to `mobile-tab-bar.md`: a bottom tab bar may *be* the primary
  layer on mobile — does this work unit absorb it or build on it?
- Relationship to `qol-quick-actions.md` item 5 (home quick-actions strip)
  and `workflow-optimization.md` (adaptive ordering): the strip is probably
  the first "layer 1" artifact.
- How much goes behind a command palette (`backlog.md` Generic Components)
  on desktop vs. visible chrome?
- Styling: codify as shared Rum components (page-shell, section, card) or
  as a documented Tailwind class vocabulary? Components are more enforceable.
- Scope of the audit: every route in `app.clj`, including custom screens
  (workout, boulder, calendar year) and CRUD-generated pages.

## Context

- Current sidebar: `src/tech/jgood/gleanmo/app/shared.clj` `side-bar`
  (frequency-ordered 2026-07-27; analytics numbers in
  `qol-quick-actions.md`).
- Layering precedent: `unified-timer-page.md` made `/app/timers` the primary
  timer surface with per-entity pages demoted to drill-down stats.
- Known layering bug to fold in: home timeline z-index over the mobile top
  bar (`backlog.md` → Dashboard UI).
- Related: `mobile-tab-bar.md`, `workflow-optimization.md`,
  `keyboard-navigation.md`, `ui-juice.md`, backlog Command Palette.
