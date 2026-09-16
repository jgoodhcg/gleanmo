---
title: "Lucide icon consolidation"
status: ready
description: "One inline-Lucide icon system, one icon per entity type, replacing emoji, hand-drawn SVGs, and stray Heroicons in three phases"
created: 2026-09-16
updated: 2026-09-16
tags: [ui, icons]
priority: medium
---

# Lucide icon consolidation

## Intent

Every glyph in the app comes from one icon system, drawn in one style, from one namespace —
and each entity type is represented by the same icon everywhere it appears.

Four approaches coexist today: seven inline Lucide SVGs in `ui/icons.clj` (the intended
canonical namespace), eleven private hand-drawn icons in `overview.clj` (Lucide-like but not
Lucide, stroke 1.7), roughly seventy emoji standing in for icons across dashboards,
navigation, timers, and tasks, and four stray Heroicons v1 SVGs in three namespaces. The mix
reads as inconsistent — emoji render differently per platform and fight the dark-neon
stroke aesthetic — and the same entity already wears different icons on different pages
(meditation is 🧘 on timers, 📜 on the logs dashboard, a ring on the overview).

Decision made 2026-09-16 after surveying Font Awesome, Lucide, Tabler, Heroicons,
Phosphor, and Iconify: Lucide-first, inline SVG, no webfont, no JS icon runtime.

## Specification

### Foundations (apply to every phase)

- `src/tech/jgood/gleanmo/ui/icons.clj` is the single icon namespace. Its ns docstring
  documents the style guide so hand- and AI-drawn customs match: 24×24 viewBox,
  `fill="none"`, `stroke="currentColor"`, `stroke-width="2"`, round caps and joins; size
  via Tailwind classes through the `:class` opt.
- Real Lucide over look-alikes. Copy path data from lucide.dev; don't graduate an
  approximation when Lucide has the glyph.
- When Lucide lacks a glyph (verified 2026-09-16: no lotus/meditation pose), draw a custom
  in the house style. Tabler is an accepted fallback source (verified: `yoga`) — note
  provenance in that icon's docstring so future drawing imitates the right source.
- Every Lucide name below is proposed; confirm it exists on lucide.dev at copy time and
  record any substitution in this doc.

### Phase 1 — icon namespace and existing SVGs

- Replace `overview.clj`'s private `icon-svg` with real Lucide in `ui/icons.clj`; the
  overview consumes the namespace. Current key → Lucide:

  | `icon-svg` key | Lucide |
  |---|---|
  | `:book` | `book-open` |
  | `:medit` | custom (keep the ring design, restroked at 2) or Tabler `yoga` — decide on screenshot |
  | `:project` | `briefcase` |
  | `:task` | `square-check` |
  | `:pill` | `pill` |
  | `:habit` | `repeat` |
  | `:drop` | `droplet` |
  | `:pulse` | `activity` |
  | `:dumbbell` | `dumbbell` |
  | `:calendar` | `calendar` |
  | fallback (`:pin`) | `map-pin` |

- Migrate the Heroicons v1 strays to Lucide: the boolean ✓/✗ cells in
  `crud/views/formatting.clj` (two duplicated pairs, lines ~31 and ~160) → `check` / `x`;
  the delete button in `crud/views.clj` → `trash-2`; the copy button in `habit_log.clj` →
  `copy`.
- Introduce the entity icon registry (see Phase 3) seeded from overview's `type-meta`, so
  Phase 1 already reads icons through it rather than through `:icon-key` literals.

### Phase 2 — navigation and chrome

- Mobile tab bar, `shared.clj` `primary-surfaces`: 🏠 → `house`, ⏱️ → `timer`,
  ➕ → `plus`, ✅ → `check`.
- Sidebar (`shared.clj`): ⏱️ timers → `timer`, 🏁 goals → `flag`, 📅 calendar →
  `calendar-days`, 📊 stats → `chart-column`, 💊 medication history → `pill`,
  📋 activity logs → `list-checks`, 📦 manage entities → `boxes`, 🎯 task focus → `target`,
  ⚙️ account → `settings`, 🛡️ monitoring → `shield`.
- Chrome glyphs: ☰ menu → `menu`; ✕ close buttons in `shared.clj`, `app.clj`, and
  `calendar.clj` → `x`.
- Status badges in `shared.clj`: 🔒 Sensitive → `lock`, 📦 Archived → `archive`,
  🧻 BM logs → the bm-log entity icon.
- Page headers: "⏱️ Timers" (`timers.clj`) and "⏱️ Time Tracker" (`timer/routes.clj`) →
  `timer`.
- Location markers 📍 in `timers.clj` and `timer/routes.clj` → `map-pin`.
- Task screens: 📌 Today (`task_focus.clj`) → `pin`; ✨ empty state (`task_today.clj`) →
  `sparkles`; the hover ✓ complete button (`task_today.clj`) → `check`.

### Phase 3 — one icon per entity type

- An entity icon registry in `ui/icons.clj`: `(entity-icon entity-key opts)` returns the
  hiccup for that entity's icon, with a generic fallback. The dashboards, timers dashboard,
  overview timeline, and sidebar Quick Add read icons from it; nothing else maps entities to
  icons.
- `dashboard-card` in `dashboards.clj` takes the icon as hiccup instead of an emoji string.
- Parent entities and their logs share an icon (habit and habit-log are both `repeat`); the
  card label already distinguishes them.
