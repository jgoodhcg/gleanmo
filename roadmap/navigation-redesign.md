---
title: "Navigation & Page Architecture Redesign"
status: ready
description: "Layered navigation (primary surfaces vs. drill-down pages), consistent styling across all pages, and an intentional information architecture"
created: 2026-07-27
updated: 2026-07-28
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

## Resolved decisions (user, 2026-07-28)

- **Scope: both threads, full pass.** IA restructure *and* the styling audit
  ship together, before the Airtable prod migration runs — the user wants to
  validate the new schemas in a deployed app that already has its final
  navigation.
- **Styling vocabulary: shared Rum components**, not a documented class list.
  `page-shell`, `section-header`, `card`, `empty-state` become real components
  so new pages get consistency by default and drift becomes a deliberate act.
  Accepted cost: every page is rewritten to consume them, so the diff is large.
- **`mobile-tab-bar.md` is absorbed into this work unit.** The bottom tab bar
  *is* the mobile primary layer, so mobile navigation gets designed once
  rather than twice. That file becomes a pointer here rather than separate
  work.

## Still open (decide during execution)

- How much goes behind a command palette (`backlog.md` Generic Components)
  on desktop vs. visible chrome? Default: no palette in this pass; the
  sidebar plus tab bar should carry it.
- Whether the sidebar survives on mobile at all once the tab bar exists, or
  becomes a "more" sheet behind one of the tabs.

## Folded-in fixes

- **Timeline day headings never stick** (`backlog.md`): the shell's
  `overflow-x-hidden` makes that div the scrolling ancestor, so every
  `position: sticky` in the app is inert. The page-shell component is the
  right place to fix this, since it owns the overflow strategy. Any sticky
  header elsewhere is silently broken today too — the audit should confirm.
- Container-width and heading drift (`container.mx-auto` vs `max-w-4xl` vs
  `max-w-2xl` on the custom screens) collapses into `page-shell` variants.

## Scope of the audit

Every route in `app.clj`, including custom screens (workout, boulder,
calendar year, timers workspace) and CRUD-generated pages.

## Progress (2026-07-28)

**Shipped:**
- `app/layout.clj` — the shared vocabulary: `page-shell` (with `content-widths`
  :narrow/:normal/:wide/:full), `page-header`, `section-header`, `card`,
  `empty-state`. `page-shell` owns the bottom clearance the tab bar needs.
- `shared/primary-surfaces` — layer 1 (home, timers, log, today), shared by
  the sidebar and the tab bar so they cannot disagree.
- `shared/mobile-tab-bar` — fixed bottom nav, five slots, prefix-matched
  active state, "more" toggles the sidebar. This absorbs `mobile-tab-bar.md`.
- Sidebar restructured into Log Something / Review / Manage.
- `/app/log` hub — the tab bar's logging destination, built from the shared
  `quick-action-items`.
- **Converted to the shell:** entities / activity-logs / stats dashboards,
  timers workspace, Today, Task Focus.

**Not yet converted** (still on their own shells):
- CRUD list pages (`crud/views.clj`) and **CRUD new/edit forms**
  (`crud/forms.clj`). The forms are a deliberate hold: they sit at
  `w-full md:w-96`, and moving them to `:narrow` (`max-w-2xl`) changes the
  look of *every* form in the app. That deserves its own before/after
  screenshot pass rather than riding along here.
- Custom screens `boulder.clj` and `workout.clj`. Their containers already
  match `:narrow` (`max-w-2xl … pb-24`), so they are visually consistent
  already; the conversion is mechanical but their shells live inside view
  functions rather than at the `side-bar` call site, so it is a real
  refactor rather than a substitution.
- `user.clj`, `medication_history.clj`, `habit_log.clj`, `bm_log.clj`,
  `meditation_log.clj`, `calendar.clj`.

**Still open:** the command-palette question (default: no palette this pass),
and whether the sidebar should become a "more" sheet on mobile now that the
tab bar carries the primary layer.

**Note:** the sticky-heading bug in `backlog.md` ("Timeline Day Headings Never
Stick") is still unfixed — `page-shell` does not yet own the overflow
strategy, so the shell's `overflow-x-hidden` still breaks every
`position: sticky` in the app.

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
