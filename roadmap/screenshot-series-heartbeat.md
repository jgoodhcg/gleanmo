---
title: "Screenshot Series in CI"
status: ready
description: "Capture the visual timeline in CI against a fixed fixture, on push to main, published to an orphan branch"
created: 2026-08-03
updated: 2026-08-03
tags: [tooling, e2e, screenshots, ci]
priority: low
---

# Screenshot Series in CI

## Intent

`just e2e-shot-series` produces one comparable frame set per tick, and
AGENTS.md instructs agents to run it at the start of UI work and again before
committing UI changes. In practice the archive looks like this:

```
2026-07-29T14-45-40Z
2026-07-29T15-02-47Z
2026-07-29T17-47-15Z
2026-07-29T20-05-17Z
2026-08-03T12-12-02Z
```

Four ticks in one afternoon — the session where the tooling was built — then a
five-day gap, then one because it was asked for by hand. A timelapse needs a
heartbeat, and instructions in a policy file are not one.

**The artifact wanted is the UI changing over time — not real history.** That
single decision settles most of the design. The series does not need the
production database, does not need the dev laptop, and does not need to run at
commit time. It needs a fixture good enough to express each surface, and a
trigger that fires without anyone remembering. CI has both.

It also makes the frames *better*, not merely cheaper. Against fixed data,
frames differ only when the UI differs. Against real history, every frame also
changes because more life got logged — noise, when the question is how the
design evolved.

## Specification

### 1. A fixture that expresses the UI

`resources/fixtures.edn` (163 lines: one user, a few locations, a handful of
logs) is loaded by `dev/repl.clj:24`. It is nowhere near enough — CI's database
starts empty, and a series shot against it would be empty charts and "no items"
lists, which records nothing.

The bar is **every manifest surface renders with content**: lists with enough
rows to show layout, charts with enough points to have shape, heatmaps with
enough spread to look like a year, a timeline with several distinct entity
types on several distinct days.

Values stay fixed. Determinism is the point — a frame that differs means the UI
differed. If hand-writing the volume gets tedious, a generator with a pinned RNG
seed keeps determinism while making volume cheap; either way the output must be
identical run to run.

Dates need care: anything anchored to "now" makes frames differ every day for no
reason. Either pin the fixture to fixed absolute dates and accept that the
"today" surfaces look empty, or generate dates relative to the run date so the
today/timeline surfaces stay populated. The second is more useful and is the
recommendation, but it means those particular frames legitimately change daily —
worth deciding deliberately rather than discovering later.

### 2. A seed path CI and local share

A `just seed` recipe (or a flag on the dev task) that loads the fixture the same
way in both places, so a frame can be reproduced locally when one looks wrong.

### 3. A `screenshots` workflow job

Gated to **pushes on `main`** — effectively per release, which is the right
cadence and keeps volume manageable. Feature-branch pushes produce nothing.

It reuses what the existing `e2e` job already sets up (`validate.yml:101-175`):
JDK, Clojure, Node, pinned Tailwind v3, generated CSS, ephemeral secrets, a real
dev server on an ephemeral database, Playwright. The added steps are: seed, run
`npm run shot:series`, publish.

### 4. Publish to an orphan `screenshots` branch

An orphan branch has its own root commit, so it never merges into `main`'s
history and a normal clone never pays for it — Actions' checkout is
single-branch by default. No PR to open, nothing to merge, and no CI loop
because no workflow watches that branch.

Rejected alternatives: workflow artifacts (expire at 90 days, not browsable as
a timeline); a PR per push (noise someone has to merge, and it writes binaries
into main history permanently); committing to `main`/`dev` directly (same bloat,
plus loop protection needed).

`gh-pages` instead of `screenshots` is the same mechanism and yields a browsable
gallery for free — worth taking if a gallery is wanted.

### 5. Thinning

62 frames per tick at roughly 100KB is about 6MB per tick. PNGs do not
delta-compress and git history is permanent, so a few pushes a week for a year
is a few hundred MB forever. Keep every tick for 30 days, then thin to one per
week.

## Validation

- [ ] Push to a feature branch — no series job runs
- [ ] Push to `main` — one tick lands on the `screenshots` branch
- [ ] Frames show populated lists, charts and heatmaps, not empty states
- [ ] Two runs at the same commit produce byte-identical frames, except any
      deliberately date-relative surfaces
- [ ] `git clone` of `main` does not fetch the screenshots branch
- [ ] Consecutive ticks are comparable — same routes, same viewports, same
      fixture — so they line up as a timelapse
- [ ] `just seed` locally reproduces a CI frame

## Scope

Not included: a series against real personal history. Explicitly out of scope —
the artifact wanted is UI change, and real data adds noise, a laptop
dependency, and a durability problem for no benefit here.

Not included: git hooks. Considered and dropped once the CI route was chosen —
a `post-commit` hook was only ever a way to get a heartbeat off the dev
machine, and CI does that better and without a dev server to detect, a rate
limit to tune, or `core.hooksPath` to install.

Not included: changing what a tick captures, the manifest, or the
`SCREENSHOT_PHASE` before/after workflow, which serves a different purpose
(per-change changelog art, not progression). Not included: building the
timelapse renderer.

## Context

- `justfile:55` — `e2e-shot-series`
- `e2e/scripts/manifest.ts` — canonical route list; comparability depends on it
  changing only alongside navigation changes
- `.github/workflows/validate.yml:101` — the e2e job whose setup this reuses
- `resources/fixtures.edn`, `dev/repl.clj:24` — the fixture and its loader
- `.gitignore:34,37` — why local captures are not durable today
- AGENTS.md "Visual timeline (series capture)" — the instructions this is meant
  to make unnecessary; update once CI owns the heartbeat

## Open Questions (draft only)

1. **Fixed or relative dates in the fixture?** See §1 — determines whether the
   today/timeline surfaces are populated or empty, and whether some frames
   legitimately change daily.
2. **Does the fixture drift from reality?** A design series shot against a
   fixture that stops resembling real usage stops being informative. Wants
   either a periodic review or a rule that new entity types land in the
   fixture as part of the new-entity checklist.
3. **Keep the local capture at all?** `just e2e-shot-series` stays useful for
   ad-hoc before/after work. The question is only whether AGENTS.md should
   still ask for it once CI has the heartbeat — probably not, which removes a
   standing instruction that is not being followed anyway.
