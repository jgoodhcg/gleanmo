---
title: "Cronometer Integration"
status: active
description: "Pull food, recipes, biometrics and daily nutrition summaries from Cronometer into Gleanmo for the agent API — recent days over the reverse-engineered mobile API, historical backfill by CSV import"
created: 2026-04-24
updated: 2026-10-13
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
exporter in [078-data-export.md](./078-data-export.md), which is what puts nutrition
and workouts in the same paste.

## Specification

### Data Ingestion

- **Food diary entries**: every serving with its time, portion, and the full
  nutrient profile scaled to the serving — not only macros.
- **Foods and recipes**: the food records servings point at, including custom
  recipes and their ingredients, so recipe composition can be analysed (e.g.
  vegan or whole-food compliance from ingredient tags).
- **Biometrics**: every reading in the diary — weight, body fat, blood
  pressure, blood glucose, custom metrics, and the Oura values Cronometer
  imports. Cronometer is the single pipe for these for the foreseeable future.
- **Daily summary**: Cronometer's own per-day figures, kept even though food
  logs can rebuild the macros.
- **Historical backfill**: import existing large history from Cronometer.

The consumer is the agent API ([061-ai-assistance.md](./061-ai-assistance.md)).
UI is limited to what proves the sync works.

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
| `POST /api/v2/get_diary` | A day's servings, biometrics, exercise entries, plus `summary`, macro `template`, `info.complete`, `fasts` | **one day** |
| `POST /api/v2/get_nutrients` | The nutrient **definition catalog** (95 ids, names, units, RDIs) — no consumed amounts despite the name | n/a |
| `POST /api/v2/get_nutrition_scores` | Consumed amount + confidence per *tracked* nutrient (44 here), for a list of `servingIds` | servings |
| `POST /api/v2/get_metrics` | Biometric catalog: metric ids + unit ids (Weight, Body Fat, ...) | n/a |
| `POST /api/v2/get_biometrics` | `{data: [{day, value}]}` for one metric + unit | **date range** |
| `POST /api/v2/get_food` / `get_foods` | Food records: name, `foodTags`, measures, nutrients (per 100 g; per serving for recipes), recipe `ingredients` | n/a |
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

**Phase 0 — session-safety check. Passed 2026-10-12.** A CLI login left the
iPhone app signed in; reopening it once showed a brief "logging in" screen and
recovered without a credential prompt, and it stayed signed in through the
later dumps. The account timezone was unchanged. Still unobserved: whether
`firebaseToken: ""` stops push notifications.

**Phase 1 — shape capture. Done 2026-10-12.** The probe is a Biff task,
`dev/tasks/cronometer.clj` (`clj -M:dev cronometer-probe login | dump --day D`).
Credentials come from `CRONOMETER_EMAIL` / `CRONOMETER_PASSWORD` (env, then
`config.env`); the session is cached in `cronometer_data/session.edn`. All
output lands in the gitignored `cronometer_data/`. Dumps cover three days,
including a custom recipe and a blood pressure + glucose reading. Results
are under Probe Findings.

**Phase 2 — schemas.** See Data Model.

**Phase 3 — recent-days sync.** Promote the client to
`src/tech/jgood/gleanmo/cronometer/client.clj`, add a sync that re-pulls the
last N days (default small — a week), upserts idempotently, and soft-deletes
rows that vanished from a re-pulled day. Writes via `db/mutations.clj`. Per day:
`get_diary`, one `get_foods` for uncached food ids (and recipe ingredients,
recursively), `get_nutrition_scores` only if its confidences are wanted.

**Phase 4 — CSV backfill.** A migration (`dev/tasks/migrations/m0NN_*.clj`)
following the `m005_airtable_import_exercise.clj` pattern: `--dry-run`,
`--target dev|prod`, batched submits, idempotent re-runs. CSV parsing via
`org.clojure/data.csv`, already in `deps.edn`. Whether CSV carries ids that
line up with the API's is unverified.

### Probe Findings (2026-10-12)

- **Per-serving nutrients reproduce Cronometer's day totals exactly.** Each
  serving is `foodId`, `measureId`, `grams`, `time`. Scale the food's nutrients
  by `grams / 100` — or by `grams` alone when the measure `type` is `Recipe`,
  where `grams` is a serving count (`1` = "full recipe"). Summed, that matched
  `get_nutrition_scores` and the diary summary on energy, protein, carbs, fat,
  fiber, sodium and caffeine for all three probe days, recipe day included. It
  also yields ~95 nutrients per day against the 44 the scores endpoint reports.
- **Some nutrient ids are not in the definition catalog** (negative ids such as
  -203/-204/-205/-221 on nearly every food; USDA foods add fatty-acid ids from
  652 up). They stay in the raw payload and out of the translated map.
- **Servings carry a stable `servingId`; biometrics a stable `biometricId`.**
  Both are the idempotency keys.
- **Times are wall-clock local, offsets unreliable.** A recipe logged at a
  known local time reads back as exactly that time. Servings and manual readings carry `offset 0` (one
  serving `nil`); Oura entries carry `-240`, and one heart-rate summary `-12032`.
- **Custom recipes:** `owner` = the user's Cronometer id, `source "Custom"`,
  **no `foodTags`**, and an `ingredients` list of `{foodId measureId grams id}`.
  Tags on stock foods: `Vegan`, `Vegetarian`, `Whole`, `Brand`, `Supplement`,
  `Nuts`. Compliance for a recipe therefore has to come from its ingredients.
- **Blood pressure is one entry with a composite:** `amount 1` (placeholder),
  metric 32524, `meta.composite` = `[{metricId 4 value <systolic>} {metricId 5
  value <diastolic>}]`. A manual weight entry carried a stale composite in its
  meta — composites are meaningful only on composite metrics.
- **Biometric units come as entered:** weight in lbs (unit 2), glucose in
  mg/dL (unit 9), body fat in % (unit 13). `get_biometrics` converts to the
  unit asked for, but carries no time — biometrics sync from the diary instead.
