---
title: "Goals dashboard"
status: active
description: "Implement the approved dashboard with reusable numeric types, reading positions, goal storage, and scoped progress queries."
created: 2026-09-12
updated: 2026-09-14
tags: [goals, visualization, schema, reading]
priority: medium
---

# Goals dashboard

## Intent

Implement the approved goals dashboard using actual user records.
Support duration totals, counts, best performances, and explicit book completion.
This document is the implementation handoff; the planning session must not implement it.

## Design reference and precedence

- Approved mock: [version 10](../mockups/2026-09-motivating-dashboards/codex/mockup-codex-10-goals-dashboard.html).
- Mock commit: `d6f1e20`; includes solid and dotted book-position connections.
- [Version 10 semantics](../mockups/2026-09-motivating-dashboards/codex/goals-10-data-contract.md).
- [Version 09 timing semantics](../mockups/2026-09-motivating-dashboards/codex/goals-09-data-contract.md).
- [Version 08 aggregation and comparison details](../mockups/2026-09-motivating-dashboards/codex/goals-08-data-contract.md).
- [Browser checks](../mockups/2026-09-motivating-dashboards/codex/check-goals-10.cjs) and adjacent `review-shots/goals-10-*.png` provide examples, not production tests.

This work unit supersedes earlier contracts where they conflict, especially goal modes, numeric types, and reading positions.
Version 10 controls visual intent; use the application's shared components and ECharts implementation patterns.
Do not copy fictional fixtures, fixed dates, synthetic coverage, or the mock's JavaScript calculation layer into production.
This work unit absorbs [055](./archived/055-reading-log-pages.md).

## Scope

Include schema additions, goal creation/editing/archiving, authenticated dashboard, reading-position entry, and regression checks.
Preserve existing attributes and historical documents; no data backfill or migration is required.
Do not implement speed multipliers, edition conversion, nonfiction filters, arbitrary attribute expressions, monthly recurrence, or reading-speed goals.
Do not add stored progress totals, completion flags, forecasts, or chart series to goal documents.
Do not deploy, push to main, run production migrations, or access live data without authorization.

The previous visual-series waiver covered standalone mockups only.
Before application UI edits, follow AGENTS.md's baseline workflow with the user-run dev server.
The following implementation steps are ordered; complete and validate each before advancing.

## Specification

### 1. Register numeric aliases and support them throughout CRUD

Add these entries to `src/tech/jgood/gleanmo/schema.clj`:

```clojure
:positive-int    [:int {:min 1}]
:nonnegative-int [:int {:min 0}]
```

Keep existing `:int` attributes unchanged.
Fields declare the alias keyword, rather than embedding constrained vectors.
The current `schema.utils/determine-input-type` cannot dispatch constrained primitive vectors or `[:and ...]` fields correctly.
Its relationship detection also expects `[:set :entity/id]`, without a properties map inside the set.

Implement in this order:

1. Register aliases and recognize them in `schema/utils.clj` without losing their constraint identity.
2. Extend `crud/forms/inputs.clj`, reusing integer inputs with `step=1` and `min=1` or `min=0`.
3. Extend `crud/forms/converters.clj`, reusing integer parsing and preserving explicit zero.
4. Extend `crud/views/formatting.clj`, reusing integer display and the existing missing-value placeholder.

Do not change the behavior of plain integers, existing enums, or relationship sets.
Malli must reject invalid values independently of HTML input constraints.
Give new fields readable `:crud/label` metadata and deliberate `:crud/priority` values.

### 2. Add reading positions and explicit exercise duration

All fields in this table are optional:

| Entity | Attribute | Type |
|---|---|---|
| book | `book/total-pages` | `:positive-int` |
| book | `book/total-chapters` | `:positive-int` |
| book | `book/audiobook-duration-seconds` | `:positive-int` |
| reading-log | `reading-log/start-page` | `:nonnegative-int` |
| reading-log | `reading-log/end-page` | `:nonnegative-int` |
| reading-log | `reading-log/start-chapter` | `:nonnegative-int` |
| reading-log | `reading-log/end-chapter` | `:nonnegative-int` |
| reading-log | `reading-log/start-audio-position-seconds` | `:nonnegative-int` |
| reading-log | `reading-log/end-audio-position-seconds` | `:nonnegative-int` |
| exercise-line | `exercise-line/duration-seconds` | `:number` |

For example:

```clojure
[:reading-log/end-page
 {:optional true :crud/label "Ending page" :crud/priority 6}
 :nonnegative-int]
```

