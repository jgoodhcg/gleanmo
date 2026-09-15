---
title: "Goals dashboard"
status: active
description: "Implement the approved dashboard with reusable numeric types, reading positions, goal storage, and scoped progress queries."
created: 2026-09-12
updated: 2026-09-15
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

Capture one request instant and derive today and local midnight in each goal's saved time zone.
Totals, best performances, book positions, completion, and charts include today's eligible records up to that instant.
Point measurements use the half-open range ending at that instant; interval ends can equal it.
Exclude open intervals and intervals ending after the relevant cutoff before clipping or attributing their values.

Rates retain completed-day accounting at local midnight starting today.
Average, required rate, rate ratio, and even-pace difference use only the completed-day amount and completed-day denominator.
Intervals ending today cannot affect those calculations until the next local day, including their portions attributed to earlier days.
Required rate uses the remaining target at midnight and the calendar days remaining, including today.
Once today's records reach the target, suppress the required rate.
Label averages "per completed day" and explain the completed-day baseline for required pace.
Unknown coverage continues to suppress unsupported pace estimates.

The chart's partial-day endpoint uses the captured local time, rather than tomorrow's boundary.
Activity strips and recent panels include today within their existing 84-day and 28-day bounds.
Weekly progress resets at local Monday midnight; a goal becomes active on its start date.
Ended numeric goals remain clipped to their goal period, while book goals retain late completion.
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
Use bounded last-28-day activity, including today, for the open-ended supporting panel.
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

## Development test coverage (2026-09-15)

Build measurement integration coverage before further goal features or shipping.
Keep browser regression work and query-performance profiling in final validation.

- [x] Add independent expected results for every registered measurement.
  Require exact equality between fixture keys and registry IDs so registry additions fail until covered.
- [x] Run every supported measurement/timing combination through real mutations, queries, and dashboard orchestration.
  Current coverage includes 13 measurements and 36 timing combinations.
- [x] Include foreign-user history and owned history outside selected relations.
  Neither must change the selected goal results.
- [x] Delete contributing source records and verify recalculation.
  Assert totals, best performances, activity days, and book completion before and after deletion.
- [x] Register the integration namespace in the main test entry point.
- [x] Pass the focused measurement suite and full validation.
  Focused suite: 2 tests, 299 assertions.
  Full validation: 152 tests, 1,283 assertions; no failures or errors.
  No browser rerun was needed for these test and documentation changes.

Tests live in `test/tech/jgood/gleanmo/test/goals/measurements_test.clj`.
Run `clj -M:dev test tech.jgood.gleanmo.test.goals.measurements-test` during measurement development.
Use explicit expected values; do not calculate fixture expectations with production aggregation functions.
When adding a measurement, add its fixture and expected results in the same change.

This suite verifies source field mappings, relation scope, supported timing modes, and recalculation.
Existing calculation tests retain coverage for DST, boundaries, incomplete coverage, and rate formulas.
Existing browser tests cover shared editor and dashboard interactions.
Production-sized query plans, latency, and final browser regression checks remain required before shipping.

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

- [x] Edit placement.
  Edit, Archive, and Delete now share the selected-goal card's top-right header on desktop and mobile.
  A per-row edit control remains later work.
- [x] Count today.
  Totals and charts include today's eligible records through one captured request instant.
  Rates retain the completed-day cutoff in each goal's saved zone.
  The specification above replaces the earlier one-cutoff rule.
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

## Code review findings (Codex, 2026-09-14)

Reviewed `fd02b83` against this specification and the version 08–10 contracts.
Verification was static; no tests, E2E, server, REPL, or live database access ran.
Local clj-kondo matches CI (`v2026.07.24`).
Source paths below are relative to `src/tech/jgood/gleanmo/`.

### Bug

