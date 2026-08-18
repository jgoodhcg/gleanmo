---
title: "Cronometer Integration"
status: draft
description: "Pull nutrition and body composition from Cronometer into Gleanmo — recent days over the reverse-engineered mobile API, historical backfill by CSV import"
created: 2026-04-24
updated: 2026-08-18
tags: [integration, nutrition, biometrics, llm-context, external-data]
priority: medium
---

# Cronometer Integration

## Intent

Pull nutrition and body composition data from Cronometer into Gleanmo so it can
serve as LLM-ready context for daily training and nutrition decisions.
Cronometer is the source of truth for food logging (data flows in from Fitbit
and Oura already); Gleanmo becomes the unified data layer that combines
nutrition with sleep, exercise, mood, and other tracked metrics.

The primary use case is not visualization — it is making structured nutrition
data available alongside other quantified-self data so an LLM can reason about
"given what I ate, how I slept, and how I trained, what should I do today?"
Once the entities exist, they get export for free through the generic entity
exporter in [data-export.md](./data-export.md), which is what puts nutrition
and workouts in the same paste.

## Specification

### Data Ingestion

- **Food diary entries**: individual foods/meals with timestamps, macros
  (protein, fat, carbs, calories), and serving info.
- **Body composition**: weight, body fat %, lean mass tracked in Cronometer.
- **Historical backfill**: import existing large history from Cronometer.

### Integration Method (decided 2026-08-18)

Two paths, split by job — not "pick one":

1. **Mobile REST API for recent days.** A small client fetches the last N days
   on demand or on a schedule. This is the ongoing sync path.
2. **CSV export + import for the historical backfill.** One-time, run as a
   migration. The diary endpoint is one request per day (see Constraints), so
   pulling years of history over the API is thousands of calls against an
   unofficial endpoint for data a single CSV already contains.

Earlier drafts recommended CSV-first for everything and treated the API as a
later upgrade contingent on Cronometer approving developer credentials. That is
superseded: there is a working reverse-engineered client to build against, so
the schemas can be designed against the real API response shape from the start
rather than against CSV columns that would need re-modeling later.

### API reference