Allocate priorities within each existing schema; the example number is not a mandated global ordering.
Expand both existing format enums, `book/formats` and `reading-log/format`, with `:ebook`.
Preserve `reading-log/finished?`, all interval fields, and existing metadata exactly.
No new fields are needed on meditation or meditation-log.
Exercise duration accepts positive finite numbers, including fractional seconds; validate without an inline `[:and ...]` field type.

Starting and ending positions are independently optional, including on a log with several different position measures.
Do not gate these fields by format, cap them at book totals, require paired endpoints, or enforce increasing positions.
The user manages edition differences and corrections; ending positions can decrease.
No position is synthesized from interval duration or another measure.
Blank optional fields remain absent; zero is a real position.

Provide shared duration input/display behavior for the three audiobook fields.
Store integer seconds; accept nonnegative integer seconds or `H:MM:SS` with minutes/seconds in 00–59.
Display `H:MM:SS`, allowing hours above 23; reject malformed or fractional input and enforce each field's minimum.
Use field metadata such as `:crud/duration-format :hms` to select this behavior, not duplicated book-specific parsers.
Keep exercise duration as a seconds input with fractional support.
Update relevant CRUD forms, list formatting, and custom exercise-line entry so these fields can actually be recorded.
Preserve changed-field highlighting and do not clear untouched optional fields during unrelated edits.

### 3. Define goal storage and validation

Create `schema/goal_schema.clj` and register `:goal/id :uuid` and `:goal` in `schema.clj`.
Use a closed map with standard id, type, deletion, creation, and user ownership fields in existing order.
Do not add legacy or Airtable metadata to this new app-created entity.

| Attribute | Shape / requirement |
|---|---|
| `goal/label` | Required `:string`; nonblank |
| `goal/source` | Required enum of the source entities in the matrix below |
| `goal/measure` | Required enum `:duration :records :reps :attempts :weight :book-completion` |
| `goal/aggregation` | Required enum `:total :best :completion` |
| `goal/target` | Optional `:number` in schema; required positive finite number except for completion |
| `goal/timing` | Required enum `:dated :weekly :open-ended` |
| `goal/starts-on` | Required `:local-date`; explicit counting baseline |
| `goal/ends-on` | Optional `:local-date`; required for dated, forbidden for open-ended, optional stop date for weekly |
| `goal/time-zone` | Required `:string`; valid IANA timezone |
| `goal/threshold-step` | Optional positive finite `:number`, only for numeric goals |
| `goal/progress-measure` | Optional enum `:pages :chapters :audio`, only for book completion |
| `goal/even-pace-enabled` | Optional `:boolean`; book chart preference |
| `goal/archived` | Optional `:boolean` |

Optional relation filters use plain `[:set :related-entity/id]`:
`goal/project-ids`, `goal/book-ids`, `goal/meditation-ids`, `goal/habit-ids`, `goal/exercise-ids`.
An absent filter means all eligible records; reject an explicitly empty set.
Accept only the filter corresponding to the source, and verify ownership of selected records.
Book completion requires exactly one book, omits target and threshold, and allows dated or open-ended timing only.
Do not persist a duplicate unit field; derive the unit from the measurement definition.

Use a small data-driven measurement registry with this initial allowlist:

| Sources | Measure / aggregation | Relation filter | Unit |
|---|---|---|---|
| project-log, reading-log, meditation-log | duration / total | matching project, book, meditation filter | seconds |
| meditation-log | records / total | meditation filter | sessions |
| habit-log | records / total | habit filter | logs |
| exercise-session, boulder-session | records / total | none | sessions |
| boulder-attempt | attempts / total | none | attempts |
| boulder-attempt | duration / total | none | seconds |
| exercise-line | reps / total | exercise filter | reps |
| exercise-line | weight / best | exercise filter | kg |
| exercise-line | duration / best | exercise filter | seconds |
| reading-log | book-completion / completion | exactly one book | none |

Total goals support dated, weekly, and open-ended timing.
Best goals support dated and open-ended timing, without linear improvement forecasts.
Count/repetition targets and thresholds must be integers; duration and weight targets can be fractional.
End dates cannot precede start dates.
Apply validation at the shared write boundary for goal and exercise duration changes, including updates, not only in browser forms.
Return useful field errors; do not wrap the entire map in a form the CRUD schema parser cannot understand.

### 4. Implement scoped queries and pure progress calculations

Keep orchestration and pure calculations in dedicated `goals` namespaces; keep database operations in `db/queries.clj` and `db/mutations.clj`.
Public query inputs describe user, source, relation scope, date range, timezone, and measurement intent.
Follow AGENTS.md's query-shape rules, including scan-then-pull for paged goal lists and parent-bound exercise reads.
Do not load all user history and filter it in application code.
Use targeted projections for full chart windows; a chart genuinely needs its interval's measurements, not just its last page.
Batch compatible requests for several goals instead of repeating the same source scan for every table row.
Apply ownership and resolved visibility to both logs and related entities.