- [x] Enforce ownership and live-parent checks on source records.
  `db/queries.clj:1411` selects lines by set without checking the line's owner.
  `db/queries.clj:1392` delegates visibility to helpers that do not check parent ownership or exclude deleted parents (`db/queries.clj:133`).
  A foreign-owned line referencing an owned set contributes its measurements.
  An unfiltered project goal still counts logs after their project is deleted.
  Check candidate line ownership and validate referenced parents with bounded ID reads before aggregation.
  Cover foreign lines, foreign parents, deleted parents, and exercise-session ancestry in query tests.

- [x] Apply resolved log visibility to book-completion reads.
  `db/queries.clj:1472` only filters reading logs by owner, book, and log deletion.
  `goals/dashboard.clj:120` uses these records without checking their locations.
  A log at a sensitive or archived location can complete a book and expose its positions while numeric reading goals exclude it.
  Pass resolved settings into the book query and apply the same related-entity visibility policy before calculating completion or rendering logs.

- [x] Derive each goal's local accounting date from one captured instant.
  `app/goals.clj:881` obtains the account's date; `goals/dashboard.clj:51` reinterprets that date in every goal's zone.
  When the account and goal have different local dates, the goal includes an incomplete day or omits a completed day.
  For example, Tokyo can already be Monday while Los Angeles is still Sunday; the Los Angeles weekly goal resets early.
  Capture the request time once and derive each goal's completed-day boundary and labels in its saved zone.
  Test zones on opposite sides of midnight.

- [x] Exclude records whose interval ends beyond the accounting cutoff.
  `db/queries.clj:1468` defines openness only by a missing end.
  `goals/calc.clj:183` clips duration, and `goals/calc.clj:187` checks only the beginning for point measures.
  A session beginning yesterday and ending tomorrow already counts as completed; duration goals also credit its pre-cutoff portion.
  Filter interval records by completion at the explicit cutoff before attribution or clipping to the goal period.
  Keep this eligibility rule explicit when implementing the separately tracked change to count today.

- [x] Replace the three-day duration lookback with an overlap query.
  `goals/dashboard.clj:25` and `goals/dashboard.clj:45` add three days before the earliest requested date.
  `db/queries.clj:1455` then selects only beginnings within that range.
  A completed interval beginning earlier and ending inside the required range disappears before the correct clipping calculation can see it.
  Query intervals overlapping the requested range, with no assumed maximum duration, and add a query-to-calculator regression case.

- [x] Keep open-ended best performance out of additive recent-rhythm calculations.
  `goals/dashboard.clj:106` requests recent activity for every open-ended numeric goal.
  `goals/calc.clj:306` sums daily maxima; `app/goals.clj:647` calls the result “Added in the last 28 days.”
  Best weights of 80 kg and 90 kg on separate days therefore display 170 kg added.
  Dispatch supporting panels and summary statistics by aggregation as well as timing.
  Show the recent maximum for best goals, with wording that describes a performance rather than accumulation.

- [x] Allow the new exercise duration to be cleared through generic CRUD.
  `schema/utils.clj:70` makes only integer aliases and H:MM:SS fields clearable.
  Exercise duration is `:number`, so a blank submission is skipped at `crud/handlers.clj:30` and preserves the old duration.
  A corrected line can therefore continue contributing an obsolete best performance.
  Add field-level clearing metadata for this attribute and honor it in `cleared-fields`, without changing unrelated numeric fields.
  Test blank removal and omission preservation through the update handler.

- [x] Render the new validation errors in generic CRUD forms.
  `crud/handlers.clj:99` calls the validated mutation without handling its field errors.
  Only the focused editor catches `invalid-write?` (`app/goal_editor.clj:399`).
  Entering zero exercise duration in its generic form raises an exception instead of showing the useful duration error.
  Generic goal forms have the same problem for cross-field validation.
  Catch conversion and write-validation failures in shared CRUD handlers and re-render submitted values with field errors.

- [x] Apply complete-week defaults when switching a new goal to weekly timing.
  `app/goal_editor.clj:97` defaults to Monday only when the page initially receives weekly timing.
  The normal new form starts dated; `app/goal_editor.clj:386` preserves its populated start when the user selects Weekly.
  Creating a weekly goal midweek therefore defaults to a partial first week despite the specified complete-week default.
  Track whether dates remain at their defaults and initialize Monday/Sunday when switching a new goal to weekly.
  Preserve dates the user deliberately entered.