- **Oura per-day values have no time:** Recovery, Respiration Rate, Body Temp
  Variation and Sleep Score. The Sleep entry has `time` = bedtime and
  `meta.wakeup_time`, `time_in_bed`, `stage_summary`.
- **Exercise entries** (Oura daily activity, Google Health) are calorie
  estimates, not workouts.

### Data Model (decided 2026-10-12)

Four entities, all imported and read-only from the app's side; none carry
`sm/legacy-meta`. Every measured value follows the unit rule in AGENTS.md
(schema conventions): a sibling `<field>-unit` enum, even with one member, and
the unit stored as Cronometer gave it — no conversion at sync time. Comparisons
across units are a shared read-side conversion, as for exercise weight.

| Entity | One per | Purpose |
|---|---|---|
| `food` | Cronometer food or recipe | name, tags, nutrient profile, measures, recipe ingredients |
| `food-log` | diary serving | what was eaten, when, how much, nutrients scaled to it |
| `biometric-log` | diary biometric reading | one reading, typed fields per supported metric |
| `nutrition-day` | diary day | Cronometer's own day figures (kept even though food logs rebuild macros) |

#### `food`

```clojure
[:food/label :string]
[:food/kind [:enum :food :recipe]]
[:food/source {:optional true} :string]              ; "NCCDB:7772", "CRDB", "Custom"
[:food/tags {:optional true} [:set :string]]         ; Cronometer foodTags verbatim
;; per 100 g for :food, per one reference serving for :recipe
[:food/nutrients {:optional true} [:map-of :keyword :number]]
[:food/measures {:optional true}
 [:vector [:map {:closed true}
           [:label :string] [:type :string] [:amount :number] [:value :number]
           [:cronometer/measure-id :int]]]]
[:food/ingredients {:optional true}                  ; recipes only
 [:vector [:map {:closed true}
           [:food-id :food/id]                       ; the ingredient's own food doc
           [:grams :number]
           [:portion-label {:optional true} :string]
           [:portion-quantity {:optional true} :number]
           [:cronometer/food-id :int]
           [:cronometer/ingredient-id :int]]]]
[:cronometer/food-id :int]
[:cronometer/owner {:optional true} :int]            ; non-zero = the user's custom food
```

Ingredients are embedded rather than a `recipe-ingredient` entity — they have
no life outside their recipe. Each references the ingredient's own `food`, which
the sync fetches (recursively for nested recipes), so ingredient tags drive
vegan / whole-food analysis: custom recipes carry **no** `foodTags` of their
own. XTDB history keeps the ingredient list as it stood when an old serving was
eaten.

#### `food-log`

```clojure
[:food-log/timestamp :instant]
[:food-log/timestamp-basis {:optional true} [:enum :logged :day-start]]
[:food-log/food-id :food/id]
[:food-log/label {:optional true} :string]           ; copy of food label, for agents
[:food-log/grams {:optional true} :number]           ; for a recipe: serving count
[:food-log/portion-label {:optional true} :string]   ; "small or tall - each 12 fl oz"
[:food-log/portion-quantity {:optional true} :number]
[:food-log/nutrients {:optional true} [:map-of :keyword :number]] ; scaled to this serving
[:cronometer/serving-id :int]
[:cronometer/food-id :int]
[:cronometer/measure-id {:optional true} :int]
[:cronometer/translation-id {:optional true} :int]
[:cronometer/order {:optional true} :int]
```

**Nutrients** are `[:map-of :keyword :number]` keyed without units (`:protein`,
`:vitamin-c`, `:caffeine`). Keys and units come from a committed nutrient table
(Cronometer nutrient id → key, label, unit, category), so an upstream rename
never moves data. This is the stated exception to the unit rule: Cronometer
itself sends nutrient amounts without units, and a unit beside each of ~95
values per row would only repeat the table. The agent API publishes the table.
Ids absent from Cronometer's definition catalog (negative ids, USDA fatty-acid
ids) stay in raw only.

Scaling: `amount × grams / 100`, or `amount × grams` when the serving's measure
`type` is `"Recipe"` (then `grams` is a serving count). Verified exact against
Cronometer's totals — see Probe Findings.

#### `biometric-log`

Flat typed fields per supported metric; no `biometric` definition entity and no
values map (both considered and rejected 2026-10-12: Cronometer has 54 stock
metrics and the user has no custom ones, so one schema edit per newly used
metric beats an entity + relation + map on every reading, and flat fields are
what `/types` can describe and the API can filter on). Field names match
`symptom-log`'s vitals so values are comparable; the two entities are otherwise
unrelated.

```clojure
[:biometric-log/timestamp :instant]
[:biometric-log/timestamp-basis {:optional true} [:enum :logged :wake-time :day-start]]
[:biometric-log/label :string]                        ; Cronometer metric name
[:biometric-log/source {:optional true} :string]      ; "Oura"; absent = manual
[:biometric-log/weight {:optional true} :number]
[:biometric-log/weight-unit {:optional true} [:enum :lbs :kg :st]]
[:biometric-log/body-fat {:optional true} :number]
[:biometric-log/body-fat-unit {:optional true} [:enum :percent :kg :lbs]]
[:biometric-log/bp-systolic {:optional true} :number]
[:biometric-log/bp-systolic-unit {:optional true} [:enum :mmhg]]
[:biometric-log/bp-diastolic {:optional true} :number]
[:biometric-log/bp-diastolic-unit {:optional true} [:enum :mmhg]]
[:biometric-log/blood-glucose {:optional true} :number]
[:biometric-log/blood-glucose-unit {:optional true} [:enum :mg-dl :mmol-l]]
[:biometric-log/heart-rate {:optional true} :number]
[:biometric-log/heart-rate-unit {:optional true} [:enum :bpm]]
[:biometric-log/hrv {:optional true} :number]
[:biometric-log/hrv-unit {:optional true} [:enum :ms]]
[:biometric-log/respiration-rate {:optional true} :number]
[:biometric-log/respiration-rate-unit {:optional true} [:enum :brpm]]
[:biometric-log/sleep {:optional true} :number]
[:biometric-log/sleep-unit {:optional true} [:enum :hours]]
[:biometric-log/sleep-score {:optional true} :number]
[:biometric-log/sleep-score-unit {:optional true} [:enum :percent]]
[:biometric-log/recovery {:optional true} :number]
[:biometric-log/recovery-unit {:optional true} [:enum :percent]]
[:biometric-log/temp-variation {:optional true} :number]
[:biometric-log/temp-variation-unit {:optional true} [:enum :celsius :fahrenheit :kelvin]]
[:cronometer/biometric-id :int]
[:cronometer/metric-id :int]
[:cronometer/unit-id {:optional true} :int]
[:cronometer/external-id {:optional true} :string]
```