[`rwestergren/cronometer-api-mcp`](https://github.com/rwestergren/cronometer-api-mcp)
(MIT, Python) is an MCP server built on the mobile REST API, reverse-engineered
from the Cronometer Android app (Flutter/Dart, v4.52.6) by static analysis of
the AOT snapshot plus intercepted traffic. It is the endpoint catalog and the
auth recipe; use it as reference, not as a dependency.

- Base: `https://mobile.cronometer.com`
- Auth: `POST /api/v2/login` with email/password → `{id, sessionKey, timezone}`.
  Every later v2 call carries an auth block in the JSON body
  (`{userId, token, api: 3, os: "Android", build: "2807", flavour: "free"}`).
  v3 calls authenticate by `x-crono-session` header instead.

Endpoints that matter here:

| Endpoint | Returns | Window |
|---|---|---|
| `POST /api/v2/get_diary` | Diary entries for a day: per-food servings, meal group | **one day** |
| `POST /api/v2/get_nutrients` | Nutrient totals for a day | one day |
| `POST /api/v2/get_metrics` | Biometric catalog: metric ids + unit ids (Weight, Body Fat, ...) | n/a |
| `POST /api/v2/get_biometrics` | `{data: [{day, value}]}` for one metric + unit | **date range** |
| `POST /api/v2/get_food` / `get_foods` | Full nutrition profile for food ids | n/a |
| `POST /api/v2/get_fasting_with_date_range` | Fasting history | date range |

Nutrient ids from the login response, needed to read any nutrient payload:
energy 208, protein 203, carbs 205, net carbs -1205, fat 204, fiber 291,
sugar 269, sodium 307, alcohol 221, saturated fat 606, cholesterol 601,
trans fat 605, omega-3 10001, omega-6 10002.

### Constraints and hazards

These are the reasons the ingest is shaped the way it is:

- **`get_diary` takes one day, not a range.** Biometrics and fasting take
  ranges; the food diary does not. Any multi-day pull is a loop of requests, so
  the sync must be resumable, throttled, and cursor-driven — and the backfill
  should be CSV.
- **Login is rate-limited per account.** The reference client caches the
  `sessionKey` to disk specifically to avoid it. Cache the session; do not log
  in per request.
- **A non-null `timezone` on login is a *write*.** It overwrites the account's
  server-side timezone and persists across sessions — the reference project's
  issue #29, where older builds silently reset every user's account zone to
  Eastern. Always send `timezone: null` and read the account zone back out of
  the login response. This one has cross-device blast radius: it would change
  the timezone the user's phone app uses.
- **`firebaseToken: ""` in the login payload is unverified.** It is a push
  notification registration field. Sending empty may clobber the phone's push
  registration for the account. Low harm, easy to observe (Cronometer
  notifications stop arriving on the phone), no known mitigation short of
  sending nothing.
- **Unofficial API, no stability contract.** It can break on any Cronometer app
  release. Contain the blast radius: one namespace owns every HTTP call and
  normalizes responses into Gleanmo maps at that single boundary, so a shape
  change is a one-file fix.
- **iOS is the same backend.** The Cronometer app is Flutter — one codebase for
  both platforms, hitting the same `mobile.cronometer.com` endpoints.
  Reverse-engineering the iOS build would cost significantly more (no APK to
  unpack) for the same endpoint list. Build against the Android-derived client.

### Phases

**Phase 0 — session-safety check (gate, ~15 minutes).** Log in once from a REPL
with the phone app open, then use the phone app and confirm it does not demand
re-authentication. This answers the only question that could kill the API path:
whether Cronometer allows concurrent sessions per account or invalidates the
previous one. If the phone gets logged out, drop to CSV-only ingest and close
the API path.

**Phase 1 — experiment and shape capture.** A dev-only client namespace
(`dev/cronometer/client.clj`) with `login!`, `get-diary`, `get-metrics`,
`get-biometrics`, credentials read from env, and a `comment` block. Dump raw
responses to `cronometer_data/*.edn`. One day of diary plus one biometric range
answers every remaining modeling question. No schemas, no writes.

**Phase 2 — schemas.** Model against the captured payloads (see Data Model).

**Phase 3 — recent-days sync.** Promote the client to
`src/tech/jgood/gleanmo/cronometer/client.clj`, add a sync that pulls the last
N days (default small — a week), upserts idempotently, and records a per-day
cursor so a re-run is cheap and safe. Writes via `db/mutations.clj`.

**Phase 4 — CSV backfill.** A migration (`dev/tasks/migrations/m0NN_*.clj`)
following the `m005_airtable_import_exercise.clj` pattern: `--dry-run`,
`--target dev|prod`, batched submits, idempotent re-runs. CSV parsing via
`org.clojure/data.csv`, already in `deps.edn`.

### Data Model

New schemas, modeled after Phase 1:

- `nutrition-log` — individual food entries with timestamp, food name, serving,
  macros.
- `body-composition-log` — weight, body fat %, lean mass entries.

Both follow standard field ordering with `cronometer/*` lineage fields for
traceability (analogous to `airtable/*` fields), and omit `sm/legacy-meta` —
new schemas do not carry it.

```clojure
[:cronometer/id           {:optional true} :string]   ; Cronometer entry ID
[:cronometer/exported-at  {:optional true} :instant]  ; when data was exported/synced
```

Idempotency key: `:cronometer/id` where the payload carries one. Where it does
not (notably CSV rows), derive a stable synthetic key from day + food name +
serving so a re-import updates rather than duplicates.

### Credentials

The sync needs a Cronometer username and password.

- **Recommended for now: environment secrets**, read via Biff's `biff/secret`
  the way `mailersend/api-key` is in `src/tech/jgood/gleanmo/email.clj:85`.
  Gleanmo is a personal single-user app; this is the whole job.
- **A per-user settings form storing credentials in XTDB is a bigger change
  than it looks.** The codebase has no encryption-at-rest primitive, and
  putting a plaintext third-party password in the document store is worse than
  putting it in the deploy environment. If per-user config is wanted, it needs
  an encryption decision first — see Open Questions.
- The cached `sessionKey` is a bearer token. Hold it in memory (an atom) and
  re-login on restart; do not persist it to the document store.

### Views

- **Food diary log**: searchable log/table view of nutrition entries via the
  standard CRUD system, filterable by date range. This is also how the import
  gets eyeballed for correctness.
- No charting needed initially — the data serves LLM context first,
  visualization later.

### Export

- Both new entities opt into the generic entity exporter from
  [data-export.md](./data-export.md) — one line per entity namespace, no
  export-specific code.
- A nutrition **composite** (day → meals → foods → macros) is a likely
  follow-on if the flat per-food table reads poorly as LLM context. Not in this
  unit's scope; decide after seeing a real paste.

## Validation

- [ ] Phase 0: the phone app stays authenticated after a REPL login.
- [ ] Schema defined and registered for `nutrition-log` and
      `body-composition-log`.
- [ ] Client fetches a day of diary and a biometric range against the live API.
- [ ] Sync is idempotent: running it twice over the same window writes no
      duplicates.
- [ ] CSV parser handles the Cronometer export format(s).
- [ ] Historical data imported without errors.
- [ ] Food diary log view renders imported data.
- [ ] Data queryable through the standard `db/queries.clj` layer.
- [ ] Entity appears on the entities dashboard.
- [ ] Both entities appear in `/app/export` and export correctly.
- [ ] New test namespaces are required in `test/tech/jgood/gleanmo/test.clj`,
      or the runner skips them silently.
- [ ] `just lint-fast` and `just check` pass on touched files.

## Scope

### In scope

- Read-only data import from Cronometer (API for recent days, CSV for history).
- Food diary log view.
- Body composition log.
- Historical backfill.
- Cronometer lineage metadata on imported entities.

### Out of scope

- Writing nutrition data back to Cronometer (the API supports `add_serving` and
  `add_food`; Cronometer stays the logging interface and the source of truth).
- Replacing Cronometer as the food logging interface.
- Fasting history, nutrition scores, and the food-search endpoints — available,
  not needed yet.
- Nutrition charts/visualizations (defer to generic-viz work unit).
- Micronutrient tracking (initial scope is macros + calories).
- Daily summary/scorecard views.
- A nutrition composite export (see Export).

## Context

- Cronometer Gold account (paid). Official developer API access was never
  applied for and is no longer on the critical path.
- Fitbit and Oura already feed data into Cronometer.
- Cronometer CSV exports are confirmed available.
- API reference:
  [rwestergren/cronometer-api-mcp](https://github.com/rwestergren/cronometer-api-mcp).
- Existing schema conventions: `src/tech/jgood/gleanmo/schema/` — see
  `reading_schema.clj` for a recent example.
- Migration pattern: `dev/tasks/migrations/m005_airtable_import_exercise.clj`.
- CRUD system: `src/tech/jgood/gleanmo/crud/`.
- DB layer: `src/tech/jgood/gleanmo/db/queries.clj`,
  `src/tech/jgood/gleanmo/db/mutations.clj`.
- Related work units: [data-export.md](./data-export.md) (how this data reaches
  the coach agent), [generic-viz.md](./generic-viz.md) (future charting),
  [scheduled-work-multi-instance.md](./scheduled-work-multi-instance.md) (if
  the sync becomes a scheduled task, it inherits that unit's singleton
  problem).

## Open Questions

- [ ] Phase 0 result: does a REPL login invalidate the phone's session?
- [ ] Credentials: env secret (recommended) or per-user encrypted storage? The
      latter needs an encryption-at-rest decision the codebase has not made.
- [ ] Does sending `firebaseToken: ""` disturb push notifications on the phone?
- [ ] What does a Cronometer CSV export actually look like (columns, format,
      date ranges)?
- [ ] How to handle Cronometer's composite foods / recipes in the import?
- [ ] How large is the historical dataset (approximate record count / date
      range)?
- [ ] Is `nutrition-log` one entity, or does a meal grouping entity earn its
      place?
- [ ] Should the recent-days sync be manual, on page load, or a scheduled task?

## Notes

### User Interview Summary (2026-04-24)

**Primary motivation**: feed context into LLMs to make decisions about training
and nutrition day to day. Secondary: unified dashboard, data backup/ownership.

**Data of interest**: individual foods/meals (not just daily aggregates), body
composition (weight, body fat, lean mass).

**Visualization**: food diary log as searchable table — charts not the
priority.

**History**: large historical dataset in Cronometer — wants to backfill.

**Read/write**: Cronometer stays source of truth. Read-only import into Gleanmo.

### Method decision (2026-08-18)

CSV-first was rejected for the ongoing sync to avoid modeling the schemas
against CSV columns and then re-modeling them against API payloads. CSV kept
its job — the one-time historical backfill — because `get_diary` is per-day and
a multi-year API backfill would be thousands of requests against an unofficial
endpoint.

The alternative considered and set aside: point the health-coach agent directly
at `cronometer-api-mcp` and skip Gleanmo ingest for nutrition entirely. That
delivers nutrition context immediately with zero build, but it leaves the data
in Cronometer, splits the coach's inputs across two sources, and fails the
project's ownership goal. It remains a viable stopgap if Phase 0 fails.