### Rule violation

- [x] Bound source requests to the actual chart and activity windows.
  `goals/dashboard.clj:45` always starts at the earlier of the original goal start and the activity window.
  It ignores the current weekly period and dated end, then scans continuously through today.
  A weekly goal created years ago reads those years on every refresh, although only the current week and 84-day history are displayed.
  An old ended goal also reads the unused gap between its deadline and recent activity.
  Build requests from `calc/window` and the supporting-panel windows; batch compatible ranges without filling unrelated gaps.
  Also bound visibility lookups to candidate parent IDs: `db/queries.clj:1392` reaches the user-wide exclusion scans at `db/queries.clj:133`.
  Actual join order and latency remain unverified under the already tracked query-plan task.

- [x] Preserve changed-field highlighting for the new workout duration input.
  `app/workout.clj:433` renders duration through `stepper-ctrl`, whose input lacks `data-original-value` at `app/workout.clj:356`.
  The custom edit form cannot compare the duration against its saved value.
  Add the original value to the shared stepper input and dispatch input events when its buttons change the value.

- [x] Replace the new pixel-valued Tailwind class.
  `app/workout.clj:432` adds `text-[10px]` for the duration label, contrary to the named-size rule.
  Use a named text size, such as `text-xs`, consistent with the surrounding controls.

- [x] Document the new public functions.
  Docstrings are absent from `goals/calc.clj:34`, `goals/calc.clj:38`, `goals/dashboard.clj:86`, `goals/validation.clj:18`, and `goals/registry.clj:126`.
  Their callers need clear date, numeric, and invalid-input contracts.
  Add concise docstrings or make functions private when they have no external callers.

- [x] Remove direct user-settings fallbacks from the new goal paths.
  `goals/dashboard.clj:144` and `db/queries.clj:1447` call `get-user-settings` directly when settings are absent.
  The normal page supplies resolved settings, but these public fallback paths bypass the required ctx-first resolver.
  Require resolved settings as an argument, or resolve them once from context at the orchestration boundary.

### Risk

- [x] Distinguish unknown current coverage from confirmed zero activity.
  `goals/calc.clj:263` turns every empty total window into zero without a coverage input.
  `app/goals.clj:542` then displays zero percent and pace statistics even when the source history is unavailable.
  This is separate from the already tracked absence of prior-year comparisons.
  Carry a coverage state for the current window and label incomplete coverage; reserve confirmed-zero claims for known-complete windows.

- [x] Extend validation coverage beyond the existing happy paths.
  `test/tech/jgood/gleanmo/test/goals/dashboard_test.clj:63` covers parent dating but not foreign lines, deleted parents, or bounded orchestration requests.
  `test/tech/jgood/gleanmo/test/goals/calc_test.clj:125` has no explicit best-duration assertion.
  The calculation tests also omit a partial final week, autumn DST, equal-end-time book ordering, and recalculation after a book-total edit.
  `e2e/scripts/test-goals.ts:186` tests measure persistence but not the even-pace preference or weekly editor defaults.
  Add behavior tests for these checklist gaps alongside regressions for the bugs above.

### Cleanup

- [x] Reuse registry relation metadata instead of maintaining a second mapping.
  `db/queries.clj:1352` repeats the source-to-relation attributes already declared at `goals/registry.clj:13`.
  Adding a source currently requires keeping both maps synchronized despite the registry's stated role as the shared definition.
  Derive relation fields from the registry and retain only query-specific projection details in the database namespace.

### Refinement of already tracked work

The comparison deferral is broader than missing coverage entry.
`app/goals.clj:654` renders one textual comparison; `app/goals.clj:374` has no prior-year series or year-selection controls.
`goals/calc.clj:465` checks prior coverage only, although the contract requires both periods to be covered.
The review fixes now require coverage for both periods and test the known-coverage renderer.
The comparison follow-up still includes coverage entry, prior-year chart series, and year-selection controls.

