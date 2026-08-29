---
title: "One navigation model: boost every form through htmx, delete the double-submit guard"
status: ready
description: "Route all 42 native form POSTs through htmx so pages stop fully reloading, replace the hand-rolled double-submit guard with hx-sync, and drop the two unused vendor scripts"
created: 2026-08-29
updated: 2026-08-29
tags: [frontend, htmx, performance, forms, navigation]
priority: high
---

# One navigation model: boost every form through htmx, delete the double-submit guard

## Intent

Three problems with one root: the front-end request path is inconsistent, and
the workarounds that inconsistency forced are now the thing to remove.

**Inconsistency.** 42 native `biff/form` POSTs across 12 files do full document
reloads; 15 explicit `hx-post` call sites swap in place. Same app, two
navigation models, and the choice between them is historical rather than
considered. Every native POST tears down the document and re-fetches four
render-blocking cross-origin scripts.

**A guard that has to guess.** `main.js:663-731` prevents double submits by
setting a flag on the form and clearing it on navigation, with an 8-second
timeout as backstop and a `pageshow` handler for bfcache restores. All three
mechanisms exist because a native form submit cannot report when it finished.
htmx can. Once every form is htmx-driven, the guard is deletable in full.

**Dead weight.** Two of the four render-blocking vendor scripts have zero call
sites. One loads from an unversioned URL, costing a redirect on every cold load
for a library nothing uses.

The payoff is one navigation model, a lighter page on every route, ~70 fewer
lines of timing heuristics, and a front end where making a path fast doesn't
require inventing a pattern first.

## Specification

### 1. Delete the two unused vendor scripts

Commented out at `ui.clj:51-52` with a pointer comment (done 2026-08-29;
delete outright once this unit lands and nothing has needed them):

- **hyperscript** (`hyperscript.org@0.9.8`) — the only reference in the entire
  codebase was its own `<script>` tag. No `_=` attributes, no `:_` hiccup keys.
- **htmx websocket extension** (`htmx.org/dist/ext/ws.js`) — no `hx-ws`,
  `ws-connect`, or `ws-send` anywhere. The URL is also **unversioned**, so it
  302s to a versioned path and cannot be long-cached.

Sortable stays — used via `ui/sortable.clj` and `task_today.clj:172`.

**Decision: no opt-in mechanism.** `ui/base` already has the pattern —
`::recaptcha` and `::echarts` context keys gate their script tags (`ui.clj:26`,
`:56-61`). If hyperscript is ever wanted it costs the same three lines echarts
costs. Building the hook now, with zero consumers, is speculative generality.

**What replaces it:** `hx-on`, an htmx attribute taking plain JavaScript with no
extra download. Already used at `calendar.clj:96` with the `"event: code"`
syntax htmx 1.9.0 supports.

### 2. Make the inline page scripts safe to re-run — PREREQUISITE

Five inline scripts exist app-wide. Two pairs are correct only when their markup
arrives via a fresh document load, and break when it is swapped:

- **`tick-script`** (`workout.clj:210`, and a **duplicate copy** at
  `boulder.clj:118`) — runs `querySelectorAll` at parse time and creates one
  `setInterval` **per element**, captured in a closure. Every swap adds a fresh
  set while the old ones keep ticking against detached nodes.
- **`form-script`** (`workout.clj:226`, and a **duplicate copy** at
  `boulder.clj:139`) — `initLineForm` has a per-form init guard, but the three
  `document.addEventListener` calls at `workout.clj:296-308` do not. Every swap
  adds another `htmx:beforeRequest` handler, so the "clear other line mounts"
  logic fires N times.

Move both into `main.js` as once-bound modules, following the pattern already
established there by Choices, Sortable, ECharts, and the filter inputs: bind
once, re-scan on `htmx:afterSettle`. `tick-script` becomes a single global
interval that re-scans `[data-epoch-ms]` rather than one interval per element.

This collapses `boulder.clj`'s duplicates into the shared modules — a
simplification worth having independent of htmx.

`main.js` loads in `<head>` and therefore does **not** re-execute on a body
swap, which is precisely why these modules belong there.

Remaining inline scripts to audit for the same hazard, not yet assessed:
`boulder.clj:628` (`problems-script`) and `overview.clj:804`
(`timeline-filter-script`).

This lands before anything below it.

### 3. Boost all navigation

Add to the wrapper div in `ui/page` (`ui.clj:64`):

```clojure
{:hx-boost "true" :hx-sync "closest form:drop"}
```

Every descendant `<a>` and `<form>` becomes an AJAX request that swaps `<body>`
and pushes the URL — all 42 native forms, no per-form edits — and it degrades to
ordinary navigation when JS is unavailable.

Both attributes inherit. `hx-sync` with a `drop` strategy is what actually
prevents a double submit: **htmx does not dedupe concurrent requests by
default.** Verify the inherited-selector semantics on 1.9.0 before relying on
this — `this` and `closest form` resolve differently when inherited, and the
wrong one gives either no protection or an app where one in-flight request
blocks every other.