- Proposed mapping:

  | Entity (and its log) | Lucide |
  |---|---|
  | task | `square-check` |
  | habit / habit-log | `repeat` |
  | meditation / meditation-log | custom `medit` |
  | medication / medication-log | `pill` |
  | location | `map-pin` |
  | project / project-log | `briefcase` |
  | book / reading-log | `book-open` |
  | book-source | `store` |
  | exercise / exercise-session | `dumbbell` |
  | exercise-set | `timer` |
  | exercise-line | `list-ordered` |
  | boulder-problem / boulder-attempt / boulder-session | `mountain` |
  | symptom-episode / symptom-log | `thermometer` |
  | mood-log | `smile` |
  | bm-log | `droplet` |
  | calendar-event | `calendar` |
  | goal | `flag` |

- Dashboard section and card icons that are not entities: 📅 ACTIVITY CALENDARS →
  `calendar-days`; 📊 STATISTICS → `chart-column`; 🔍 habit patterns → `search`. The
  per-entity calendar and stats cards use the entity icon, which removes the thirteen
  identical 🗓️.
- Timers dashboard (`timers.clj` `timer-entities`) drops its `:icon` strings for the
  registry — this corrects project-log, which is 📋 there today.

## Validation

Per phase:

- [ ] `just lint-fast` on every touched `.clj` file; `just check` after the batch
- [ ] `just e2e-shot-series` baseline before edits and another tick after
- [ ] `just e2e-test-all` passes (smoke covers the dashboards; today-mobile covers the tab bar)

Phase-specific:

- [ ] Phase 1: `formatting_test.clj` updated for the Lucide check/x and passing; grep finds
  no `[:svg` outside `ui/icons.clj` and `home.clj`
- [ ] Phase 2: tab bar and sidebar checked at mobile width; no emoji left in `shared.clj`, `timers.clj`, `timer/routes.clj`, `task_*.clj`
- [ ] Phase 3: no entity-to-icon mapping outside the registry (grep `icon-key`, `:icon "`)

Done when a repo-wide emoji grep over `src/` returns only allowlisted text:

```sh
rg -n '[\x{1F300}-\x{1FAFF}\x{2600}-\x{27BF}\x{2B00}-\x{2BFF}]' src --glob '!home.clj'
```

Allowlist: inline typographic ✓ in `goals.clj` status text ("Reached ✓", "✓ Completed").

## Scope

- The landing page (`home.clj`) keeps its bespoke line icons and brand marks (GitHub,
  mail). It is layout-specific marketing surface, not app UI.
- Typographic checkmarks inside sentences (`goals.clj`) stay as text.
- No webfont, icon CDN, or JS icon runtime (`lucide.createIcons`, Iconify). Inline SVGs
  need no HTMX `afterSettle` hook and no `sw.js` cache entries — that is why they won.
- No icon-picker UI for entities. `calendar-event/icon` in
  [010-calendar.md](./010-calendar.md) is a separate question.
- No per-entity colour changes; the dashboard `neon-*` accents stay as they are.
- Favicon and PWA touch icons untouched.

## Context

- Alternatives rejected 2026-09-16: Font Awesome — webfont payload/FOUT and a solid
  aesthetic that clashes with the existing stroke icons. Tabler as primary — user call
  that it looks dated; kept as fallback. Both sets are 24×24 stroke-2 and visually
  compatible enough to mix sparingly.
- Ecosystem check 2026-09-16: Lucide 24.5k stars, 367.8M monthly `lucide-react` downloads
  vs Tabler 21.7k stars, 11.5M `@tabler/icons-react`; both actively maintained. Lucide is
  shadcn/ui's default, so models reproduce its path data reliably from memory — which
  matters because AI does the icon copying here. Still copy from lucide.dev rather than
  memory.
- Licenses: Lucide ISC, Tabler MIT; both permissive, both fine inline.
- Inventory audit 2026-09-16:
  - `ui/icons.clj` — `lucide` wrapper plus `plus`, `check`, `pencil`, `x`, `arrow-right`,
    `grip-vertical`, `clock-arrow-up`.
  - `overview.clj` — `icon-svg` with 11 cases, keyed from `type-meta` (lines ~101–113);
    sized 13/15/16px via `:width`/`:height`.
  - Heroicons v1 — `crud/views/formatting.clj` (4, two duplicated pairs),
    `crud/views.clj` (1), `habit_log.clj` (1); `formatting_test.clj` embeds the shapes.
  - Emoji — `dashboards.clj` (~40 cards and section headers), `shared.clj` (tab bar,
    sidebar, badges, menu/close), `timers.clj`, `timer/routes.clj`, `task_today.clj`,
    `task_focus.clj`, `app.clj` and `calendar.clj` (✕ close).
- Copy workflow: lucide.dev → icon → Copy SVG → paste the path children into a new
  `defn` wrapping them in the `lucide` helper.

## Notes

- Sizing convention: the `lucide` wrapper sizes via `:class` (default `w-4 h-4`) while
  `overview.clj` passes pixel sizes. Default to Tailwind classes; extend the wrapper with
  an explicit `:size` opt only where the timeline rings genuinely need pixel-exact sizes
  (13/15/16px) — Tailwind-class `px` values are disallowed by the styling rules.
- Emoji carry colour; stroke icons inherit `currentColor`. Dashboard cards will need the
  icon wrapped in the card's `text-neon-*` accent to keep their visual identity.
- Boulder entities all share `mountain`; if that reads poorly on the entities dashboard,
  differentiate attempt/problem during Phase 3 rather than before.
- Each phase is independently shippable; Phase 3 depends on the registry introduced in
  Phase 1.