| Cronometer metric (id) | Fields |
|---|---|
| Weight (1) | `weight` |
| Body Fat (8) | `body-fat` |
| Blood Pressure (32524) — `amount 1` placeholder; values in `meta.composite` as Systolic (4) / Diastolic (5) | `bp-systolic`, `bp-diastolic` — one row |
| Blood Glucose (6) | `blood-glucose` |
| Heart Rate (3) | `heart-rate` |
| Heart Rate Variability (40002) | `hrv` |
| Respiration Rate (50088) | `respiration-rate` |
| Sleep (764) — `hr:min` unit, decimal hours | `sleep` |
| Sleep Score (57418) | `sleep-score` |
| Recovery (47185) | `recovery` |
| Body Temp Variation (52242) | `temp-variation` |
| anything else | a row with `label` + raw only, until it earns fields |

A reading whose unit is missing from the field's enum gets no typed value (raw
only, warning logged) rather than failing the day's write.

#### `nutrition-day`

```clojure
[:nutrition-day/day :local-date]
[:nutrition-day/completed {:optional true} :boolean] ; diary info.complete
[:nutrition-day/summary {:optional true} :string]    ; diary `summary` (consumed/burned/macros), EDN
[:nutrition-day/template {:optional true} :string]   ; macro target template, EDN
[:nutrition-day/fasts {:optional true} :string]      ; EDN
[:nutrition-day/exercises {:optional true} :string]  ; diary Exercise entries (Oura activity, Google Health), EDN
```

