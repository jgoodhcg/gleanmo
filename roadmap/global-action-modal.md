---
title: "Global Action Modal"
status: draft
description: "One shared HTMX modal shell for confirm-and-act prompts, replacing per-page overlays and inline prompt slots"
created: 2026-07-28
updated: 2026-07-28
tags: [ux, htmx, components, layout]
priority: medium
---

# Global Action Modal

## Intent

User-raised (2026-07-28) while reworking the timers page: *"I think we need
some kind of global modal pop up action component for stuff like the above."*

"The above" is the relocate prompt — switching the global current location
while timers are running asks whether to split them and continue at the new
place. Today that prompt renders into a page-local `#relocate-prompt` div
that lives inside the START TIMER section, which means the question about
*running timers* appears under the *start* controls, below the fold on
mobile, and can be missed entirely. It is a modal decision rendered as an
inline one.

There is no shared modal in the app. The only precedent is `#bc-modal`
(`app/calendar.clj:92`), a fixed overlay hardcoded into the calendar page
with its dismiss logic inlined as `hx-on` string JavaScript repeated at four
call sites. Any second consumer today would copy that string.

The goal is one shell that any page can target: server renders the prompt
body, HTMX swaps it in, the shell handles show/hide, backdrop, focus, and
Escape. Confirm-and-act flows stop being a per-page layout problem.

## Specification

### The shell

A single container rendered once per authenticated page, in
`app/layout.clj`'s `page-shell` (so every page that uses the shared shell
gets it for free — this is the reason `navigation-redesign.md` consolidated
onto `page-shell` in the first place):

```clojure
(defn action-modal-container
  "Empty target for confirm-and-act prompts. Visible only when it has
   content; pages post prompt fragments into #action-modal."
  [] ...)
```

- Fixed overlay, centered panel, backdrop, `z-50` (above the mobile tab bar).
- Empty innerHTML ⇒ hidden. Non-empty ⇒ shown. That rule lives in
  `main.js`, not in an `hx-on` attribute string, so consumers only need
  `hx-target "#action-modal"`.
- Dismiss paths: backdrop click, Escape, and any element marked
  `data-modal-dismiss` inside the panel. All clear innerHTML.
- Focus moves into the panel on open and returns to the triggering element
  on close; focus is trapped while open. Backdrop scroll is locked.

### The body helper

A `modal-panel` helper for the common shape — title, body text, and a row of
actions — so consumers write content, not chrome:

```clojure
(layout/modal-panel
 {:title   "Move 2 running timers to Kitchen?"
  :body    "Each one is split at this moment…"
  :actions [(layout/modal-action {:label "Split and continue" :hx-post "…"})
            (layout/modal-dismiss {:label "Keep them running here"})]})
```

Destructive actions get a red treatment; the default is the neon-yellow
affirmative already used by the relocate confirm.

### First consumers

1. **Relocate prompt** (`app/timers.clj`) — `POST /app/timers/current-location`
   targets `#action-modal` instead of `#relocate-prompt`; the inline div and
   its `onclick` dismiss string disappear. This is the motivating case.
2. **Calendar** (`app/calendar.clj`) — retire `#bc-modal` and its four
   copies of the `hx-on` dismiss string onto the shared shell. Proves the
   component handles a form-bearing modal, not just a confirm.

A third consumer is *not* required to justify the work, but delete-confirm
in CRUD views is the obvious next one.

### Non-goals for the shell

Not a general dialog framework: no stacking, no per-modal sizing API beyond
one `:width`, no client-side-only modals (content always comes from the
server). If a page needs something the shell can't express, it renders its
own markup rather than growing the shell.

## Validation

- [ ] `just lint-fast` per edit; `just check` before commit.
- [ ] E2E: `test-timers-workspace.ts` relocate flow passes against the modal
      (confirm + dismiss paths, and Escape as a third dismiss).
- [ ] E2E: calendar event create/edit/delete still work through the shared
      shell.
- [ ] Keyboard: Tab cycles within the open panel; Escape closes; focus
      returns to the trigger. Cross-check `keyboard-navigation.md`.
- [ ] Mobile screenshot: panel clears the fixed tab bar and is readable at
      375px-class widths.
- [ ] No page-local modal containers remain (`grep -r 'bc-modal\|relocate-prompt'`
      returns nothing).

## Scope

**Out:** toast/snackbar notifications (different lifetime — no decision to
make, no focus capture; a separate component if wanted), inline expanding
disclosures, multi-step wizards inside a modal, converting CRUD forms
wholesale to modals, and animation polish (`ui-juice.md` owns motion).

## Context

- Motivating prompt: `relocate-prompt` in `src/tech/jgood/gleanmo/app/timers.clj`,
  rendered into `#relocate-prompt` by `set-current-location!`.
- Existing one-off precedent to absorb: `htmx-modal-container` and its four
  `hx-on` dismiss strings in `src/tech/jgood/gleanmo/app/calendar.clj`.
- Shell to host it: `page-shell` in `src/tech/jgood/gleanmo/app/layout.clj`.
- Behavior layer: `resources/public/js/main.js` — follow the existing
  `DOMContentLoaded` + `htmx:afterSettle` init pattern used by the generic
  list filter and changed-field highlighting.
- Accessibility expectations: `roadmap/keyboard-navigation.md`.
- Styling rule: no `px` values in Tailwind class names (AGENTS.md).

## Open Questions (draft only)

- Does the relocate decision actually want to be *blocking*? A modal
  interrupts; the current inline prompt can be ignored by scrolling past.
  The user's complaint was that the inline prompt is easy to miss, which
  argues for blocking — but a modal on every location switch with a timer
  running may become an obstacle if location switches are frequent. Worth
  using the reworded inline version first and confirming the annoyance is
  real before making it interrupt.
- Should the shell also serve non-decision content (a details peek), or stay
  strictly confirm-and-act? Staying narrow keeps the focus/dismiss semantics
  simple.

## Notes

Deliberately left at `draft` rather than executed alongside the 2026-07-28
timers pass: that pass reworded the relocate prompt in place, which may
resolve enough of the "I missed it" problem to change what this component
needs to be. Promote to `ready` once the reworded inline prompt has been
used for a while and the blocking question above is answered.