### 4. Delete the double-submit guard

Once every form is boosted, `main.js:663-731` is dead code. Remove the module
entirely: `BUSY_MS`, `isHtmxDriven`, `unlock`, the delegated `submit` listener,
and the `pageshow` handler.

Replace the visual affordance with htmx's automatic `htmx-request` class, which
does the same job with no JavaScript:

```css
/* was .is-submitting */
.htmx-request { opacity: .55; cursor: wait; pointer-events: none; }
```

Delete `.is-submitting` from `tailwind.css:750`.

**Trap — `.htmx-request` will be purged unless you act.** `.is-submitting` lives
inside `@layer components` (`tailwind.css:165`), and Tailwind v3 purges unused
`@layer` rules against the content globs. It survives today only because the
string `'is-submitting'` appears in `main.js`, which
`resources/tailwind.config.js:2-8` includes for exactly this reason. Nothing in
`src/` or `resources/public/js/` ever contains the string `htmx-request` — htmx
applies it from vendor code that is not scanned. The rule would be purged and
the busy state would silently not render. Add `safelist: ['htmx-request']` to
`resources/tailwind.config.js`, and keep the `main.js` content glob: other
JS-applied classes still depend on it (`border-neon-cyan`, and the
`bg-neon-cyan` / `text-black` / `text-gray-500` toggles in `form-script`).

**Why this is safe.** Correctness never depended on the client guard —
`start-set!` (`workout.clj:1016`) refuses to open a second set while one is
running, and `timer/routes.clj:518` carries its own duplicate guard. The client
side is a UX affordance. htmx's version is also strictly better: the lock clears
when the request settles, rather than on navigation with an 8-second timeout and
a bfcache handler as backstops.

**Known carve-out.** Any form that opts out with `hx-boost="false"` loses this
protection. None exist today. If one is added (see the export note in Context),
it needs explicit handling at that point.

### 5. Translate 303s for htmx requests

Every 303 stays exactly as written. `redirect-home`, `redirect-back`, and their
equivalents elsewhere are untouched.

Add middleware at the edge: when `HX-Request: true` and the response status is
303, emit the `location` as an **`HX-Location`** header. htmx then performs a
client-side GET to that location, swaps, and pushes the URL.

Without this, htmx pushes the *request* path, so starting a set would leave
`/app/exercise/session/:id/set/start` in the URL bar.

**Rejected alternative:** having the middleware follow the redirect server-side
and return the body with `HX-Push-Url`, collapsing two round trips into one. It
works, but the middleware re-enters the router, which is more machinery and more
ways to be quietly wrong. That win is better taken per-path by having a hot
handler return its fragment directly — explicit rather than magic.

### 6. Record the new invariant in AGENTS.md

Boosting makes **"every inline script must be safe to run more than once"** a
permanent constraint, not a one-time fix. A script added later that binds a
global listener or starts an interval will leak, and it will leak quietly.

Add a rule to AGENTS.md: inline `[:script]` blocks must be idempotent, or belong
in `main.js` as a once-bound module that re-scans on `htmx:afterSettle`. Prefer
the latter. This is the standing price of boosting and needs to be written down.

### 7. What this buys, and what it does not

Boost removes the document teardown and vendor-script re-fetch from all 42
forms. It does **not** collapse the POST → 303 → GET double round trip; that
still costs two trips, exactly as today.

Hot paths graduate individually afterward: replace the boosted form with an
explicit `hx-post` + `hx-target` and return the fragment directly from the
handler. Boost is the floor, not the ceiling.

## Validation

- [ ] `grep -rn "hyperscript" src/` and `grep -rn "ext/ws.js" src/` both return nothing
- [ ] Render-blocking vendor scripts drop from four to two (htmx, Sortable); Choices and Plausible already carry `defer`
- [ ] `grep -rn "is-submitting" src/ resources/` returns nothing
- [ ] Busy state renders **in a production build** — confirms `htmx-request` survived Tailwind purge
- [ ] `boulder.clj` no longer defines its own `tick-script` / `form-script`
- [ ] Start a set, log three lines, end the session: each `[data-epoch-ms]` element ticks exactly once per second, and the line-mount clearing fires once per open
- [ ] Same check on the boulder screen, which shares the extracted modules
- [ ] Submit a form twice rapidly: the second request is dropped, the submitter shows the busy state, and the form is usable the moment the response settles — not 8 seconds later
- [ ] Trigger two *different* forms concurrently: both complete (confirms the `hx-sync` selector is not over-broad)
- [ ] URL bar after any POST-then-redirect shows the redirect target, not the POST path
- [ ] Back button after a boosted navigation restores a page with live ticking timers, not a frozen `…`
- [ ] With JS disabled, every form still submits and navigates
- [ ] AGENTS.md carries the idempotent-inline-script rule
- [ ] Existing test suite passes (see AGENTS.md for the command)
- [ ] E2E: workout, boulder, and timer flows pass unchanged

