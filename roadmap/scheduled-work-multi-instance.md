---
title: "Scheduled Work Under Multiple Instances"
status: draft
description: "Scheduled tasks, tx listeners and queues all run per-container with no singleton guarantee — make them safe before the app ever scales past one instance"
created: 2026-08-13
updated: 2026-08-13
tags: [infrastructure, observability, correctness]
priority: low
---

# Scheduled Work Under Multiple Instances

## Intent

Biff schedules tasks at system start, which means **per container**. There is no
leader election, no lock, and nothing that makes a scheduled task run once
across a fleet. The same is true of `:on-tx` listeners: every container gets the
full transaction feed and reacts independently.

Today that costs nothing — the `gleanmo` App Platform component runs **1
container** (1 GB RAM, 1 shared vCPU), and every other project on the account is
kept at 1 as well. This unit exists so that scaling to 2 is a resource decision
rather than a correctness incident, because nothing in the code will complain
when it happens.

**It is also not purely hypothetical at 1 container.** App Platform deploys are
zero-downtime by default, which means the replacement container starts before
the old one stops. During that window two containers are alive, both with
schedulers running. A deploy that lands a few minutes before 09:00 UTC could
have two copies of `reconcile-timer-flags` fire. Confirm the exact overlap
behaviour before relying on either reading.

## Specification

Every piece of background work is either safe to run N times concurrently, or
guarded so it runs once. Which of the two is a per-task decision, made
deliberately and written down next to the task.

The full surface is small and currently lives entirely in `worker.clj` — it is
the only module contributing `:tasks`, `:on-tx`, or `:queues`.

### `reconcile-timer-flags` — daily at 09:00 UTC

The costly one. Each container would run the full two-scan set difference
concurrently — the exact query [timer-running-flag.md](./timer-running-flag.md)
exists to keep off the hot path — then submit the same repair transactions from
its own snapshot.

The *effect* is idempotent, so this is not a corruption risk. The costs are: N
simultaneous full-history scans against shared Postgres in one burst, N document
versions per repaired document, N copies of every `Reconciling ...` warning, and
a benign write race where the second container's snapshot predates the first's
repair and it rewrites the same value.

### `alert-new-user` — `:on-tx`

The latent bug. Every container processes every transaction, and this one
currently only calls `log/info`. The code comment directly beside it reads *"You
could send this as an email instead of printing."* The day someone takes that
suggestion, N containers means N emails per new user, and it will not be
obvious why.

### `print-usage` — every 5 minutes

Harmless duplication, with one wrinkle: it is currently the only continuous
heartbeat proving the chime scheduler is alive at all, which makes it the
fallback diagnostic when a daily task appears not to have run. N-fold
duplication makes that signal ambiguous rather than useless.

### Queues

`:echo` with `echo-consumer` is a placeholder, but the question it raises is
real and unanswered: whether Biff's queue is in-memory per instance. If it is, a
job submitted on container A is invisible to B, and anything in flight dies with
a container during a deploy. Worth settling before any real work is queued.

### Metrics

Already covered by
[performance-regression-tracking.md](./performance-regression-tracking.md) —
each container accumulates its own Tufte data and the dashboard shows whichever
one served the request. Same root cause, tracked separately because the fix is
different.

## Validation

- [ ] With 2 containers running, a scheduled task's *effect* occurs once —
      verified by document version count, not just by log inspection
- [ ] A `:on-tx` side effect fires once per transaction, not once per container
- [ ] Logs identify which container performed a given piece of scheduled work
- [ ] The guard holds across a rolling deploy, when old and new containers
      overlap — this is the case that will actually happen first
- [ ] Scaling to 2 instances and back requires no code change

## Scope

Not a decision to scale. The app is comfortable at one container and there is no
load argument for more; this is about the correctness of *if*.

Not the metrics fragmentation — same cause, different fix, separate unit.

Not general distributed-systems hardening. The surface is four items in one
namespace, and it should stay that size.

## Context

- `src/tech/jgood/gleanmo/worker.clj` — the entire surface: `:tasks`
  (`print-usage`, `reconcile-timer-flags`), `:on-tx` (`alert-new-user`),
  `:queues` (`:echo`)
- `src/tech/jgood/gleanmo.clj:21-41` — module registration; `worker/module` is
  the only contributor of background work
- `daily-at-utc` in `worker.clj` already documents one scheduling foot-gun
  (`every-n-minutes` restarting its clock on every boot, so a once-a-day task
  can silently never run on a day with several deploys). This unit is the
  multi-container sibling of that problem
- [performance-regression-tracking.md](./performance-regression-tracking.md) —
  the metrics half
- [infrastructure.md](./infrastructure.md) — the Neon → DigitalOcean move;
  whatever Postgres ends up underneath is what N concurrent sweeps would hit

## Open Questions (draft only)

- **Which mechanism.** A lease document in XTDB with compare-and-set
  (`::xt/match`) and an expiry is the self-contained option and needs no new
  infrastructure. Weigh it against simply making each task safe to run N times,
  which is less machinery but has to be re-argued for every future task.
- **Or move the work out of the web container entirely.** App Platform has
  Worker and Job component types; a worker component pinned at 1 instance
  separates "how many web containers serve traffic" from "how many schedulers
  exist," and removes the question rather than answering it. Costs another
  component. This is probably the idiomatic answer and should be priced.
- **Rolling-deploy overlap.** Confirm whether App Platform genuinely runs old
  and new containers concurrently, and for how long. If it does, the "we only
  run one container" defence is already incomplete and this unit's priority
  should rise.
- **Queue semantics.** In-memory per instance, or backed by XTDB? Determines
  whether queues belong in this unit at all.

## Notes

Raised 2026-08-13, while verifying the timer-running-flag deploy. The trigger
was noticing that `reconcile-timer-flags` logs nothing when it finds nothing —
so the plan to "check the logs are quiet" could not distinguish a clean sweep
from a sweep that never ran. Following that thread led to the observation that a
second container would not make the logs *quieter*, it would make them
ambiguous in the other direction.