Use one explicit cutoff for every dashboard region: local midnight starting today, matching the mock's completed-day accounting.
Exclude open timers and records beyond that cutoff; label the cutoff in the UI.
Duration totals clip completed intervals to the goal range, split at local midnight, and merge overlaps within the selected scope.
Use local calendar boundaries rather than fixed-second approximations across DST.
Session/log counts use their beginning/timestamp; exercise lines use their parent set's beginning.
Habit records count once even when several selected habits match the same record.
Boulder attempts sum attempts, defaulting to one when absent; laps do not multiply attempts.
Exercise best weight requires at least one rep, converts known units to kg, and excludes missing units.
Exercise best duration uses the new explicit line field, never its enclosing set's interval.

Weekly totals reset Monday, with no surplus carry-forward.
Use full targets for partial first/last weeks and identify those periods as partial; default creation to complete calendar weeks.
Open-ended totals count from starts-on, never reset, and have no required rate or even-pace difference.
Use the version 08 formulas for applicable numeric goals and guard zero elapsed days, zero totals, expired goals, and reached targets.
Use bounded last-28-day activity for the open-ended supporting panel.
Keep missing source coverage distinct from confirmed zero activity.

Book calculations:

- Use completed logs for the selected book, from the counting start through the cutoff, ordered by end timestamp and id as tie-breaker.
- The first eligible log with finished? true supplies completion and its date; a deadline determines on-time versus late completion.
- For completion goals, continue reading logs after a deadline so late completion remains visible.
- Removing or correcting the finished flag recalculates completion; there is no copied goal completion attribute.
- Select the latest nonmissing ending position for each measure, not its sum or historical maximum.
- Compare only against the matching book total; missing totals show raw positions without a percentage or total line.
- Missing positions produce no dots; solid lines connect adjacent measured logs, and dotted lines bridge intervening logs missing that measure.
- Never extrapolate beyond the last recorded position or mark completion from 100%.
- Changing a book total recalculates percentages without changing recorded positions.

Prior-year comparison must use the version 08 calendar alignment rules and the same selected scope and measure.
Implement the renderer/calculator for known-complete comparison windows, with synthetic fixtures for validation.
Coverage metadata entry and historical backfills are deferred: if coverage is not established, show "No comparable history" and omit comparisons.
Do not infer complete coverage from the earliest log or manufacture prior-year data.

### 5. Build the goal editor and dashboard

Add authenticated `GET /app/goals` and link it from the existing navigation.
Register the goal entity and CRUD routes following the complete new-entity checklist in AGENTS.md.
Provide create, edit, archive, and delete actions using the existing form/mutation conventions.
Use a focused editor: source, supported measurement, optional relation selection, target, timing, dates, and timezone.
Use the registry to show valid choices and explain validation errors.
Book completion hides numeric target, requires one book, and offers the chart measure preference.
Use existing Choices.js relationship inputs and shared components; do not copy bespoke widgets from the mock.
Successful mutations redirect to a fresh GET so all dashboard regions use the new database snapshot.

Reproduce the mock's sortable/searchable bounded table, independent measurement/timing filters, and selected-goal card.
The card contains one main chart plus activity and comparison/recent-reading panels, all scoped to that goal.
Preserve the page/chapter/audio selector, per-goal saved preference, optional deadline guide, recent log trail, and What counts disclosure.
The guide is optional, starts at zero on the goal start, and never implies a completion event or predicted finish date.
For a missing book total, omit the guide; after a passed deadline show overdue/late state without a negative required rate.
Persist book measure and pace preferences through the normal validated goal mutation path; do not render from a stale snapshot.
Prior-year checkboxes can remain page-session preferences, matching the mock.
Reuse shared ECharts loading and responsive behavior; preserve desktop chart fit and mobile table scrolling.
Empty goals offer creation; unavailable measurements explain what is missing rather than rendering NaN, Infinity, or invented zero percentages.

## Validation

Follow AGENTS.md's validation levels, test namespace registration, and E2E discovery conventions.
Add tests for behavior rather than snapshots of implementation structure.

