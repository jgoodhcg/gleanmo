---
title: "Cronometer Integration"
status: ready
description: "Pull food, recipes, biometrics and daily nutrition summaries from Cronometer into Gleanmo for the agent API — recent days over the reverse-engineered mobile API, historical backfill by CSV import"
created: 2026-04-24
updated: 2026-10-12
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
  the user's real dumps — use them for local verification only. Committed test
  fixtures are **synthetic**, hand-written to mirror the shapes in Probe
  Findings (this repo is public).
- The reference for every endpoint and payload is `dev/tasks/cronometer.clj`
  (working, verified against the live API) and
  [rwestergren/cronometer-api-mcp](https://github.com/rwestergren/cronometer-api-mcp)
  `client.py`.
- Never send a non-null `timezone` on login.

### Slice A — schemas and pure transform (no network, no DB writes)

1. **Schemas** — `src/tech/jgood/gleanmo/schema/cronometer_schema.clj` defining
   `food`, `food-log`, `biometric-log`, `nutrition-day` exactly as the Data
   Model section, standard field ordering (`xt/id`, `::sm/type`,
   `::sm/deleted-at`, `::sm/created-at`, `user/id`, domain fields, then
   `cronometer/*`). Register `:food/id`, `:food-log/id`, `:biometric-log/id`,
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
6. Local check (not committed): a `comment` block or dev task that runs the
   transform over `cronometer_data/*` and prints summed macros next to the
   diary `summary` for each dumped day — they matched exactly in the probe.

Validate: `just lint-fast`, `clj -M:dev test cronometer.transform-test`,
`just check`.

### Slice B — client, sync, dev task

1. **Client** — promote `dev/tasks/cronometer.clj`'s HTTP layer to
   `src/tech/jgood/gleanmo/cronometer/client.clj`: `login!`, `get-diary`,
   `get-foods`, `get-metrics`, `get-nutrient-definitions`. Session held in an
   atom (re-login on restart and once on auth failure); credentials from config
   keys `:cronometer/email` / `:cronometer/password` added to
   `resources/config.edn` as `#biff/secret CRONOMETER_EMAIL` etc. Keep
   `:cookie-policy :standard`, the Dart user-agent, `text/plain` content type.
   Never log the password or session key. Sequential requests with a short
   pause between days (start at 250 ms).
   The dev probe task stays, rewritten to call this client but keeping its
   file-cached session (CLI runs are short-lived; logins are rate-limited).
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
4. **Dev task** — `clj -M:dev cronometer-sync --target dev --email <gleanmo
   email> --days 7`, following `tasks.task-entities` for target/email
   handling. Locally the dev server must be stopped (RocksDB lock).
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
   `:biff/db`). Shown only when Cronometer config is present and the user is
   the configured owner (`:cronometer/user-email`, `CRONOMETER_USER_EMAIL` —
   the Gleanmo account that owns imports; it differs from the Cronometer
   login email). Any queries it needs go in `db/queries.clj`.
3. **Schedule** — `worker.clj`: a `daily-at-utc` task syncing the last 7 days
   for the configured owner; no-op when config is absent. Idempotency makes a
   double fire during a deploy overlap harmless
   ([077-scheduled-work-multi-instance.md](./077-scheduled-work-multi-instance.md)).
4. **E2E** — `e2e/scripts/test-cronometer.ts` + `"test:cronometer"` in
   `e2e/package.json`: the status page renders its empty/unconfigured state.
   It cannot exercise a live sync in CI (no credentials) — say so in the test.
   Add the route to `e2e/scripts/manifest.ts`. Follow the UI rules
   (`[BP-WF-VISUAL]`, series tick before and after).

### Rollout (user-performed)

- Add `CRONOMETER_EMAIL`, `CRONOMETER_PASSWORD`, `CRONOMETER_USER_EMAIL` to the
  App Platform environment (and local `config.env`). Without them prod gets
  `nil` and the sync stays dormant.
- Merge to `main`; trigger "Sync now" once; inspect via the agent API.
- Phase 4 (CSV backfill) follows as its own migration.

## Validation

- [x] Phase 0: the phone app stays authenticated after a CLI login.
- [x] Client fetches a day of diary, foods, nutrition scores, and a biometric
      range against the live API.
- [x] Per-serving nutrient math reproduces Cronometer's day totals (three days,
      recipe included).
- [ ] Slice A: schemas registered; transform tests pass over synthetic
      fixtures; every produced doc validates; `/types` describes the new types.
- [ ] Slice B: sync is idempotent (second run writes nothing new); a serving
      deleted in Cronometer is soft-deleted on re-sync; recipe ingredients
      resolve to `food` docs with tags.
- [ ] Blood pressure lands as one `biometric-log` with both values and units.
- [ ] Timestamps use `user/time-zone`; Oura per-day values land on wake time.
- [ ] Slice C: nutrition and biometrics groups are read-only to agents;
      `cronometer/raw` is not in API output; nutrient table is in `/types`.
- [ ] Status page renders; "Sync now" 303s back and the counts update.
- [ ] New test namespaces are required in `test/tech/jgood/gleanmo/test.clj`.
- [ ] `just lint-fast`, `just check`, `just validate`, `just e2e-test-all` pass.
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
- [x] Credentials in prod — env secrets for the single configured owner
      (Slice B/C). Per-user encrypted storage stays out of scope.
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

Interpretation: the `values` map on `biometric-log`; ingredients as `food`
references so tags resolve.

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

