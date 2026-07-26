---
title: "Mobile Bottom Tab Bar"
status: draft
description: "Fixed bottom navigation for the mobile PWA — logging and timers reachable without the hamburger sidebar"
created: 2026-07-26
updated: 2026-07-26
tags: [ux, mobile, pwa, navigation]
priority: medium
---

# Mobile Bottom Tab Bar

## Intent

60% of sessions are on the phone (Plausible 28d: iOS 56 / Mac 37), where the
only navigation is a hamburger → full-screen sidebar → scan a long list. A
fixed bottom tab bar is the native-mobile pattern for "3–5 destinations you
hit constantly" and pairs with the PWA standalone experience
(`pwa-experience.md`) — in standalone mode there is no browser chrome, so
persistent in-app navigation matters more.

## Specification (sketch — refine before ready)

- Fixed bottom bar, mobile breakpoints only (`md:hidden`), rendered by the
  shared layout next to the existing hamburger header in
  `app/shared.clj:172-199`.
- 3–5 tabs, candidates: **Home** (`/app`), **Log** (opens the quick-add list —
  possibly as a bottom sheet rather than a page), **Timers**
  (`/app/timer/project-log` directly, or a running-timer-aware target),
  **Today** (`/app/task/today`).
- Active-timer awareness: when a timer is running, the Timers tab shows a
  badge/pulse (the overview shell already computes active-timer summaries —
  `app/overview.clj:371-395`).
- Safe-area insets for iOS standalone (`env(safe-area-inset-bottom)`).
- Sidebar stays as-is for desktop and as the mobile overflow menu.

## Validation

- [ ] E2E mobile screenshots of every tab target with the bar visible.
- [ ] Manual on-device (iOS standalone PWA): tab targets, safe-area, no
      overlap with page action buttons (workout/gym screens have bottom CTAs).
- [ ] Desktop layout unchanged.

## Scope

- No desktop tab bar. No gesture navigation. No offline/service-worker work
  (that's `pwa-experience.md`).

## Context

- Mobile menu button + sidebar toggle: `app/shared.clj:172-199`.
- Static quick actions shipped first in `qol-quick-actions.md` (sidebar +
  home strip); this work unit supersedes neither — it relocates the top few
  for thumb reach.
- Bottom-CTA screens to check for overlap: `/app/exercise/session`,
  `/app/boulder/session`.

## Open Questions (draft only)

- Which 3–5 tabs? (Usage says: Home, project timer, habit log — but "Log" as
  a bottom sheet may serve all log types better than one hardcoded form.)
- Bottom sheet vs. page for the Log tab?
- Should the Timers tab deep-link to the running timer's page when one is
  active?
