---
title: "App-Level Screenshot Runner"
status: ready
description: "Authenticated screenshot capture for docs, CI, and the visual timeline"
tags: ["e2e", "tooling", "docs"]
priority: medium
created: 2026-02-02
updated: 2026-07-29
---

# App-Level Screenshot Runner

## Intent

Capture authenticated screenshots of essentially every UI route straight from a
live dev instance, in a way that is **directly comparable capture-to-capture**,
so the frames compose into a clean timelapse / progression series of the app.
One command produces one "tick" of the visual timeline. This supersedes the
ad-hoc, per-change `SCREENSHOT_PHASE` pairs (still used for changelog art) with
a regular, manifest-driven heartbeat.

## Specification

A tick captures **every route in the canonical manifest** at mobile + desktop,
writing all frames into one timestamped directory with a metadata sidecar:

```
e2e/screenshots/series/<ISO-timestamp>/
  home-mobile.png
  home-desktop.png
  viz-habit-log-mobile.png
  …
  metadata.json
```

`metadata.json` records, per tick: ISO timestamp, git SHA, branch, base URL,
auth email, viewport specs, and a per-route `{ slug, group, viewport, status }`
result list. The runner exits non-zero if any route fails, so it can gate
future automation.

**Built (this work unit):**
- `e2e/scripts/manifest.ts` — canonical route manifest. Single source of truth
  for which frames make up a tick. Curated to cover every distinct surface
  type (surfaces, dashboards, viz charts, stats, flows, forms, lists) rather
  than every CRUD entity. Declares per-route `waitForSelector` / `settleMs`
  for async content (ECharts, HTMX fragments) so frames don't flicker.
- `e2e/scripts/shot-manifest.ts` — runner. One authenticated context per
  viewport, walks the manifest, writes timestamped dir + metadata.
- `just e2e-shot-series` recipe + `npm run shot:series` script.
- `AGENTS.md` policy: agents run a tick at the **start of any UI-touching
  session** (baseline) and **again before committing UI changes**.
- Viewports match `shot-pages.ts` (mobile 390×844, desktop 1280×900) so series
  frames line up with the existing per-change capture tool.

**Remaining (follow-up work units, not blocking):**
- **Biff task glue** (`clj -M:dev screenshots`): mint a dev session cookie
  without going through `/auth/e2e-login`, so the runner works from a fresh
  shell with no Playwright auth round-trip. See "Proposed architecture" below.
- **CI archive policy**: today `e2e/screenshots/` is gitignored and CI uploads
  artifacts only `if: failure()` with 7-day retention — the opposite of an
  archive. Decide storage (git-LFS-tracked dir at repo root, or external
  object storage) and flip CI to archive green-run ticks.
- **Route manifest drift helper** (`clj -M:dev suggest-screenshot-routes`):
  walk the router and report manifest entries missing from `manifest.ts`.

## Validation

- [x] `just e2e-shot-series` runs against a dev server and writes a
      timestamped dir with one frame per (route × viewport) + `metadata.json`.
- [x] Runner exits non-zero on any HTTP ≥ 400 or navigation failure, with the
      failed route recorded in `metadata.json`.
- [x] `manifest.ts` compiles under `tsx` and covers every surface type.
- [ ] Manual: two consecutive ticks produce visually identical frames for
      unchanged routes (the comparability invariant that makes a timelapse).
- [ ] CI: green-run ticks archived (blocked on storage decision above).

## Scope

**In scope:** the manifest, the runner, the `just` recipe, and the agent
policy. Local-first; no commits of binary frames (output is gitignored by
default via `e2e/.gitignore`).

**Out of scope (deferred):** the Biff task entry point, the
`/_dev/session-cookie` endpoint, CI artifact archival, visual-diff regression,
Git-LFS wiring. Each is a discrete follow-up.

## Context

- Prior art: `e2e/scripts/screenshot.ts` (single-route one-off) and
  `e2e/scripts/shot-pages.ts` (per-change page-shell pairs). Both keep working;
  the series runner is the new timelapse capability, the others stay for
  narrow / per-change work.
- Auth: `e2e/scripts/auth.ts` → dev-only `/auth/e2e-login` (never in prod
  builds). Series uses `E2E_EMAIL` (default `e2e-series@localhost`) so the
  timelapse renders a stable, dedicated user rather than test-flow throwaways.
- Related: `screenshots.md` (the higher-level "visual changelog" intent) and
  the "Visual timeline (series capture)" subsection of `AGENTS.md`.
- `manifest.ts` is TypeScript, not the EDN file originally proposed below —
  chosen because the runner is TS-first and reads it at import time with no
  parser. When the Biff task lands, the Clojure side can read the same routes
  via cheshire if we export a JSON copy, or we promote to EDN then.

## Proposed architecture (Biff task follow-up)

1. **Biff task entry point** (`clj -M:dev screenshots`):
   - Fetches a valid session cookie for a designated "docs" account.
   - Writes cookie + metadata into `tmp/playwright-auth.json`.
   - Shells out to `npm run shot:series` with `PLAYWRIGHT_COOKIE_FILE` set.
   - Exits non-zero if the runner reports any navigation failure.
2. **Dev-only session endpoint** — `POST /_dev/session-cookie` guarded by
   `(biff.config/dev?)`. Payload `{:email "docs@example.com"}`. Looks
   up/creates the user, issues a session via Biff's auth helpers, returns the
   `uid` cookie. Replaces the `/auth/e2e-login` round-trip for headless runs.
3. **Playwright harness** — `global-setup.ts` reads `PLAYWRIGHT_COOKIE_FILE`
   and calls `context.addCookies`. The existing `shot-manifest.ts` already
   does the route walk; this just swaps the auth mechanism.

## Maintenance

1. Treat `manifest.ts` like code: PRs update it whenever navigation changes, so
   every tick stays comparable. Adding/removing a route there is the trigger
   for "this surface is now / no longer part of the visual timeline."
2. Prefer a representative sample of forms/lists over exhaustive duplication —
   25 near-identical CRUD list pages add noise, not signal.
3. For chart-heavy routes, prefer a `settleMs` (or `waitForSelector` on a
   stable element) over relying on `networkidle` alone; ECharts animations can
   otherwise settle differently frame-to-frame.

## Notes

- Comparability is the whole point: same routes, same viewports, same wait
  conditions every tick. If a route needs different treatment (e.g. a login
  wall, a feature flag), encode it in the manifest rather than special-casing
  it at capture time.
- Storage open question (resolved as "decide later, keep local-first"): the
  series dir defaults under the gitignored `e2e/screenshots/`, so capturing a
  tick never surprises the user with a binary commit. When a storage target is
  chosen, point `SCREENSHOT_SERIES_DIR` at it and/or add a CI upload step.