Stored as EDN strings because nothing reads them yet ("can't hurt to just have
it"); promote fields when something does. `day` is a `:local-date`, the one
entity where a day, not an instant, is the honest key.

#### Activity observations — imported by the sync (2026-10-13)

`activity-observation` is registered in `schema.clj` with its schema in
`schema/activity_observation_schema.clj`. It represents tracker reports
separately from exercise sessions. The sync turns every diary Exercise entry
into one (`transform/exercise->activity-observation`); `nutrition-day/exercises`
still keeps the day's raw entries.

- Source time is the **beginning** (2026-10-13: "I'm pretty sure the time from
  cronometer is the beginning. I feel good about assuming that"). End is
  beginning + `minutes`, with `auto-ended` true; no positive minutes, no end.
- Scope: timed → `:session`; untimed "Daily Activity …" (Oura) → `:daily`;
  any other untimed entry (Google Health Activity, seen with `minutes 0`) →
  `:unknown`.
- Observations vanish and reappear with their diary day like food and
  biometric logs. `exercise-session-id` is user-managed: the sync neither
  compares nor removes it (`sync/user-managed-keys`).

- `scope` distinguishes `:session`, `:daily`, and `:unknown` reports.
- Timed activities use `beginning`/`end`, following exercise sessions and sets.
  Interpret source day/time with the existing Cronometer `->instant` helper
  and `user/time-zone`. Preserve the upstream offset without using it.
- Confirm whether source time means start, end, or logging time before
  deriving intervals. When end is derived from beginning + upstream minutes,
  set `auto-ended` true. Calculate application duration from boundaries.
- Daily and untimed observations retain `day` without invented boundaries.
  This follows `nutrition-day/day`'s specific date-only precedent.
- Original minutes, signed calories, and other upstream values remain in
  `cronometer/raw`. Promote source-only attributes only when a reader needs them.
- An optional `exercise-session-id` can link a report to a logged workout.
  Future sync must preserve this user-managed link when updating source data.
- Observations are not timer-enabled. Missing boundaries do not mean running.

The status page counts activities and timed sessions for the recent window,
and the e2e test checks both against the dev fake, which emits all three
observed shapes (Daily Activity, timed walk, untimed Google Health).

Remaining implementation: read-only API exposure (an API group adds a
checkbox on the token page, so it is a UI change), and a way to set the
session link. No CRUD routes, Quick Add links, timer
routes, or smoke-test routes were added for this schema-only foundation.
Daily estimates and individual activities must not be summed until overlap
is understood.

#### Time

- Every log gets one `timestamp` instant, interpreted as **wall-clock time in
  Gleanmo's `user/time-zone`** — not Cronometer's account zone, not the entry's
  `offset` (unreliable: `0`, `nil`, `-240`, `-12032` all seen).
- `timestamp-basis` records how it was derived:
  - `:logged` — the entry's own `time`.
  - `:wake-time` — Oura per-day values with no `time` (Recovery, Sleep Score,
    Respiration Rate, Body Temp Variation) take that day's Sleep entry
    `meta.wakeup_time`: they describe the night that ended that morning and
    become knowable at waking. **The deriving code must carry this rationale in
    a comment.**
  - `:day-start` — no time and no sleep entry that day: local midnight.
- Sleep is really an interval (bedtime in `time`, wake in `meta`); modelling it
  as `beginning`/`end` is deferred.

#### Lineage — keep everything

Every imported entity also carries:

```clojure
[:cronometer/raw       {:optional true :hide true} :string]  ; the entry/food payload, EDN
[:cronometer/day       {:optional true} :string]             ; raw "2026-10-11"
[:cronometer/time      {:optional true} :string]             ; raw "16:34:00"
[:cronometer/offset    {:optional true} :int]                ; raw, unreliable
[:cronometer/synced-at {:optional true} :instant]
```

The raw day/time/offset make a time-zone correction a recompute, not a
re-fetch.

#### Identity

`xt/id` is a name-based UUID (`UUID/nameUUIDFromBytes`) of
`"<user-id>/<kind>/<cronometer key>"` — serving id, biometric id, food id, or
day — so a re-sync addresses the same documents without a lookup query. The
user id is included because Cronometer food ids are global while `food` docs
are per user.

### Credentials (decided 2026-10-12)

The sync needs each user's Cronometer email and password.
They are stored per user in XTDB, encrypted.
This replaces the earlier plan of environment secrets for one configured owner.

- **Entity `cronometer-connection`**, one per user.
  It holds the email and password together as one AES-256-GCM ciphertext,
  with its random 12-byte nonce and a key version (`1` for now).
  The key version costs nothing today and makes a later rotation possible.
- **Key:** `CREDENTIALS_KEY`, a base64 32-byte key,
  read through `#biff/secret` as `:gleanmo/credentials-key`.
  Without it, the connect form is disabled and the sync stays dormant.
  The key is general-purpose: a later integration encrypts with the same primitive.
- **Primitive:** `src/tech/jgood/gleanmo/crypto.clj`, `encrypt` / `decrypt`
  over `javax.crypto`. No new dependency.
- **A leaked database or backup exposes only ciphertext.**
  Reading a password needs both the database and the environment.
- **Disconnect evicts.** XTDB keeps every document version,
  so a soft delete or an overwrite leaves the old ciphertext in history.
  Disconnecting calls `xt/evict` on the connection, through a new function in `db/mutations.clj`.
  Changing the password evicts the old connection and writes a new one, for the same reason.
- **Unreachable from everywhere else.** `cronometer-connection` is in no API
  group, not in `crud-entities`, and must stay out of the
  [078-data-export.md](./078-data-export.md) exporter.
- **Key rotation is not handled yet** — see Open Questions.
  Losing or replacing the key makes every stored credential undecryptable;
  each user then has to reconnect. No other data is affected.
- **Session:** the `sessionKey` from login is a bearer token.
  It is held in memory, in an atom keyed by user id, and never persisted.
  Re-login on restart and once on an auth failure.
  This is correct only with one backend instance — see
  [077-scheduled-work-multi-instance.md](./077-scheduled-work-multi-instance.md).

Multiple users of an unofficial API carry a risk that encryption does not address.
The app collects third-party passwords and drives an undocumented endpoint from one server IP.
If Gleanmo gets real other users, apply for Cronometer's official developer API (OAuth).

### Views

- Minimal: one `/app/cronometer` status page — last sync time, row counts per
  type for the recent window, the latest day's macros, and a "Sync now" button.
  No CRUD routes for these entities in this unit.
- No charting — the data serves the agent API first.

### Export

- [078-data-export.md](./078-data-export.md) is still draft; when it lands, the
  four entities opt into its generic exporter (one line each). Not a dependency
  of this unit — the agent API is the consumer.

## Implementation Plan

Written so another agent can execute it. Three slices, each independently
mergeable and validated; the first touches no network and no database.

**Ground rules for the implementer**
- Read the Data Model section above first; it is the spec. Read AGENTS.md's
  schema conventions (unit rule, boolean naming, no legacy meta), the
  `db/queries.clj` / `db/mutations.clj` rules, and the `:biff/db` snapshot
  anti-pattern.
- Personal data never enters the repo. `cronometer_data/` (gitignored) holds
  the user's real dumps. Build and test against **synthetic** fixtures,
  hand-written to mirror the shapes in Probe Findings (this repo is public):
  no value, food name, recipe, reading, id or date copied from the dumps.
  Before each commit, check that no staged file contains values from
  `cronometer_data/`.
- No live Cronometer calls from an agent session. The live sync and the
  real-data check (Slice A step 6) are user-run; the user gives instructions
  when implementation starts (2026-10-12).
- The reference for every endpoint and payload is `dev/tasks/cronometer.clj`
  (working, verified against the live API) and
  [rwestergren/cronometer-api-mcp](https://github.com/rwestergren/cronometer-api-mcp)
  `client.py`.
- Never send a non-null `timezone` on login.

### Slice A — schemas and pure transform (no network, no DB writes)

1. **Schemas** — `food`, `food-log` and `nutrition-day` in
   `schema/nutrition_schema.clj`, `biometric-log` in `schema/biometric_schema.clj`;
   `schema/cronometer_schema.clj` keeps only `cronometer-connection` and the
   shared `lineage` vector. Defined exactly as the Data
   Model section, standard field ordering (`xt/id`, `::sm/type`,
   `::sm/deleted-at`, `::sm/created-at`, `user/id`, domain fields, then
   `cronometer/*`). Every `cronometer/*` attribute is optional, nested ones
   included (2026-10-13: "All cronometer related attributes should be on the
   generic schemas pretty much as they are. They should just all be optional.
   As we add providers we will add provider specific optional attributes to
   these generic entities."). Register `:food/id`, `:food-log/id`, `:biometric-log/id`,
   `:nutrition-day/id` (`:uuid`) and the four schemas in `schema.clj`. Do
   **not** add them to `crud-entities` or the route vector. Check that
   `schema.utils/extract-schema-fields` and `api.coerce/type->json` cope with
   `[:map-of ...]` and `[:vector [:map ...]]` fields (a `/types` call must not
   throw); `performance_schema.clj` is the existing `:map-of` precedent.
2. **Nutrient table** — `src/tech/jgood/gleanmo/cronometer/nutrients.clj`: a
   literal map from Cronometer nutrient id → `{:key :protein, :label "Protein",
   :unit :g, :category "Protein"}`, generated once from
   `cronometer_data/nutrient_definitions.edn` (95 entries) and committed.
   Keys: kebab-case of the name, parentheticals folded (`"B1 (Thiamine)"` →
   `:b1-thiamine`, `"Lutein+Zeaxanthin"` → `:lutein-zeaxanthin`); units as
   keywords (`g`→`:g`, `mg`→`:mg`, `µg`→`:ug`, `kcal`→`:kcal`, `IU`→`:iu`).
   Keys must be unique — assert it in a test. This table is a definitional
   artifact (names and units, no personal data) and is fine to commit.
3. **Unit and metric tables** — in `cronometer/biometrics.clj`: Cronometer unit
   name → keyword (`"lbs"`→`:lbs`, `"kg"`→`:kg`, `"st"`→`:st`, `"%"`→`:percent`,
   `"mmHg"`→`:mmhg`, `"mg/dL"`→`:mg-dl`, `"mmol/L"`→`:mmol-l`, `"bpm"`→`:bpm`,
   `"ms"`→`:ms`, `"brpm"`→`:brpm`, `"hr:min"`→`:hours`, `"°C"`→`:celsius`,
   `"°F"`→`:fahrenheit`, `"Kelvin"`→`:kelvin`), and metric id → field pair per
   the table in Data Model. Units are resolved through the **metric catalog**
   (`get_metrics`: metric → units with ids), since diary entries carry only
   `unitId`.
4. **Transform** — `src/tech/jgood/gleanmo/cronometer/transform.clj`, pure
   functions, no I/O:
   - `doc-id [user-id kind key]` — name-based UUID.
   - `->instant [day-str time-str zone]` — `LocalDate` + `LocalTime` in `zone`.
     Responses carry zero-padded ISO days (`"2026-10-11"`), which parse
     directly; only *requests* use the non-padded `YYYY-M-D` form.
   - `food->doc [user-id food synced-at]` — label, kind (`:recipe` when any
     measure `type` is `"Recipe"`), source, tags, nutrients via the nutrient
     table, measures, ingredients (with `food-id` = `doc-id` of the ingredient
     food), owner, raw.
   - `serving->food-log [user-id zone serving food synced-at]` — measure lookup,
     portion label/quantity (`grams / measure.value`), scaling rule, label copy,
     timestamp (`:logged`; `:day-start` if `time` is absent).
   - `diary->biometric-logs [user-id zone diary metric-catalog synced-at]` —
     typed fields per the metric table; BP from `meta.composite` only (ignore
     composites on non-composite metrics — a weight entry was seen carrying a
     stale composite); Oura timeless values stamped at the day's Sleep entry
     `meta.wakeup_time` (`:wake-time`) else local midnight (`:day-start`), **with
     the rationale in a comment**; unknown metric → label + raw only; unit not
     in the field's enum → no typed value, log a warning.
   - `diary->nutrition-day [user-id diary synced-at]`.
   - `diary->docs [ctx-free args]` — everything for one day.
   Raw payloads are stored with `pr-str`.
5. **Tests** — `test/tech/jgood/gleanmo/test/cronometer/transform_test.clj`,
   **required from `test/tech/jgood/gleanmo/test.clj`** (otherwise silently
   skipped). Synthetic fixtures under `test/resources/` or inline. Cover:
   per-100 g vs recipe scaling; summed food-log nutrients equal a fixture's
   totals; BP composite → one row with both values and units; stale composite
   on weight ignored; lbs stored as lbs; Oura `:wake-time` and `:day-start`;
   unknown metric; unmapped unit; deterministic ids (same input → same ids);
   every produced doc validates against its malli schema; nutrient-table keys
   unique.
6. Local check (not committed, user-run): a `comment` block or dev task that runs the
   transform over `cronometer_data/*` and prints summed macros next to the
   diary `summary` for each dumped day — they matched exactly in the probe.

Validate: `just lint-fast`, `clj -M:dev test cronometer.transform-test`,
`just check`.

### Slice B — client, sync, dev task

1. **Client** — promote `dev/tasks/cronometer.clj`'s HTTP layer to
   `src/tech/jgood/gleanmo/cronometer/client.clj`: `login!`, `get-diary`,
   `get-foods`, `get-metrics`, `get-nutrient-definitions`. Session held in an
   atom keyed by user id (re-login on restart and once on auth failure).
   Every function takes the user's credentials as an argument; the client
   never reads config. Keep
   `:cookie-policy :standard`, the Dart user-agent, `text/plain` content type.
   Never log the password or session key. Sequential requests with a short
   pause between days (start at 250 ms).
   The dev probe task stays, rewritten to call this client but keeping its
   file-cached session (CLI runs are short-lived; logins are rate-limited).
   **Credentials** — per the Credentials section: `crypto.clj`
   (`encrypt` / `decrypt`, unit-tested round trip, wrong key fails, nonce never
   reused), the `cronometer-connection` schema, `:gleanmo/credentials-key` in
   `resources/config.edn`, and `evict-entity!` in `db/mutations.clj`.
   Queries: `cronometer-connection-for-user` and `users-with-cronometer-connection`.
2. **Queries** (`db/queries.clj`) — `cronometer-ids-for-day [db user-id
   entity-type day-str]`: equality-bound on `user/id`, `::sm/type`,
   `:cronometer/day`, returning ids of non-deleted docs. Used to find rows that
   vanished from a re-pulled day. Reuse `fetch-entities-by-ids` for existing
   docs.
3. **Sync** — `src/tech/jgood/gleanmo/cronometer/sync.clj`:
   `sync-days! [ctx user days]`, oldest day first. Per day:
   1. `get-diary`; collect serving `foodId`s; `get-foods` for them; then
      `get-foods` again for any recipe ingredient ids not yet fetched
      (recurse until closed). Always refetch — custom recipes get edited.
   2. Metric catalog: fetch once per sync run.
   3. Build docs with the transform, using the user's `user/time-zone`.
   4. Against a **fresh** `xt/db`: existing ids → `:update` ops, new →
      creates with preset `:xt/id` (`mutations/write-entities!`). On update,
      send `:db/dissoc` for optional domain keys present in the stored doc but
      absent from the new one, so a field removed upstream does not linger.
      Leave `::sm/created-at` alone.
   5. `food-log` / `biometric-log` ids returned by `cronometer-ids-for-day` but
      not produced this run → soft delete (deleted in Cronometer).
   6. One `write-entities!` per day, so a day lands whole or not at all.
   Returns a per-day summary (counts created/updated/deleted, warnings).
4. **Dev tasks** — `clj -M:dev cronometer-connect --target dev --email
   <gleanmo email>` encrypts `CRONOMETER_EMAIL` / `CRONOMETER_PASSWORD` (env,
   then `config.env`) into that user's connection, so the sync is testable
   before the Slice C form exists. `clj -M:dev cronometer-sync --target dev
   --email <gleanmo email> --days 7` syncs from the stored connection. Both
   follow `tasks.task-entities` for target/email handling. Locally the dev
   server must be stopped (RocksDB lock).
5. **Tests** — `test/tech/jgood/gleanmo/test/cronometer/sync_test.clj` with
   `test-xtdb-node` (see `api_test.clj`) and the client stubbed
   (`with-redefs`) over synthetic fixtures: first run creates; second identical
   run writes no new docs; a serving removed from the fixture is soft-deleted;
   a changed recipe updates the food doc. Required from `test.clj`.

### Slice C — API exposure, status page, schedule

1. **Agent API** — `api/access.clj` groups: `{:key :nutrition, :label
   "Nutrition", :types [:food :food-log :nutrition-day], :read-only true}` and
   `{:key :biometrics, :label "Biometrics", :types [:biometric-log],
   :read-only true}`. Add `:read-only` support: `can?` refuses `:write` for
   those types, the token form offers only none/read for them, and
   `api_test.clj` asserts a write grant on them is rejected. Sync-owned data
   must not be agent-editable — the next sync would overwrite it.
   `doc->json` omits `:cronometer/raw` (large, noisy). `/types` for the
   nutrition group carries the nutrient table (`key`, `label`, `unit`,
   `category`) so agents can read units for nutrient maps.
2. **Status page** — `src/tech/jgood/gleanmo/app/cronometer.clj`, route
   `/app/cronometer`, linked from the user settings page: last
   `cronometer/synced-at`, counts per type over the last 7 days, the latest
   day's macros from food-log sums, and a `biff/form` POST "Sync now" that runs
   `sync-days!` for 7 days and **303s back** (no render from the request's
   `:biff/db`). The page also carries the connect form (Cronometer email +
   password; the password field never re-renders a stored value), a
   "Disconnect" action that evicts, and the connected email. Every action
   writes and 303s. With no `:gleanmo/credentials-key` configured, the page
   says so and offers no form. Any queries it needs go in `db/queries.clj`.
3. **Schedule** — `worker.clj`: a `daily-at-utc` task syncing the last 7 days
   for every user with a connection, sequentially, with a pause between users;
   one user's failure is logged and does not stop the others. No-op when the
   key is absent. Idempotency makes a double fire during a deploy overlap
   harmless ([077-scheduled-work-multi-instance.md](./077-scheduled-work-multi-instance.md)).
4. **E2E** — `e2e/scripts/test-cronometer.ts` + `"test:cronometer"` in
   `e2e/package.json`: the status page renders its empty/unconfigured state.
   It cannot exercise a live sync in CI (no credentials) — say so in the test.
   Add the route to `e2e/scripts/manifest.ts`. Follow the UI rules
   (`[BP-WF-VISUAL]`, series tick before and after).

### Dev fake API (added 2026-10-12)

A local fake of the mobile API, served by the dev server, so the sync runs
end to end in dev and e2e with no credentials and no traffic leaving the
machine.

- **Code** — `dev/tech/jgood/gleanmo/fake_cronometer.clj` (routes) and
  `fake_cronometer/data.clj` (pure generator). Mounted at `/dev/cronometer`
  among the API routes (no session, no CSRF), loaded the way `e2e-auth` is
  (`dev-modules` in `gleanmo.clj`).
- **Off in prod, twice over** — `dev/` is not on the uberjar classpath, and
  every route 404s unless `:gleanmo.cronometer/fake` is true. That key is hard
  `false` under the prod profile and set in dev by `FAKE_CRONOMETER=true` in
  `config.env`. Tests read `config.edn` under `:prod` with the env var set and
  assert it stays false.
- **Base URL** — `client/connection` takes `:base-url` (default
  `https://mobile.cronometer.com`); `connection/client-connection` passes
  `<biff/base-url>/dev/cronometer` when the flag is on. The client still never
  reads config. The status page shows a "Dev fake" notice while it is on.
- **Behaviour** — any email logs in with any password except `invalid`; the
  account id is a hash of the email. Login with a non-null `timezone` gets a
  400, so a client regression on that hazard fails here first.
  `POST /dev/cronometer/reject-next-session {"email": ...}` fails the
  account's next request as an expired session, to exercise re-login.
- **Data** — invented throughout; nothing from `cronometer_data/`. It is
  deterministic per (account, day), and neighbouring days differ. Each day
  has 16 stock foods with per-100 g nutrients (including the uncatalogued ids
  -203 and 652), a custom yogurt-bowl recipe whose granola ingredient is
  itself a recipe, and servings across four meals, one with a `nil` offset. It
  also has a Sleep entry with `meta.wakeup_time`, Oura per-day readings with
  no time, HRV, weight in lbs, a blood pressure composite, glucose in mg/dL,
  body fat and mood on some days, and an Oura Exercise entry, plus
  `summary`, `template` and `info.complete`. Meals and manual readings fall
  after the wake time. `summary.consumed` is the servings summed by the Probe
  Findings scaling rule, computed apart from the transform. `get_biometrics`
  covers a range and converts lbs/kg/st and mg/dL/mmol/L. The
  `get_nutrition_scores` response shape is invented: nothing in the sync
  reads it.
- **CI** — `validate.yml` writes a throwaway `CREDENTIALS_KEY` and
  `FAKE_CRONOMETER=true` into the runner's `config.env`, so
  `test:cronometer` connects, syncs and checks counts and macros. Locally the
  sync step is skipped when either is missing. Under `CI` it fails instead,
  so the coverage cannot quietly lapse.
- **Use it** — add `FAKE_CRONOMETER=true` and a dev `CREDENTIALS_KEY` to
  `config.env`, restart `biff dev`, connect any credentials on
  `/app/cronometer`, press Sync now.

### Rollout (user-performed)

- Generate a key (`openssl rand -base64 32`) and add it as `CREDENTIALS_KEY`
  to the App Platform environment. Local `config.env` gets its **own** key;
  dev and prod do not share one. Without it prod gets `nil` and the sync
  stays dormant.
- Keep a copy of the prod key outside App Platform (password manager).
  Losing it means reconnecting, not data loss.
- Merge to `main`; connect on `/app/cronometer`; trigger "Sync now" once;
  inspect via the agent API.
- Phase 4 (CSV backfill) follows as its own migration.

## Progress

- Checkout: worktree `dev-1`, branch `dev-1`. Slices A–C committed in
  `da73924`; the dev fake API is uncommitted (2026-10-12).
- Completed: Slices A, B and C as planned. Schemas, nutrient and metric
  tables, pure transform, client, AES-GCM credentials, sync, dev tasks,
  read-only API groups, `/app/cronometer`, daily task at 10:00 UTC, e2e test.
- Completed (2026-10-12): the dev fake API (see Dev fake API), with unit
  tests and e2e coverage of connect → Sync now.
- Remaining: user-run real-data check and live sync (see Rollout); Phase 4 CSV
  backfill.
- Validation (fake): `just check` adds no warnings; `just validate` 212 tests,
  0 failures; `just e2e-test-all` 25/25 with the fake on (sync of 7 days:
  64 food logs, 55 readings, 18 foods); series ticks `2026-10-12T19-12-51Z`
  (before) and `2026-10-13T13-56-42Z` (after).
- Blockers: none.
- Next action: rollout (see Rollout) — real-data check passed 2026-10-13;
  user sets the prod `CREDENTIALS_KEY`, merges to `main`, connects on
  `/app/cronometer`, and presses Sync now.

### Implementation decisions (2026-10-12)

- **Unchanged rows are not rewritten.** A re-sync compares each document,
  without `synced-at`, to the stored one and skips equal ones. Without this a
  daily 7-day re-pull adds a version of every row (raw payloads included) to
  XTDB history every day. `nutrition-day` is the exception: it is rewritten on
  every pull, and its `cronometer/synced-at` is the "last sync" the status
  page shows.
- **A soft-deleted row that reappears is restored** (`deleted-at` dissoc'd).
- **The ciphertext is bound to its user.** The user id is the AES-GCM
  associated data, so a ciphertext copied to another user's document does not
  decrypt.
- **One sync per user at a time**, guarded in process
  (`sync-recent-locked!`). Not safe across instances — same caveat as the
  session atom (077).
- **Read-only groups are enforced in `access/can?`**, so a stored write grant
  on them (a tampered form) still reads as read. `/types` reports their fields
  as not writable.
- **Timeless readings take the wake time whenever the day has a Sleep entry**,
  not only for Oura sources, as the `:day-start` rule implies.
- **Connecting does not log in.** Credentials are checked by the first Sync
  now; a failure shows as "sync stopped" with details in the server log.
- **Unverified:** a serving on a recipe's `g` measure (type `Recipe`, value =
  recipe weight). The scaling rule treats `grams` as a serving count, which
  the probe confirmed only for the "full recipe" measure.

## Validation

- [x] Phase 0: the phone app stays authenticated after a CLI login.
- [x] Client fetches a day of diary, foods, nutrition scores, and a biometric
      range against the live API.
- [x] Per-serving nutrient math reproduces Cronometer's day totals (three days,
      recipe included).
- [x] Slice A: schemas registered; transform tests pass over synthetic
      fixtures; every produced doc validates; `/types` describes the new types.
- [x] Slice B: sync is idempotent (second run writes nothing new); a serving
      deleted in Cronometer is soft-deleted on re-sync; recipe ingredients
      resolve to `food` docs with tags. (Stubbed client, synthetic fixtures.)
- [x] Blood pressure lands as one `biometric-log` with both values and units.
- [x] Timestamps use `user/time-zone`; Oura per-day values land on wake time.
- [x] Slice C: nutrition and biometrics groups are read-only to agents;
      `cronometer/raw` is not in API output; nutrient table is in `/types`.
- [x] Status page renders; "Sync now" 303s back and the counts update.
      (Unconfigured state by e2e; connected state by `app.cronometer-test`.)
- [x] Stored connection holds no plaintext email or password; disconnect
      leaves no document history (`xt/entity-history` empty).
- [x] New test namespaces are required in `test/tech/jgood/gleanmo/test.clj`.
- [x] `just lint-fast`, `just check`, `just validate`, `just e2e-test-all` pass.
- [x] Dev fake: deterministic per (account, day), days differ, summary
      totals match summed servings through the transform, every doc
      validates, routes 404 with the flag off and the prod profile forces it
      off (`cronometer.fake-test`).
- [x] Dev fake: e2e connects, survives one rejected session, syncs 7 days,
      counts and macros fill in (`test:cronometer`, CI included).
- [x] Slice A step 6: transform over `cronometer_data/` matches the diary
      summaries (`tmp/cronometer_check.clj`, run 2026-10-13 at the user's
      request: energy and macros match exactly on all three probe days, no
      warnings, every produced doc validates). The script predates
      activities, so real Exercise entries are first exercised by the live
      sync.
- [ ] Live sync against the real account (user-run).
- [ ] Phase 4: CSV backfill imported without errors.

## Scope

### In scope

- Read-only import from Cronometer (API for recent days, CSV for history).
- Food, food logs with full nutrient profiles, recipes with ingredients.
- All diary biometrics, including Oura values arriving through Cronometer.
- Daily nutrition summaries.
- Cronometer lineage and raw payloads on every imported entity.
- Agent API exposure.
- A minimal status page proving the sync works.

### Out of scope

- Writing back to Cronometer (`add_serving`, `add_food`); Cronometer stays the
  logging interface and the source of truth.
- Fasting analysis, nutrition scores beyond what the summary carries, food
  search.
- Nutrition charts/visualizations (defer to
  [016-generic-viz.md](./016-generic-viz.md)).
- A direct Oura integration — Oura data arrives through Cronometer.
- Relating biometrics to `symptom-log` vitals.
- Time-zone correction tooling (see Notes, 2026-10-12).

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
- Related work units: [078-data-export.md](./078-data-export.md) (how this data reaches
  the coach agent), [016-generic-viz.md](./016-generic-viz.md) (future charting),
  [077-scheduled-work-multi-instance.md](./077-scheduled-work-multi-instance.md) (if
  the sync becomes a scheduled task, it inherits that unit's singleton
  problem).

## Open Questions

- [x] Unit convention — decided 2026-10-12: value + sibling `<field>-unit`
      everywhere, as entered, no conversion (AGENTS.md schema conventions).
- [x] Credentials — decided 2026-10-12 (revised the same day): per-user,
      AES-GCM encrypted in XTDB under `CREDENTIALS_KEY`. Supersedes "env
      secrets for one configured owner".
- [ ] Key rotation. Deferred by the user 2026-10-12. The stored key version
      allows decrypt-with-old / re-encrypt-with-new; needs a second key slot
      in config and a re-encrypt pass (which must evict the old versions).
- [ ] Session tokens under more than one backend instance — listed in
      [077-scheduled-work-multi-instance.md](./077-scheduled-work-multi-instance.md).
- [ ] Does sending `firebaseToken: ""` disturb push notifications on the phone?
- [ ] What does a Cronometer CSV export look like, and do its rows carry ids
      matching the API's `servingId`?
- [ ] How large is the historical dataset?
- [x] Recent-days sync — planned as both: "Sync now" on the status page and a
      daily `daily-at-utc` task (Slice C).
- [ ] Sleep as an interval (`beginning`/`end`) rather than a reading.

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

### Probe session (2026-10-12)

Decisions from reviewing the dumps:

> "I'm leaning towards maybe we keep the nutritional summary. Can't hurt to just
> have it even if we don't utilize it."

> "I feel like symptom tracking is about monitoring health events and
> biometrics are a regularly tracking thing. Also the only thing feeding this
> will likely be cronometer. Let's just use consistent naming and units so the
> values are comparable but they are separate."

> "I intend to have everything flow through cronometer for the foreseeable
> future."

> "I want to preserve all the ingredients of the recipe so that I can do
> potential analysis on things like vegan or whole food compliant."

> "I'm not sure I like storing the blood pressure as two different items. What
> if a biometric just has optional fields for everything? Then we can store
> arbitrary attributes and units."

Interpretation at the time: a `values` map on `biometric-log` (later replaced
by flat typed fields — see below); ingredients as `food` references so tags
resolve.

**Credentials (2026-10-12, before implementation).**

> "Ok we can store the credentials in the database encrypted via a credentials
> key supplied as an envvar. Not sure what will happen if we have to rotate
> that. We can add it to the roadmap to deal with later though."

> "Also the session token you plan to have live in memory and that's fine for
> our single instance setup but if we eventually have multiple instances that
> won't work."

Asked first whether credentials would be plaintext in the database (no), then
how env secrets would work for more than one user (they don't). Outcome: the
Credentials section. Agent API exposure (Slice C step 1) confirmed in scope
the same day.

**Time-zone correction (idea, not scheduled):**

> "I think in general a user will probably change the time zone before it
> becomes problematic but it might be nice to have some convenienc for this.
> Maybe we can have the client notice when a time zone changes and prompt the
> user? Perhaps we can notice if the time zone changes between cronometer syncs
> and prompts the user. Perhaps we can have a "correct time zone" for an
> interval that works for this kind of imported data and other entities. I
> don't think we should worry about it now but we can add it to the roadmap."

Context: imported times are read in `user/time-zone`, so a day logged while
travelling lands hours off until corrected. Raw `cronometer/day`, `time` and
`offset` are kept on every row, so a "correct time zone for an interval" action
is a recompute. Cronometer's login response reports the account zone, so a
change between syncs is detectable — a cheap first signal. The interval
correction would apply beyond this integration (any entity with timestamps), so
it may outgrow this unit.

**Unit convention question:** existing schemas use two conventions. Hand-entered
values keep the unit the user chose in a sibling enum
(`exercise-line/weight` + `weight-unit`, `distance` + `distance-unit`,
`symptom-log/temp` + `temp-unit`). Fixed-unit values put the unit in the name
or imply it (`exercise-line/duration-seconds`; `symptom-log/bp-systolic` is
implicitly mmHg). Imported biometrics are converted to one canonical unit and
keep the as-entered value in raw, which matches the second convention.

**Biometric shape and units (later the same day).** The `values` map and a
`biometric` definition entity were proposed and dropped:

> "Do we need biometric and biometric value? If we are just getting these from
> diary then should we just have a biometric-log? … it could have optional
> fields for all the biometrics we support"

> "I get that the baked in units are for metrics that would _never_ have
> another unit but it feels inconsistent to me. Is it really the right choice?"

Outcome: flat typed fields on `biometric-log`; a sibling unit field on every
measured value, even single-member enums, recorded as a schema convention in
AGENTS.md. The user had chosen `exercise-line/weight` + `weight-unit`
deliberately. Symptom-log vitals gain unit fields in
[084-symptom-blood-glucose.md](./084-symptom-blood-glucose.md).

