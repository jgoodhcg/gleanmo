---
title: "Timer Dashboard Inline Create"
status: draft
description: "True inline parent-entity creation on timer dashboards, replacing the tactical bounce-link empty state"
created: 2026-07-26
updated: 2026-07-26
tags: [ux, timers, forms, htmx]
priority: low
---

# Timer Dashboard Inline Create

## Intent

Follow-up promised by `inline-entity-creation.md` (see its Scope and Timer
integration sections). That work unit ships a tactical fix for the timer
dashboard empty state (bounce-link to the parent new-form at
`timer/routes.clj:343-344`); this one replaces it with real inline creation
on the dashboard surface itself — zero parents (no projects/books/
meditations) should mean "type a name, start the timer" on one screen.

## Specification (sketch — refine before ready)

- Reuse the inline-create endpoints (`/app/crud/inline/<entity>/new` + POST)
  shipped by `inline-entity-creation.md` — this is a new consumer surface,
  not new infrastructure.
- Empty state renders the mini-form directly (no click-to-reveal needed when
  there's nothing else on the page).
- Non-empty dashboards get a `+ New <parent>` affordance near the parent
  picker that expands the same mini-form.
- On success: parent list re-renders with the new entity present; ideally the
  timer for it starts (or focuses) immediately.

## Validation

- [ ] E2E: zero-project user visits `/app/timer/project-log`, creates a
      project inline, starts a timer — one screen throughout.

## Scope

- Blocked by `inline-entity-creation.md` Phases 1–2. List-view/dashboard
  surfaces beyond timers stay out.

## Context

- Parent spec: `inline-entity-creation.md` (Timer integration + Scope).
- Empty state today: `src/tech/jgood/gleanmo/timer/routes.clj:336-349`.

## Open Questions (draft only)

- Auto-start the timer on inline-create success, or just select the parent?