- [ ] Aliases: positive rejects zero/negative/fractional; nonnegative accepts zero and rejects negative/fractional; existing documents still validate.
- [ ] CRUD: aliases render/convert/format correctly; blank optional fields clear correctly; untouched fields survive edits; relationship sets still resolve.
- [ ] Duration input: integer seconds and H:MM:SS round-trip, including hours above 23; malformed values fail without writes.
- [ ] Reading: all measures coexist; missing endpoints, backward positions, and positions above totals remain valid; ebook saves and reloads.
- [ ] Goals: validate the allowlist, target types, empty/wrong-owner filters, single-book completion, dates, timezone, and archive behavior.
- [ ] Calculations: overlap union, DST split, weekly resets, partial weeks, zero/future/ended/reached states, rep sums, attempts defaults, and unit conversion.
- [ ] Book completion: 100% without finished remains incomplete; finished below total completes; corrections recalculate; late completion remains visible.
- [ ] Book charts: latest rather than maximum, missing values/totals, solid versus dotted links, unchanged point counts, and independent measures.
- [ ] Queries: owner isolation, visibility, parent scope, and bounded chart/history requests; inspect representative query plans using local test data.
- [ ] Comparisons: unknown coverage shows unavailable; known fixtures align windows and reconcile totals for each selected year.
- [ ] Register new unit namespaces in `test/tech/jgood/gleanmo/test.clj`.
- [ ] Add `e2e/scripts/test-goals.ts` and package script `test:goals`; extend reading CRUD tests for positions and completion.
- [ ] E2E: create/edit/archive a goal, change its source data, verify the redirected dashboard, filter/select goals, and persist book preferences.
- [ ] Add `/app/goals` to `e2e/scripts/manifest.ts` and smoke navigation coverage.
- [ ] Capture before/after desktop and mobile screenshots; compare against version 10 and inspect chart labels, keyboard controls, and table overflow.
- [ ] Run relevant namespace tests, `just validate`, reading/goals E2E, and required visual-series captures before requesting an implementation commit.

## Implementation status (2026-09-14)

A first implementation exists and awaits user review; expect iteration.

- Per-goal routes live under `/app/goal/:id/...`.
  Reitit rejects `/app/goals/new` beside `/app/goals/:id`.
- Pure calculations are in `goals/calc.clj`; orchestration is in `goals/dashboard.clj`.
  The registry is in `goals/registry.clj`, and write rules are in `schema/rules.clj`.
- Comparisons always report "No comparable history", because no coverage metadata exists.
  The calculator and a known-coverage renderer are tested with fixtures.
- Not done: query-plan inspection with debug logging, and keyboard navigation of the activity cells.

## Review and dogfood feedback

This is a running list from user review and daily use.
Resolve each item, or move it to another work unit, before archiving this unit.

- [ ] Edit placement.
  The user looked for Edit on the table row, with a right-click, and at the top right of the selected-goal card.
  Candidate: move Edit · Archive · Delete to the top right of the card.
  A per-row edit control is a possible later addition.
- [ ] Count today.
  Include today's records in totals and the chart.
  Keep the rates on completed days, and label them so (for example, "per completed day").
  This changes the one-cutoff rule in the specification above.
- [ ] Duplicate entities.
  "Pullup" and "Pullups" are separate exercises.
  Workaround: select both exercises in the goal filter.
  The fix is tracked in [042](./042-entity-merge.md).
- [ ] Grouped measurement select.
  The editor's `<optgroup>` headings are a new pattern in the app, and the user liked them.
  Consider them for other long fixed lists.
- [ ] Hands-on familiarization exercises, deferred until the dashboard stabilizes.
  Outline: read the registry; predict `calc/numeric-progress` output in the REPL; inspect real `dashboard/dashboard` data; implement "count today" with the test first.
- [x] Time zone default.
  It works as designed; the account had an unexpected time zone set.

## Context: implementation entry points

- Schema registry: `src/tech/jgood/gleanmo/schema.clj`.
- Existing schemas: `schema/reading_schema.clj`, `schema/exercise_schema.clj`, `schema/meditation_schema.clj` under the same namespace root.
- Field parsing: `src/tech/jgood/gleanmo/schema/utils.clj`.
- CRUD: `crud/forms/inputs.clj`, `crud/forms/converters.clj`, `crud/views/formatting.clj`, and `crud/handlers.clj`.
- Reads/writes: `src/tech/jgood/gleanmo/db/queries.clj` and `db/mutations.clj`.
- Route examples: `app/book.clj`, `app/reading_log.clj`, and `app/exercise_line.clj`; registrations in `app.clj`.
- Navigation: `app/shared.clj` and `app/dashboards.clj`.
- Existing tests: `test/tech/jgood/gleanmo/test/crud/schema_utils_test.clj`, `crud/forms/inputs_test.clj`, `crud/forms/converters_test.clj`, and `db/queries_test.clj`.

Paths abbreviated above are relative to `src/tech/jgood/gleanmo/` unless prefixed with `test/`.
No code, schema, or database changes were made during creation of this plan.