## Scope

**In:** vendor script removal, inline-script extraction and idempotency,
app-wide `hx-boost` + `hx-sync`, full deletion of the double-submit guard, the
303 → `HX-Location` middleware, and the AGENTS.md rule.

**Out:**

- **Timer instant-start.** The investigation behind this unit found that
  starting a workout set costs two round trips and ~7 DB reads to display a
  clock that needs two of them. Optimistic client-side paint and lazy-loading
  the log form and history are a follow-on this unit unblocks, not part of it.
  Findings preserved in Context so nothing is re-derived.
- **Converting individual forms to explicit `hx-post` fragment returns.** Boost
  first; graduate hot paths later, one at a time, with measurements.
- **Self-hosting the remaining CDN scripts.** Worth doing for cold-load latency,
  orthogonal to navigation consistency.
- **`problems-script` and `timeline-filter-script` rewrites**, beyond auditing
  them for the re-run hazard. Fix only if the audit finds a leak.

## Context

Investigation, 2026-08-28/29, prompted by "starting a workout set feels
sluggish."

Path from tap to running timer:

1. `workout.clj:756` — plain `biff/form` POST. Full browser navigation begins.
2. POST → `start-set!` (`workout.clj:1014`). Middleware runs `get-user-settings`;
   handler does `get-entity-for-user` + `sets-for-session` + a **synchronous**
   `biff/submit-tx` (awaits tx indexing); returns 303.
3. GET → `workout-page` (`workout.clj:948`). Middleware repeats; then
   `active-timers-for-user`, `get-user-authz`, `sets-for-session`,
   `lines-for-sets`, `exercises-for-user`, `exercise-memory`.
4. Full document parse against four render-blocking cross-origin scripts.
   `resources/public/sw.js` is push-only — no asset caching.
5. `tick-script`, at the bottom of the body, paints the first clock value.

Reads on step 3 that the clock does not need: `exercises-for-user`
(`all-entities-for-user`, every exercise doc, no limit) and `exercise-memory`
(200-line windowed scan over `exercise-line`, the densest type, with an
**unbounded** fallback scan when the 120-day window comes back empty —
`workout.clj:130-133`) both feed the log form, untouched until the set *ends*.
`lines-for-sets` and the session history render below the fold.

Measurement is already available: `obs/wrap-request-profiling` plus the
super-user dashboard at `/app/monitoring/performance`. Snapshots are manual —
press "Persist & Reset Metrics", exercise the path, press again. See
`performance.md`.

Key files: `ui.clj:26-64`; `main.js` (guard at 663-731); `workout.clj:210`,
`:226`; `boulder.clj:118`, `:139`; `tailwind.css:165` and `:745-754`;
`resources/tailwind.config.js:2-8`; `middleware.clj`.

**Prior decision this unit retires, deliberately.** The guard was documented as
never setting `disabled` on a submitter, because a disabled submitter is dropped
from the POST body and the timer Start button carries `parent-id` as name/value
(`tailwind.css:745-749`, `main.js:709-713`). That constraint is why `hx-sync` is
specified above rather than `hx-disabled-elt` — the reasoning outlives the code
being deleted.

**Why the guard existed:** timer Start had no feedback on a slow round trip. The
tap looked unregistered, a second tap followed, and two timers were created. Any
replacement must preserve visible in-flight feedback, which `htmx-request` does.

**Version note:** `hx-sync`, `hx-disabled-elt`, and `hx-on` all landed in htmx
1.9.0 — the exact pin at `ui.clj:50`. `hx-on` is confirmed working in this
codebase (`calendar.clj:96`); verify `hx-sync` on first use rather than assuming.

**Forward constraint:** `data-export.md` is `ready`. Boosted requests expect an
HTML response, so if export ever returns a file download that route needs
`hx-boost="false"` — and, per the carve-out in section 4, its own double-submit
handling. The current plan is copy-paste output, so no conflict today.

## Notes

Counts at time of writing — native `biff/form` POSTs, 42 across 12 files:
workout 12, home 5, shared 5, boulder 5, crud/views 3, crud/forms 3, calendar 3,
timers 2, and one each in `timer/routes.clj`, `user.clj`, `meditation_log.clj`,
`app.clj`. Explicit `hx-post` call sites: 15. Inline `[:script]` blocks: 5,
across `workout.clj` (2), `boulder.clj` (2), `overview.clj` (1).

On consistency, stated precisely: this does not reduce the app to one model. It
goes from **two accidental** models — native or htmx, decided by history — to
**two deliberate** ones: boosted body-swap by default, explicit fragment swap
where a path has earned it. The second becomes an opt-in optimization instead of
an inconsistency.
