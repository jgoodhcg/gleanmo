---
title: "Home timer display goes stale when the timer is stopped elsewhere"
status: draft
description: "A running timer shown on the home page keeps ticking forever after it is stopped on another device; decide between a pushed invalidation and a long-interval poll"
created: 2026-09-21
updated: 2026-09-21
tags: [frontend, timers, home, htmx, realtime]
priority: medium
---

# Home timer display goes stale when the timer is stopped elsewhere

## Intent

The home overview's "Running now" strip ticks entirely client-side: the server
emits the timer's start instant as `data-epoch-ms` and a one-second
`setInterval` recomputes the elapsed text from `Date.now()`
(`shared/tick-script`). Nothing ever invalidates it. The fragment that carries
it is `hx-trigger "load"` (`overview.clj`), so it renders once per page load and
never again.

So if a timer is stopped — or relocated, or deleted — on another device, or in
another tab, the home page keeps counting up as if it were still running, and
keeps doing so indefinitely. There is no bound on the lie: a phone left on the
home screen overnight shows a timer at 14 hours that was stopped at 20 minutes.

This is a multi-device problem specifically, which is exactly the shape of the
usage: start a timer on the phone, stop it at the desk.

**The client-side tick made this more visible, not worse.** Before, the elapsed
text was rendered server-side and sat frozen, which was wrong in its own way but
at least *looked* inert. A number that visibly advances reads as live and
authoritative, so a stale one is more actively misleading. The underlying
staleness predates the tick — the strip has never re-checked the server.

## Specification

After this, a home page left open does not display a timer that is no longer
running, beyond some bounded and deliberately-chosen window.

Two directions, and this work unit is to pick one:

**A. Long-interval poll.** Give the strip (or the whole `#overview-recent`
fragment) an `hx-trigger` on an interval — 60s, or a few minutes. The server
re-answers "what is actually running", and the strip re-renders with whatever is
true. Cheap to build, no new infrastructure, one more concept in a file that
already does this on the timer pages (`timer/routes.clj:387`, `app/timers.clj:75`
both poll `every 30s`).

**B. Pushed invalidation.** The server tells open pages that the set of running
timers changed — SSE, or htmx's SSE extension, or a websocket. Correct
immediately rather than eventually, no request when nothing changes.

Recommendation before analysis: **A, at a deliberately long interval.** The
staleness window that matters here is "minutes, not hours", and B buys latency
this problem does not appear to need while costing a persistent connection per
open page and a cross-instance fanout story (see Scope).

## Validation

- [ ] Two browser contexts as the same user: start a timer in one, open home in
      the other, stop the timer in the first. The home page stops showing it
      within the chosen window without a manual reload.
- [ ] A page left open for an hour with a genuinely running timer still shows
      it, still ticking, and has not accumulated duplicate intervals (see the
      leak note in Context).
- [ ] The chosen mechanism costs nothing measurable on the home page's existing
      query cascade — `active-timer-summaries` already runs one
      `fetch-active-timers` per timer entity config, sequentially and on purpose
      (`overview.clj:282`). A poll multiplies that by the poll rate.
- [ ] e2e: extend `e2e/scripts/test-home-running-timer.ts`, which already starts
      a timer and asserts the home strip ticks, with a stop-elsewhere case.

## Scope

- The home overview strip is the motivating surface. The timer pages already
  poll every 30s and are not stale in this way.
- **Not** general realtime for the app. If B is chosen, the temptation is to
  build a push channel and then reach for it everywhere; that is a much larger
  commitment than this defect justifies.
- If B is chosen, it collides with
  [077-scheduled-work-multi-instance.md](./077-scheduled-work-multi-instance.md):
  a tx listener that fans out invalidations runs per-container with no singleton
  guarantee, so a push from the instance that handled the write does not reach
  pages held open against a different instance. A is immune to this — every poll
  is an ordinary request that any instance can answer. That asymmetry may settle
  the decision on its own.

## Context

- `src/tech/jgood/gleanmo/app/shared.clj` — `tick-script` / `epoch-ms`, shared by
  the home strip, the workout screen and the bouldering screen.
- `src/tech/jgood/gleanmo/app/overview.clj` — `render-active-timers` emits the
  script; `active-timer-summaries` is the query behind it; `overview-shell` sets
  `hx-trigger "load"` on `#overview-recent`.
- `src/tech/jgood/gleanmo/timer/routes.clj:387` and
  `src/tech/jgood/gleanmo/app/timers.clj:75` — the existing `every 30s` polls,
  the precedent for direction A.
- **Latent interval leak, currently untriggered.** `tick-script` never clears its
  intervals and scans the whole document, so it is only safe because the home
  fragment swaps exactly once. Adding a poll to that fragment (direction A)
  stacks a fresh `setInterval` on every element per swap and leaves orphaned
  closures firing against detached nodes. **Whichever direction is chosen, the
  script needs an idempotence guard first** — skip elements already wired, or
  clear on swap. This is the one piece of work both options share.
- Related: [066-unified-timer-page.md](./066-unified-timer-page.md) (the timer
  workspace), [043-pwa-experience.md](./043-pwa-experience.md) (a backgrounded
  PWA is the worst case for a page left open),
  [archived/047-timer-stale-start-time.md](./archived/047-timer-stale-start-time.md)
  (the previous stale-timer bug, a different mechanism).

## Open Questions (draft only)

- **How stale is acceptable?** This sets the poll interval and decides whether B
  is worth considering at all. A minute? Five?
- **Poll the strip or the whole fragment?** `#overview-recent` renders the
  timers, the activity timeline and the stats together from one query cascade.
  Re-polling all of it keeps the page internally consistent but is much more
  expensive than re-polling the strip alone.
- **Does a backgrounded tab need to poll?** `visibilitychange` could pause the
  poll and force one refresh on wake, which is both cheaper and more correct
  than a fixed interval — a phone returning to a page after four hours wants an
  immediate answer, not to wait out the interval.
- **Should the client tick stop itself?** A cheap partial mitigation independent
  of either direction: cap the client-side count (stop ticking, or grey out,
  past some implausible duration) so the page degrades to "unknown" instead of
  confidently wrong.

## Notes

- Found while reviewing the client-side tick added 2026-09-21 (backlog item
  "Home Page Active Timers Don't Tick"). The tick fix is correct and shipped;
  this is the adjacent problem it brought into focus.
