---
title: "Navigation & Page Architecture Redesign"
status: done
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

**Complete.** Every authenticated page is on the shared shell.

- `app/layout.clj` — `page-shell` (widths :narrow/:normal/:wide/:full),
  `page-header`, `section-header`, `card`, `empty-state`.
- `shared/primary-surfaces` — layer 1 (home, timers, log, today), shared by the
  sidebar and tab bar so they cannot disagree.
- `shared/mobile-tab-bar` — this absorbs `mobile-tab-bar.md`. The old mobile
  top bar is gone; the sidebar carries its own close.
- Sidebar grouped Log Something / Review / Manage; `/app/log` hub added.
- **Converted:** all three dashboards, timers workspace, Today, Task Focus,
  every CRUD list and new/edit form, boulder (session, summary, problems),
  workout, user detail/edit, medication history, and the habit/bm/meditation
  stats pages.

Two things settled during the conversion:

- **Forms use `:narrow`, not a tier of their own.** A 24rem column centered in
  a ~64rem content area reads as adrift — the before/after screenshots made
  that obvious — and forms are the same shape as the custom screens already
  sitting at `:narrow`.
- **Home carries an `sr-only` h1.** It leads with Running Now and the timeline,
  where a visible title would spend space on the most-used page to say nothing,
  but every page needs exactly one heading. The `test:navigation` suite asserts
  that invariant across every kind of page.

## Coverage

`e2e/scripts/test-navigation.ts` guards the chrome: tab bar surfaces and active
state, the sidebar open/close cycle, the `/app/log` hub, and two invariants
checked on eleven routes covering every shell kind — exactly one `h1`, and no
focusable control hidden under the fixed tab bar (the regression that shipped
once already).

Written against roles, aria labels and hrefs rather than classes or pixels, so
restyling a page does not break it.

## Still open

- Command palette: no palette in this pass; the sidebar plus tab bar carry it.
- Whether the sidebar should become a "more" sheet on mobile now that the tab
  bar is the primary layer.
- ~~Timeline day headings never stick~~ — **closed 2026-07-28 by removing the
  feature.** The real cause turned out to be global rather than shell-level:
  `overflow-x: hidden` on html/body in `tailwind.css` (an iOS Safari fix)
  makes body a scroll container and disables `position: sticky` app-wide. The
  owner decided a pinned day heading wasn't wanted, so the classes went
  instead. See `backlog.md` — note that sticky remains unavailable app-wide,
  and the landing page header is inert for the same reason.
