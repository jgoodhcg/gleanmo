---
title: "Keyboard Navigation & Focus Audit"
status: draft
description: "Audit of keyboard accessibility across CRUD forms and entity index views, with a phased remediation roadmap"
created: 2026-07-09
updated: 2026-07-09
tags: [accessibility, forms, ux, keyboard]
priority: medium
---

# Keyboard Navigation & Focus Audit

## Intent

Make every existing form and entity index view fully operable by keyboard: reach every control with Tab, see where focus is, and activate actions without a mouse. Prompted by the observation that submit buttons can't be tabbed to in Safari.

## The Safari report, specifically

Safari **by default skips `<button>` and `<a>` elements when tabbing** — only text fields and popups get Tab focus. This is a browser preference, not a bug in our markup: Safari → Settings → Advanced → "Press Tab to highlight each item on a webpage" (or use `Option+Tab` without changing settings). `tabindex` does not override it.

So the submit button issue is not directly fixable in code, but two mitigations matter:

1. **Enter-to-submit must always work.** Pressing Enter in a text/number/date input submits the form via the implicit submission mechanism — this works today because the Create/Save buttons are real `type="submit"` buttons inside the form. Keep it that way (never replace submit buttons with JS click handlers).
2. Everything else in this audit makes keyboard use better in all browsers, including Safari with the preference enabled.

## Audit findings (what exists today)

### Forms (`crud/forms.clj`, `crud/forms/inputs.clj`)

1. **Broken label association (every input type).** All `render` methods emit `[:label {:for input-name}]` but the inputs only get a `name` attribute, never an `id`. The `for` points at nothing: clicking a label doesn't focus its field, and screen readers announce unlabeled inputs. Affects string, textarea, boolean, number, int, float, local-date, instant, enum, set-enum, boolean-or-enum, single- and many-relationship — `inputs.clj` throughout.
2. **Checkbox (`:boolean`)** has the same missing-`id` problem; label-click toggling doesn't work.
3. **Choices.js-enhanced selects** (`data-enhance="choices"`: relationships, set-enum) hide the native `<select>` and substitute a search input. Basic Tab/arrow/Enter works via Choices.js, but: removing selected items in multi-selects is mouse-only (the × buttons aren't reachable by Tab); and `required` on the hidden native select can make Safari/Chrome try to focus an unfocusable element on validation failure ("An invalid form control is not focusable" — form silently fails to submit).
4. **Focus loss after htmx submit.** Forms use `hx-swap "outerHTML"` + `hx-select` on the form itself — after a save, the whole form is replaced and focus falls back to `<body>`. A keyboard user has to Tab through the entire sidebar again.
5. **Tab order itself is fine** — DOM order matches visual order (single-column grid), Cancel precedes Save in edit forms, Delete is last. No `tabindex` juggling needed (see [form-tab-ordering.md](./form-tab-ordering.md), which this supersedes/absorbs).

### Index views (`crud/views.clj`)

6. **List view actions are hover-only.** Edit/Delete sit in a container with `sm:opacity-0 sm:group-hover:opacity-100` (`views.clj:725`). Keyboard focus does not trigger `group-hover`, so a keyboard user tabs onto invisible controls. Needs `group-focus-within:opacity-100` (and ideally `focus-visible` on the controls themselves).
7. **"Disabled" pagination links are still tabbable.** Previous/Next use `pointer-events-none` + `href="#"` when inactive (`views.clj:852`, `views.clj:867`). Pointer-events doesn't affect keyboards: Tab lands on them and Enter navigates to `#`. Render them as non-anchor spans (or add `tabindex="-1"` + `aria-disabled="true"`) when inactive.
8. **Card view link has `role="button"`** (`views.clj:516`) on what is a navigation `<a>`. Screen readers will announce a button and users expect Space to activate; Space scrolls instead. Drop the role.
9. **Table view** is keyboard-fine structurally (real links and submit buttons), but Delete uses `onsubmit` + `confirm()` — works with keyboards, no change needed.

### Global chrome (`app/shared.clj`, `tailwind.css`)

10. **No skip link.** The sidebar puts ~20 links and a sign-out button before main content on every page. A keyboard user tabs through all of it on every page load. Add a visually-hidden "Skip to content" link targeting `#side-bar-page-content`.
11. **No visible focus styles on buttons/links.** `.form-button-primary/-secondary`, `.btn`, and `.link` define `:hover` but no `:focus-visible` styles (`tailwind.css:421-443`); the mobile menu button explicitly sets `focus:outline-none` with no replacement (`shared.clj:166`). With default outlines suppressed or subtle on a dark theme, focus position is invisible.
12. **Mobile menu toggles don't manage focus** and lack `aria-expanded`/`aria-controls`; when the sidebar opens, focus stays on the (now hidden) hamburger.

## Roadmap

### Phase 1 — Quick wins in forms (small diff, high value)

- [ ] Add `:id input-name` to every input/select/textarea in `inputs.clj` so `label for` works (input names are unique per form).
- [ ] Add a shared `:focus-visible` style (gold ring to match hover treatment) to `.form-button-primary`, `.form-button-secondary`, `.btn`, `.link` in `tailwind.css`; remove the bare `focus:outline-none` on the mobile menu button.

### Phase 2 — Index views

- [ ] List view: add `sm:group-focus-within:opacity-100` alongside the group-hover rule so Edit/Delete are visible while focused.
- [ ] Pagination: render inactive Previous/Next as `<span>` (not `<a href="#">`).
- [ ] Card view: remove `role="button"` from the edit link.

### Phase 3 — Global navigation

- [ ] Skip-to-content link as the first element inside `side-bar` (visually hidden until focused), targeting `#side-bar-page-content` with `tabindex="-1"` on the target.
- [ ] `aria-expanded`/`aria-controls` on the mobile menu button; move focus into the sidebar on open.

### Phase 4 — Enhanced selects & htmx focus

- [ ] Verify required-field validation with Choices.js-hidden selects (submit an empty required relationship and confirm the browser doesn't silently refuse); if broken, move `required` handling to visible elements or server-side flash.
- [ ] Keyboard path for removing items from multi-selects (Choices.js `removeItemButton` keyboard support, or document Backspace-in-search behavior).
- [ ] After htmx form swap, restore focus (e.g. `hx-swap` `focus-scroll` or a small `htmx:afterSettle` hook focusing the form header/first error).

## Validation

- [ ] Manual pass in Safari with "Press Tab to highlight each item" enabled and in Firefox/Chrome: new form, edit form, table/card/list views, pagination, sidebar.
- [ ] Every interactive element reachable by Tab, visibly focused, and activatable by Enter/Space.
- [ ] Label click focuses/toggles its field for every input type.
- [ ] Enter in a text field submits new/edit forms.

## Scope

- Existing CRUD forms and index views, sidebar, pagination. Not included: modal focus trapping, full screen-reader/ARIA audit, viz/dashboard keyboard interaction (ECharts), timer pages.

## Context

- `src/tech/jgood/gleanmo/crud/forms/inputs.clj` — all input rendering (label/id fix lands here once).
- `src/tech/jgood/gleanmo/crud/views.clj` — table/card/list views, pagination, view selector.
- `src/tech/jgood/gleanmo/app/shared.clj` — sidebar, mobile menu.
- `resources/tailwind.css` — button/link/focus styles; Choices.js focus styles already exist (`:769+`).
- `resources/public/js/main.js:250` — Choices.js initialization.
- Supersedes the tab-ordering concern in [form-tab-ordering.md](./form-tab-ordering.md): audit found DOM order already matches visual order; the real gaps are label association, focus visibility, and hover-only controls.
