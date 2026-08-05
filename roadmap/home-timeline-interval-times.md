---
title: "Show interval stop times on the home timeline"
status: draft
description: "Render an interval's stop time alongside its start time in the home activity timeline, so a row shows its full span"
created: 2026-08-04
updated: 2026-08-04
tags: [frontend, ux, home, timeline]
priority: medium
---

# Show interval stop times on the home timeline

## Intent

The home activity timeline renders each row's left rail with a single clock
time — the start. An interval entity (meditation, reading, project, exercise
session, calendar event) shows when it began but not when it ended, so two rows
can look identical while one was a 10-minute meditation and the other a
45-minute one. The duration appears on desktop as a span ("45m") but never as a
stop clock time, and not at all on mobile. Going to the home page should show
each interval's full start–stop span at a glance.

This is a rendering change. The stop instant is already extracted per row — it
just isn't displayed.

## Specification

For entities whose interval has both a beginning and an end (i.e.
`(::activity-time :end-instant)` is present), render the stop time next to the
start. Point-in-time entities (habit-log, bm-log, medication-log, symptom-log,
mood-log, task) keep today's single-timestamp treatment unchanged.

The layout is the main open question (see below). Concrete candidate, smallest
change first:

1. Turn the left rail's single time into a range for intervals:
   `9:30 – 10:15a`, deduping the meridiem when start and end share it
   (`9:30 – 9:45a`, not `9:30a – 9:45a`).
2. Keep the desktop right column's duration and relative time as-is; the rail
   range is additive and also fixes mobile, where duration is the only span cue
   today.

Stronger alternative worth weighing before `ready`: a small visual extent (a bar
whose width reflects the interval's proportion of the day) in addition to the
clock times — this is what "see a visualization" most literally asks for, and it
makes a 10-min vs. 90-min interval distinguishable without reading. Decide
between text range, extent bar, or both.

Running intervals (beginning set, no end — `entity-status :running`) already
have a distinct treatment (cyan ring, LIVE badge) and already appear in the
"Running now" strip, so they need no stop time; leave them on the elapsed
display they have today.

## Validation

- [ ] `just lint-fast src/tech/jgood/gleanmo/app/overview.clj`.
- [ ] Before/after e2e screenshots (`SCREENSHOT_PHASE` pair) of `/app`: an
      interval row shows both start and stop; a point-in-time row is unchanged.
- [ ] Manual: a meditation-log or project-log with beginning+end renders the
      span; a habit-log renders a single time as today.
- [ ] Mobile width: the chosen layout fits the existing left rail (or the
      restructure is documented and the skeleton at `timeline-skeleton` is
      updated to match).
- [ ] Cross-midnight case: an interval ending the next day shows the date on
      the stop side (e.g. `11:30p – 12:15a Wed`).

## Scope

- Home overview timeline rows only — `render-timeline-row` in
  `src/tech/jgood/gleanmo/app/overview.clj`.
- Not the dedicated timeline page ([activity-timeline.md](./activity-timeline.md)),
  not CRUD list/card views, not the "Running now" strip.
- No schema or query changes — end instants are already stored and already
  extracted by `activity-time`.

## Context

- `src/tech/jgood/gleanmo/app/overview.clj`:
  - `render-timeline-row` — the row renderer; left rail at the `w-20` column
    renders only `(timeline-time ctx start)`.
  - `activity-time` — already returns `:end-instant` for interval entities by
    finding the `end` field; the data is there, just not rendered.
  - `entity-status` — already keys `:running` off a present start with nil end.
  - `timeline-time` — the `h:mm a` formatter to reuse for the stop side.
  - `timeline-skeleton` — loading placeholder shaped like the rail; update if
    the rail layout changes.
- `src/tech/jgood/gleanmo/crud/views.clj` — `build-time-display` returns
  `:mode :duration` and a `:duration` string but no end clock-time string; a
  stop-time formatter can live inline in overview or extend this.
- Related: [activity-timeline.md](./activity-timeline.md) (separate dedicated
  page), [workflow-optimization.md](./workflow-optimization.md).

## Open Questions

- **Layout.** Rail range vs. desktop right-column stop vs. a visual extent bar
  vs. a combination. The rail is `w-20` and tight on mobile — a range or bar may
  force a small restructure.
- **Duration coexistence.** Keep the desktop "45m" duration alongside a stop
  time (redundant but scannable), or let the stop time replace it?
- **Meridiem dedup.** Worth the formatting branch (`9:30 – 10:15a`) or always
  full (`9:30a – 10:15a`)?
- **Extent bar provenance.** If the bar is chosen, what defines its scale — the
  logged day (midnight to midnight, so a 2h meditation is a wide bar) or a
  rolling 24h window around the start?

## Notes

- This fell out of a session reviewing the home timeline: rows tell you *that*
  you meditated and *when you started*, but not *how long* in clock terms
  without doing the arithmetic against the duration string. The cheapest fix
  (rail range) is independently useful even if the extent-bar direction is
  rejected.
