---
title: "Timer Running Flag"
status: ready
description: "Replace the two-full-scan set difference behind active timers with an indexed flag derived at write time, reconciled daily"
created: 2026-08-03
updated: 2026-08-03
tags: [performance, xtdb, timers, schema]
priority: medium
---

# Timer Running Flag

## Intent

Finding the running timers is the last full-history scan left on the hot path.
It runs once per timer-enabled entity type, on both the home page and
`/app/timers`, and its cost grows forever while the answer is almost always
"none" or "one".

`active-timers-for-user` computes "has a beginning, has no end" as a set
difference of two index scans:

- **A** — every session id with a `beginning` ≈ every session you have ever had
- **B** — every session id with an `end` ≈ all of them but the running ones
- running = A − B

To find 0-2 running timers it reads your entire history for the type, twice.
2,563 exercise-sessions came over from Airtable alone, and that number only
goes up.

The reason it is shaped that way is not an oversight: **XTDB indexes presence,
not absence.** Triples are indexed by (attribute, value, entity) and
(entity, attribute, value); both answer "which entities *have* X." Nothing
enumerates "entities *lacking* X" — that is a set complement, and complements
are computed, not indexed. `(not [?e :exercise-session/end])` is worse still,
because XTDB 1.x re-evaluates each `not` clause as a per-row subquery (see
[dashboard-performance.md](./dashboard-performance.md), "Not-Clause
Discovery").

A stored `running` attribute turns the question into one XTDB *can* index.

## Specification

### 1. Schema

Add to each timer-enabled entity, optional so existing documents stay valid:

```clojure
[:exercise-session/running {:optional true} :boolean]
```

Named per the boolean convention (past-tense/state word, no `?` suffix).
Present-and-true only while running; **dissoc'd**, not set false, when
stopped — a sparse attribute is what keeps the index lookup proportional to
the answer rather than to history.

### 2. Derive it in the mutation layer, not in handlers

This is the part that makes it safe, and the part to get right.

The naive version sets the flag in `start-timer` and clears it in
`stop-timer`, which turns a property that could not lie into one that must be
remembered at every call site. The paths that would eventually diverge are
enumerable and unpleasant: `resume-set!` (clears `end`), **the generic CRUD
edit form** (a user clearing the end field never goes near the timer
handlers), migrations, imports, REPL writes, and whatever timer feature gets
added in 2027 by someone who does not know the invariant exists.

Instead, `db/mutations.clj` recomputes the flag from the document being
written, on every write, for any timer-enabled type:

```
running = (and (some? beginning) (nil? end))
```

`mutations.clj` is already the mandatory chokepoint — AGENTS.md forbids
`xt/submit-tx` anywhere else — so every path inherits the invariant, including
the CRUD form. The flag stops being *maintained* state and goes back to being
*derived* state: derived at write time instead of read time. One derivation
site, no call-site discipline.

Interval field names come from `schema-utils/ensure-interval-fields`, the same
helper `timer/routes.clj` already uses, so this stays schema-driven rather than
a hardcoded list of types.

### 3. Read path

`active-timers-for-user` becomes a single selective lookup:

```clojure
{:find  '[?e]
 :where [['?e :user/id 'user-id]
         ['?e running-key true]]
 :in    '[user-id]}
```

Then pull the handful of candidates and **confirm `end` is nil on the pulled
documents** before returning them. That check is free at this size and makes
the read self-healing in the phantom direction: a document flagged running
that actually has an end gets filtered out, logged, and its flag cleared.

### 4. Backfill

One-time migration setting the flag on currently-running timers. The old
set-difference query is exactly right for this — it is only unacceptable on
the hot path, not as a one-off.

### 5. Daily reconciliation job

The read path self-heals phantoms (flagged but ended). It cannot see the
opposite — no end, no flag — because such a document is invisible to a query
that filters on the flag. That is what the sweep is for.

A task in `worker.clj`'s `:tasks` (Biff's chime integration is already wired
up via `biff/use-chime` in `gleanmo.clj:88`), running **once a day**:

1. Run the old set-difference query per timer type — the honest answer.
2. Diff against the flags.
3. Fix both directions and `log/warn` each discrepancy with the entity id,
   type, and which direction it was.

The expensive query never goes away; it moves off the request path. Once a day
versus several times per page load is the entire point. A quiet log is also the
signal that the derivation is holding — if it starts printing, some write path
is bypassing `mutations.clj`.

Note: `worker.clj` currently has one task (`print-usage`, every 5 minutes), and
`every-n-minutes` is the only schedule helper. A daily schedule wants either
`(every-n-minutes (* 60 24))` or a proper daily chime sequence.

## Validation

- [ ] Unit: starting a timer writes `running true`; stopping dissocs it
- [ ] Unit: `resume-set!` restores the flag
- [ ] Unit: clearing `end` through `update-entity!` (the CRUD-form path,
      not a timer handler) sets the flag — the regression this design exists
      to prevent
- [ ] Unit: `active-timers-for-user` returns the same set as the old
      set-difference query across a fixture with running, stopped, deleted,
      sensitive and archived timers
- [ ] Unit: a document flagged running but carrying an `end` is filtered out
      of the read and its flag cleared
- [ ] Unit: the reconciliation job repairs both directions and logs each
- [ ] Migration is idempotent — re-running changes nothing
- [ ] `just e2e-test timer-start` / `timer-stop` / `timer-overlap` /
      `timer-double-submit` / `timers-workspace` / `workout` all pass
- [ ] Prod: `active-timers-for-user` mean drops from the ~300-900ms range;
      confirm on `/app/monitoring/performance`

## Scope

Not included: changing how timers are started, stopped, or displayed. Not
included: the `running-timers-for-parent` double-submit guard, which is already
parent-scoped and cheap — though it can adopt the flag afterwards for free.

Deliberately not attempted: solving this with a time window. Scanning "started
in the last 30 days" is cheap, but proving *nothing* is running requires ruling
out every older still-open timer, and "nothing is running" is the common case —
so the fallback would fire on nearly every page load and the window would buy
nothing.

## Context

- `db/queries.clj` — `active-timers-for-user`, `running-timers-for-parent`,
  `recent-completed-timer-logs`
- `db/mutations.clj` — where the derivation belongs
- `timer/routes.clj` — `timer-config`, `infer-primary-rel`, the interval-field
  helpers this should reuse
- `schema/exercise_schema.clj` and the other timer-enabled schemas
- [dashboard-performance.md](./dashboard-performance.md) — "Next lever", and
  the measurements that led here

## Notes

The 2026-08-03 pass already took the cheap half of this: the *ended* side of
the set difference dropped its `:user/id` and `::sm/type` clauses, since it is
subtracted from an already user-scoped set and foreign ids can only fail to
match. Roughly 2x on that scan (139ms vs 283ms over 40k rows). The beginning
side is what remains, and only the flag fixes it.
