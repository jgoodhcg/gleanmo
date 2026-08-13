---
title: "Performance Regression Tracking"
status: draft
description: "Make performance comparable across deploys — scheduled snapshots, cross-instance aggregation, per-SHA grouping, and a synthetic benchmark so low traffic still yields data"
created: 2026-08-13
updated: 2026-08-13
tags: [observability, performance]
priority: medium
---

# Performance Regression Tracking

## Intent

The dashboard at `/app/monitoring/performance` can tell you what this container
has done since you last pressed a button. It cannot answer the question the
instrumentation exists for: **did that commit make things faster or slower?**

Four structural reasons, all of them independent:

1. **Snapshots are manual.** `obs/persist-instance-snapshot!` has exactly one
   caller — the "Persist & Reset Metrics" button (`app.clj:384`). Forget to
   press it before a deploy and the numbers are gone; the accumulator is
   in-memory and the container is replaced. The 2026-02 architecture sketch in
   [performance.md](./performance.md) called for a 60-second flush task. It was
   never built, and the doc claimed for months that it had been.

2. **Documents are keyed per instance.** The id is
   `(keyword "performance-report" instance-id)` (`observability.clj:176`) and
   the page renders "This Instance" only. Every App Platform deploy is a new
   container with a new instance id, so before and after live in different
   documents that the UI never shows together — precisely at the moment you
   want them side by side.

3. **Multiple instances split the picture.** App Platform can run more than one
   container. Each accumulates independently, and the dashboard shows whichever
   one served your request. Even "how fast is the app right now" is a partial
   answer, and which part you get is arbitrary.

4. **The sample is whatever you happened to browse.** Route coverage, call
   counts and cache warmth all vary per session, so two snapshots are rarely
   comparable even on identical code.

Point 4 is the one that bites hardest, because it looks like data. The
2026-08-13 baseline in [timer-running-flag.md](./timer-running-flag.md) is the
worked example: `active-timers-for-user` on `/app/timers` ranged **15.13ms to
619.27ms across five calls in one session** — a 41× spread within a single span.
A 4-sample mean over that distribution mostly reports which call paid for a cold
JDBC connection. Comparing two such means across a deploy is comparing two
draws of noise, and a real regression well under 41% would hide inside it
completely.

## Specification

After this work, a deploy can be evaluated without a human remembering to do
anything.

**Scheduled persistence.** A worker task flushes the accumulator on an interval
instead of on a button press. The button stays — it is useful for bracketing a
deliberate experiment — but nothing depends on it.

**Reports grouped by git SHA, not by instance.** `:performance-report/git-sha`
is already captured on every document; nothing can currently *query* by it. The
per-instance document id has to give way to a scheme that lets one SHA's
measurements be gathered across every container that ran it. Tufte pstats merge
associatively (`timpl/merge-pstats`, already used at `observability.clj:144`),
so aggregation is available once the documents can be found.

**A comparison view.** Pick two SHAs, see per-route and per-span deltas. This is
the actual deliverable — the rest is plumbing to make it possible.

**A synthetic benchmark.** A fixed set of routes, hit a fixed number of times,
on a schedule, so every SHA gets a comparable sample whether or not anyone used
the app that day. Without this, a quiet week produces no data and a busy one
produces data shaped like the week rather than like the code.

## Validation

- [ ] Two consecutive deploys produce comparable per-route numbers with no
      button pressed and no browsing
- [ ] A SHA that ran on three containers reports one merged set of numbers, not
      three partial ones
- [ ] A route's timings are present for a SHA nobody browsed
- [ ] Repeating the benchmark on unchanged code produces a spread small enough
      to detect a regression worth acting on — establish that threshold from
      measurement, and state it
- [ ] History growth is bounded and the retention rule is written down

## Scope

Not chart visualization of the history — that is the other open follow-up in
[performance.md](./performance.md) and is independent of this.

Not fixing any specific slow path. The exercise-session page (3.07s, of which
`lines-for-sets` and `sets-for-sessions` are 66%) and the home overview's 149
`get-entity-by-id` calls per load both want work, and both are their own units.
This unit is about being able to *tell* whether such work helped.

Not alerting. Detection before notification.

## Context

- `src/tech/jgood/gleanmo/observability.clj` — accumulator, `aggregator-snapshot`,
  `persist-instance-snapshot!` (line 178), `instance-doc-id` (line 176),
  `wrap-request-profiling`, `profile-block`
- `src/tech/jgood/gleanmo/app.clj` — `load-performance-history` (line 276),
  `merge-pstats` (line 334), the dashboard and its one persist caller (line 384)
- `src/tech/jgood/gleanmo/schema/performance_schema.clj` — `[:xt/id :keyword]`
  and the `pstats` shape; changing the id scheme touches this
- `src/tech/jgood/gleanmo/worker.clj` — where a scheduled flush would live.
  `daily-at-utc` exists because `every-n-minutes` restarts its clock on every
  boot; for a sub-hourly flush that drift is harmless, but read the docstring
  before picking one
- [performance.md](./performance.md) — the original monitoring unit; this
  splits out its "cross-instance aggregation" follow-up
- [timer-running-flag.md](./timer-running-flag.md) — the 2026-08-13 baseline
  table, and the manual before/after that motivated this

## Open Questions (draft only)

- **Document identity.** One document per SHA, with instances merging into it,
  makes the comparison query trivial but means concurrent containers write the
  same id — last-writer-wins would silently drop a container's data. One
  document per (SHA, instance) keeps writes independent and merges at read time,
  which is safer and slower. The second is probably right; confirm before
  building.
- **Where the benchmark runs.** In-process (a worker task calling handlers
  directly) needs no auth and no network, but skips the real HTTP path and
  measures a server calling itself. External (CI, or a small scheduled job)
  measures what a user would feel and needs a credential and a target URL.
- **Cold versus warm.** Given the 41× spread, a benchmark that includes
  first-call costs measures container age as much as code. Discard the first N
  iterations, or record cold and warm separately and compare like with like —
  the second is more honest and more work.
- **Retention.** A 60-second flush is 1,440 document versions per day per
  instance. XTDB keeps every version forever. Decide the flush interval and the
  retention or downsampling rule together, not separately.
- **What "regression" means.** Means are noisy; mins are the warm-path cost and
  much steadier. A useful comparison probably keys on min and p50 rather than
  mean — but that needs checking against real data before it goes in the UI.

## Notes

Requested 2026-08-13, during the timer-running-flag deploy. Capturing that
baseline required pressing a button at the right moment, knowing that the
accumulator dies with the container, knowing that the new container would file
its numbers under a different document, and hand-copying a table into a roadmap
file so the two could be compared at all. Every one of those steps is a thing
this unit should make unnecessary.
