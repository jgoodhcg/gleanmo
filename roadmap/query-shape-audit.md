---
title: "Query Shape Audit"
status: ready
description: "Find clauses that cost without narrowing, and ranges that never prune, across db/queries.clj"
created: 2026-08-04
updated: 2026-08-04
tags: [performance, xtdb, audit]
priority: low
---

# Query Shape Audit

## Intent

The dashboard work turned up two query-shape defects that are almost certainly
not unique to the queries it touched:

1. **A redundant `::sm/type` clause.** `[?e ::sm/type :habit-log]` beside
   `[?e :habit-log/timestamp ?t]` narrows nothing — only habit-logs carry that
   attribute — but still costs a stream to advance on every result row.
2. **A range predicate that never prunes.** `[(>= ?t since)]` looks like it
   bounds the scan and does not, whenever `?e` sorts before `?t` in the join
   order.

Both are invisible by reading. Both are now cheap to detect (see Method).
The point of this unit is one sweep, not a standing optimization habit.

**Sequencing: do this after [timer-running-flag.md](./timer-running-flag.md).**
That unit deletes `all-ids-with-attribute` and rewrites three timer queries, so
auditing them first would be auditing code about to change.

## Method

XTDB 1.x will tell you what it did. Enable the query logger:

```
-Dorg.slf4j.simpleLogger.log.xtdb.query=debug
```

Three lines matter per query:

- `:triple-joins-var->cardinality` — the estimates the plan is built from.
- `:triple-clause-var-order` — the variable join order. **If a range or sort
  variable appears after `?e`, its range will not prune.**
- `:join-order :ave|:aev` per clause — `:ave` seeks by value and yields
  entities, so a range on that value prunes at the index. `:aev` looks the
  value up per entity, so a range on it is a post-filter over everything `?e`
  already enumerated.

`(xt/attribute-stats node)` gives the document count carrying each attribute —
raw selectivity for any `[?e :attr <literal>]` clause.

Worked example, same query with and without the user/type clauses:

```
A: user + type + range   :triple-clause-var-order [cutoff u :habit-log ?e ?t]
                         :join-order :aev ?e ?t     <- range post-filters
B: range only            :triple-clause-var-order [cutoff ?t ?e]
                         :join-order :ave ?t ?e     <- range seeks
```

Same 240 rows out; A walks 6,000 entities to get there. The irony is worth
holding onto: the extra bound clauses *lower* `?e`'s estimated cardinality,
which promotes `?e` in the join order, which is exactly what demotes the range.

## Specification

Walk every query in `db/queries.clj` and record, per query:

1. Does it carry `::sm/type` beside an attribute that already implies the type?
   Drop it — free, with no multi-user consequence.
2. Does it carry a range or sort clause whose variable lands after `?e`? Note
   whether the range is load-bearing or decorative.
3. Is anything in the query genuinely selective? If nothing is, cost tracks
   result size times clause count, and the query wants a selective attribute
   rather than a rearrangement.

Fix category 1 everywhere it appears. For category 2, fix only where the query
is on a hot path — a range that fails to prune on a rarely-hit admin page is a
finding, not a defect worth churn.

**Constraint that governs every fix:** never drop `:user/id` from a query whose
rows reach a user. It is only ever safe where the result is subtracted from an
already user-scoped set, and that trade is being retired rather than extended
(see Notes).

## Validation

- [ ] Every query in `db/queries.clj` has a recorded verdict against the three
      questions
- [ ] Category 1 removals land with no behaviour change; full suite green
- [ ] Each category 2 fix carries a before/after measurement, not an assumption
- [ ] `just validate` and the e2e suite pass
- [ ] Findings appended to [dashboard-performance.md](./dashboard-performance.md)

## Scope

Not included: adding attributes to make queries faster. That is a modeling
decision per case (see the Notes in timer-running-flag.md for when it is
justified), not something an audit should trigger.

Not included: a standing rule that all queries get profiled. This is one sweep.

## Context

- `db/queries.clj` — the surface being audited
- `roadmap/dashboard-performance.md` — the measurements that motivated this
- `roadmap/timer-running-flag.md` — do that first
- `app.clj` `/app/monitoring/db` — existing `scan-diagnostics` page; a natural
  home if any of this is worth surfacing in-app

## Notes

**Two single-user trades are outstanding and should be closed, not spread.**

`all-ids-with-attribute` drops `:user/id` and `::sm/type` from the ended-timer
scans. It is safe only because the result is a subtrahend, and it scales with
*all* users' rows. The timer-running-flag unit deletes two of its three uses;
the third (`recent-completed-timer-logs`) should get its clauses back as part
of this audit.

`build-windowed-scan-query` binds `:user/id` as an output variable and filters
in Clojure. Correct, and roughly 15x on a single user, but it scans every
user's rows inside the window. It is the one place where the answer is not a
rearrangement: XTDB 1.x cannot express a compound (user, time) index, and
faking one with a composite attribute was considered and rejected — it puts an
engine workaround into the data model, the one layer a database swap cannot
follow. Leave it, record it here, and re-measure if Gleanmo gains real users.
