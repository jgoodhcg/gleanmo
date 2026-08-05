---
title: "Timer Running Flag"
status: active
description: "Replace the two-full-scan set difference behind active timers with an indexed flag derived at write time, reconciled daily"
created: 2026-08-03
updated: 2026-08-04
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

**Opt-in is the schema itself.** Rather than `mutations.clj` importing a list
of timer types from the app layer, it maintains the flag for exactly those
entities whose schema declares a `<entity>/running` field alongside
`beginning`/`end`. Adding the attribute to a schema is what turns the
derivation on. Interval field names come from
`schema-utils/ensure-interval-fields`, the helper `timer/routes.clj` already
uses.

`exercise-set` is deliberately **not** on the list. The workout screen finds
its running set through `sets-for-session`, which is equality-bound on the
parent and already cheap — it never calls `active-timers-for-user`, so a flag
would buy nothing and add a fourth schema to keep in sync.

#### The trap: `update-entity!` is a partial merge

`:db/op :update` merges `data` into the stored document, so **the flag cannot
be derived from `data` alone.** If `data` sets only `beginning`, whether the
result is running depends on an `end` that is not in `data`. If it clears
`end`, whether the result is running depends on a `beginning` that is not in
`data` either.

So the derivation has to see the merged document, which means reading the
current one when `data` touches an interval field. `xt/entity` inside
`mutations.clj` is permitted by the db-layer rule, and it is a single
by-id lookup on a document the cache almost certainly holds. Getting this wrong
is silent: it would leave the flag stale exactly on the CRUD-form path this
design exists to protect, and every test that only exercises start/stop would
still pass. Cover the partial-update case explicitly.

`create-entity!` and `create-entities!` have no such problem — `data` is the
whole document.

### 3. Read path

`active-timers-for-user` becomes a single selective lookup:

```clojure
{:find  '[?e]
 :where [['?e :user/id 'user-id]
         ['?e running-key true]]
 :in    '[user-id]}
```

**Measured before building (in-memory node, 40k exercise-sessions — 30k this
user's, 10k another's, 2 running):**

| Shape | Time |
|---|---|
| Set difference, two full scans (current) | 314.63 ms |
| Flag, `:user/id` **and** `::sm/type` both kept | **0.18 ms** |
| Flag, `:user/id` only (type implied by the attribute) | 0.15 ms |

About 1,700x, with user scoping fully intact. This is the important result:
because the flag clause is genuinely selective, XTDB's n-ary join intersects
the 2-element stream against the others and the scoping clauses cost nothing
measurable. There is no single-user-versus-multi-user trade to make here —
unlike the ended-scan change this supersedes. Keep both clauses.

Then pull the handful of candidates and **confirm the interval on the pulled
documents** — beginning present, end nil — before returning them. Free at this
size, and it means a stale flag can never surface a stopped timer as running.

**The read filters and logs; it does not repair.** An earlier draft of this
unit had it clearing the flag inline. That is wrong twice over:
`active-timers-for-user` takes `db`, not `ctx`, so it cannot write at all, and
a GET that writes would be reading its own pre-write snapshot for the rest of
the request (see the `:biff/db` anti-pattern in AGENTS.md). A `log/warn` here
and repair in the daily job keeps the read path a read path — and the log is
the useful half anyway, because a phantom means some write path skipped the
derivation.

### 4. Backfill and deploy order

One-time migration (`m007`) setting the flag on currently-running timers. The
old set-difference query is exactly right for this — it is only unacceptable on
the hot path, not as a one-off. Idempotent, per the m003–m006 pattern; the
RocksDB lock means the dev server must be stopped to run it locally.

**Deploy order — decided 2026-08-04: single deploy.** Stop every running timer
first, then ship schema, derivation, read path and reconciliation together, and
run the backfill after.

The consequence to be awake to: between the deploy and the backfill, any timer
that *was* running is invisible in the UI, because the new read filters on a
flag no existing document carries. Stopping timers beforehand is what makes
that window empty rather than alarming. It is recoverable either way — the data
is untouched and the backfill restores the view — but it is a real window, and
it is the reason the staged alternative existed.

That also means this can ship as **one commit**. The staged alternative would
have split it on the deploy boundary.

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

- [x] Unit: starting a timer writes `running true`; stopping dissocs it
- [x] Unit: ~~`resume-set!` restores the flag~~ — corrected during the build.
      `resume-set!` reopens an **exercise-set**, which §2 deliberately keeps
      off the list, so there is no flag for it to restore. The check that
      matters is the one below it (clearing `end` through `update-entity!`,
      which is the same `{end :db/dissoc}` shape), plus an explicit assertion
      that the exercise-set path stays flagless
- [x] Unit: clearing `end` through `update-entity!` (the CRUD-form path,
      not a timer handler) sets the flag — the regression this design exists
      to prevent
