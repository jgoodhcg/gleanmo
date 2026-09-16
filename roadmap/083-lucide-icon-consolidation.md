---
title: "Lucide icon consolidation"
status: ready
description: "One inline-Lucide icon system replacing emoji, per-page hand-drawn SVGs, and stray Heroicons"
created: 2026-09-16
updated: 2026-09-16
tags: [ui, icons]
priority: medium
---

# Lucide icon consolidation

## Intent

Every glyph in the app comes from one icon system, drawn in one style, from one namespace.

Four approaches coexist today: seven inline Lucide SVGs in `ui/icons.clj` (the intended
canonical namespace), twelve private hand-drawn icons in `overview.clj`, emoji standing in
for icons in the mobile tab bar and the timers dashboard, and stray Heroicons v1 SVGs in
three other namespaces. The mix reads as inconsistent — emoji render differently per
platform and fight the dark-neon stroke aesthetic — and leaves no written guidance for
producing the next icon.

Decision made 2026-09-16 after surveying Font Awesome, Lucide, Tabler, Heroicons,
Phosphor, and Iconify: Lucide-first, inline SVG, no webfont, no JS icon runtime.

## Specification

- `src/tech/jgood/gleanmo/ui/icons.clj` is the single icon namespace. Its ns docstring
  documents the style guide so hand- and AI-drawn customs match: 24×24 viewBox,
  `fill="none"`, `stroke="currentColor"`, `stroke-width="2"`, round caps and joins; size
  via Tailwind classes through the `:class` opt.
- Replace emoji in `shared.clj` `primary-surfaces` with Lucide icons:
  🏠 → `house`, ⏱️ → `timer`, ➕ → `plus` (exists), ✅ → `check` (exists).
- Replace emoji in `timers.clj` sections: 📋 → `clipboard-list`, 🧘 → the `medit` custom
  (graduating from `overview.clj`), 📖 → `book-open`.
- Graduate the twelve `icon-svg` cases in `overview.clj` into public functions in
  `ui/icons.clj` (book, medit, briefcase, check-square, pill, recycle, drop, activity,
  dumbbell, calendar, map-pin); `overview.clj` consumes the namespace instead of drawing
  its own. Standardize stroke width — the private copy uses 1.7, the house wrapper uses 2.
- Migrate stray Heroicons v1 SVGs to Lucide equivalents in
  `crud/views/formatting.clj`, `crud/views.clj`, and `habit_log.clj`.
- When Lucide lacks a glyph (verified 2026-09-16: no lotus/meditation pose), prefer
  drawing a custom in the house style. Tabler is an accepted fallback source (verified:
  `yoga`) — note provenance in that icon's docstring so future drawing imitates the
  right source.

## Validation

- [ ] `just lint-fast` on every touched `.clj` file; `just check` after the batch
- [ ] Unit tests that embed Heroicons SVG shapes (`test/tech/jgood/gleanmo/test/crud/views/formatting_test.clj`) updated and passing
- [ ] `just e2e-shot-series` baseline before edits and another tick after — the tab bar
  and timers dashboard visibly change
- [ ] No emoji remains as a UI icon in `shared.clj` / `timers.clj` (grep)
- [ ] Grep finds no new `[:svg` hiccup outside `ui/icons.clj` (landing-page brand marks
  excepted)

## Scope

- The landing page (`home.clj`) keeps its bespoke line icons and brand marks (GitHub,
  mail). It is layout-specific marketing surface, not app UI.
- No webfont, icon CDN, or JS icon runtime (`lucide.createIcons`, Iconify). Inline SVGs
  need no HTMX `afterSettle` hook and no `sw.js` cache entries — that is why they won.
- No icon-picker UI for entities. `calendar-event/icon` in
  [010-calendar.md](./010-calendar.md) is a separate question.
- Favicon and PWA touch icons untouched.

## Context

- Alternatives rejected 2026-09-16: Font Awesome — webfont payload/FOUT and a solid
  aesthetic that clashes with the existing stroke icons. Tabler as primary — user call
  that it looks dated; kept as fallback. Both sets are 24×24 stroke-2 and visually
  compatible enough to mix sparingly.
- Ecosystem check 2026-09-16: Lucide 24.5k stars, 367.8M monthly `lucide-react` downloads
  vs Tabler 21.7k stars, 11.5M `@tabler/icons-react`; both actively maintained. Lucide is
  shadcn/ui's default, so models reproduce its path data reliably from memory — which
  matters because AI does the icon copying here.
- Licenses: Lucide ISC, Tabler MIT; both permissive, both fine inline.
- Current inventory: `ui/icons.clj` (canonical `lucide` wrapper), `overview.clj`
  `icon-svg`, `shared.clj` `primary-surfaces`, `timers.clj` sections, Heroicons strays in
  `crud/views/formatting.clj`, `crud/views.clj`, `habit_log.clj`.
- Copy workflow: lucide.dev → icon → Copy SVG → paste the path children into a new
  `defn` wrapping them in the `lucide` helper. Already present: `plus`, `check`,
  `pencil`, `x`, `arrow-right`, `grip-vertical`, `clock-arrow-up`.

## Notes

- Sizing convention to settle during migration: the `lucide` wrapper sizes via `:class`
  (default `w-4 h-4`) while `overview.clj` passes pixel `:width`/`:height`. Default to
  Tailwind classes; extend the wrapper with an explicit `:size` opt only where the
  timeline rings genuinely need pixel-exact sizes (13/15/16px).