## Review resolution (2026-09-15)

The code-review checklist above is implemented.
Count today and Edit placement are implemented below; the remaining dogfood feedback stays open.

- Source visibility now checks ownership, deletion, and visibility through bounded reads of candidate ancestors.
  Book completion uses the same policy as numeric reading goals.
- Each goal derives its completed-day cutoff from one request instant in its saved time zone.
  Intervals must finish by that cutoff before clipping to the goal period.
- Source requests merge overlapping progress and activity windows without scanning unused gaps.
  Duration requests find overlapping intervals without a maximum-duration assumption.
- Best-performance summaries use maxima and performance wording.
  Unknown current coverage leaves empty totals unknown and suppresses pace estimates.
  Recorded nonempty totals remain visible with a coverage label.
- Generic CRUD shows conversion and write-validation errors beside fields and retains submitted values.
  Exercise duration explicitly supports blank removal; omitted values remain stored.
- Weekly editor defaults use Monday and Sunday until the user changes each date.
  Workout steppers preserve original values and dispatch input events.
- Regression tests cover ownership, ancestry, visibility, long intervals, bounded requests, opposite-midnight zones, cutoff eligibility, and current coverage.
  They also cover best duration, recent maxima, autumn DST, partial final weeks, book ordering, edited totals, and comparison rendering.
  Browser tests cover weekly defaults, both preference states, duration highlighting, validation errors, and clearing.

Validation:

- `just validate`: 150 tests, 984 assertions, no failures or errors.
- Goals, reading CRUD, and reading timer E2E passed.
- Baseline and final visual series each captured all 64 frames.
  Final capture: `e2e/screenshots/series/2026-09-15T13-24-38Z/`.
- The expanded workout E2E passed after restarting the dev server.
  It verified duration highlighting, validation errors, submitted values, and persisted blank removal.
  The fresh-process persistence test also passed.
  The previous process retained schema metadata captured when its CRUD routes were initialized.

The production-sized query-plan and latency audit remains open.
The comparison controls and activity-cell keyboard navigation remain follow-up work.

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

## Count today and action placement (2026-09-15)

Boundary tests were written before implementation and failed against the previous cutoff behavior.
They cover today versus completed-day amounts, open and future-ending intervals, midnight, opposite time zones, Monday resets, and ended goals.
Database tests verify today's reading totals and completion, then delete the source record and verify recalculation.
Book and numeric charts retain today's points within their displayed bounds.
Unknown coverage still suppresses pace estimates, including chart guides.

The selected-goal header now contains Edit, Archive, and Delete at the top right.
Browser checks exercise Edit and Archive there and verify desktop/mobile placement.
Per-row editing, query profiling, comparison controls, activity keyboard navigation, and duplicate merging remain separate follow-ups.

Validation:

- `just lint-fast` passed for every changed Clojure file.
- `just check` and `just validate` passed: 158 tests, 1,343 assertions, no failures or errors.
  Lint reported 24 existing warnings outside the changed files.
- Focused calculator and dashboard namespace tests passed.
  Full validation also ran the registry-wide measurement integration tests.
- Goals E2E passed before and after the change, with paired desktop/mobile screenshots.
  The expanded test verifies today's numeric and book chart points, future-ending exclusion, and unknown-coverage suppression.
  It also verifies header action placement and exercises Edit and Archive.
- Reading CRUD and reading timer E2E passed.
- Baseline series: `e2e/screenshots/series/2026-09-15T20-02-51Z/`, 64/64 frames.
- Final series: `e2e/screenshots/series/2026-09-15T20-23-38Z/`, 64/64 frames.
- `git diff --check` passed; desktop and mobile goal screenshots were visually reviewed.

The full unrelated E2E suite and query-plan/latency profiling were not run for this scoped change.
No commit, push, deployment, production migration, or server restart was performed.