- [x] Unit: **a partial update that touches only `beginning`** lands the right
      flag, i.e. the derivation read the stored `end` rather than assuming
      `data` was the whole document. The trap above; start/stop tests alone
      pass without it
- [x] Unit: an update touching neither interval field leaves the flag alone
- [x] Unit: `active-timers-for-user` returns the same set as the old
      set-difference query across a fixture with running, stopped, deleted,
      sensitive and archived timers
- [x] Unit: a document flagged running but carrying an `end` is filtered out
      of the read and logged — and the read performs no write
- [x] Unit: the reconciliation job repairs both directions and logs each
- [x] Unit: an entity whose schema declares no `running` field is untouched by
      the derivation
- [x] Migration is idempotent — re-running changes nothing. It repairs exactly
      what `running-flag-audit` reports, so the second run has nothing to do;
      covered by `reconcile-timer-flags-idempotent-test`, which asserts no tx
      is submitted on a second sweep
- [x] `just e2e-test timer-start` / `timer-stop` / `timer-overlap` /
      `timer-double-submit` / `timers-workspace` / `workout` all pass —
      and beyond the named six, `just e2e-test-all` is green end to end:
      all 20 scripts CI runs, in one pass
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

## Build notes (2026-08-04)

Built as specified. Four things worth recording:

**The flag is `:hide true`.** Not in the spec, but forced by it: every
timer schema's fields flow into the generic CRUD form and list view, so an
un-hidden `running` would render as a user-editable checkbox on derived
state — the exact call-site discipline §2 exists to abolish. `:hide` is the
established mechanism (see the deprecated-field rule in AGENTS.md).

**The derivation reads a fresh snapshot, not ctx's `:biff/db`.** `submit-tx`
routes through `submit-with-retries`, which calls `assoc-db` and therefore
*overwrites* `:biff/db` with a fresh snapshot before building the tx. So Biff
merges the update into a document possibly newer than the one the request
holds. Deriving from the older one would be the `:biff/db` anti-pattern wearing
a different hat: the flag would go stale precisely when two writes hit the same
document in one request.

**Reconciliation must not reuse `active-timers-for-user`.** §5 says to run "the
old set-difference query", and the old query applied the
deleted/sensitive/archived display filters. Reconciling against those would
make the sweep fight itself: a running timer under a sensitive parent reads as
"not running", gets its flag cleared, and disappears for good. `running-flag-audit`
is deliberately unfiltered — those flags govern what a user is *shown*, not
whether an interval is open.

**`daily-at-utc` rather than `(every-n-minutes (* 60 24))`.** The note in §5
offered both. `every-n-minutes` restarts its clock from `now` on every boot, so
a once-a-day task can silently never run on a day with a few deploys.

**All 20 e2e scripts pass in a single `just e2e-test-all` run.** Three earlier
attempts failed — `timer-double-submit` on a 30s `waitForLoadState` timeout,
`navigation` on `browserType.launch: Timeout 180000ms exceeded`, and one that
hit `ERR_CONNECTION_REFUSED` because the dev server was down. None were this
change: the first two were contention from running the suite while the machine
was busy with other work, and no failure in any run was ever a failed
assertion — only infrastructure failing to reach the app.

Chasing that did surface real latent fragility in the suite, unrelated to this
unit and worth its own work: 156 `waitForLoadState('networkidle')` calls (an
anti-pattern Playwright's own docs discourage) plus 59 bare `page.goto()` calls
that default to `waitUntil: 'load'` and therefore block on every subresource —
against pages that pull 12 external requests from five third-party hosts
(`ui.clj`), roughly a thousand public-internet round trips per suite run, in a
CI loop with no retries. `auth.ts` already documents this exact bug ("a 3ms
route time out at 30s when one asset stalled") and fixed one call site;
`runningCount`, which failed here, is one of the 59 left exposed.

Related: the CRUD edit form cannot currently clear an optional field at all —
`form->schema` *skips* blank optional values rather than emitting `:db/dissoc`,
so an omitted `end` leaves the stored one intact under `:db/op :update`. The
scenario §2 names ("a user clearing the end field") is therefore not reachable
through the form today. The derivation is still the right shape — it covers
`resume-set!`, imports, migrations, REPL writes and whatever comes next — but
the *form* path it was justified by is latent, not live. Worth a look on its
own terms: silently ignoring a cleared field is surprising in either direction.

## Notes

The 2026-08-03 pass already took the cheap half of this: the *ended* side of
the set difference dropped its `:user/id` and `::sm/type` clauses, since it is
subtracted from an already user-scoped set and foreign ids can only fail to
match. Roughly 2x on that scan (139ms vs 283ms over 40k rows). The beginning
side is what remains, and only the flag fixes it.
