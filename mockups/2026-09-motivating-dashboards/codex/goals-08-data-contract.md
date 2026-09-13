# Goals dashboard data contract

This proposal accompanies `mockup-codex-08-goals-dashboard.html`.
The HTML uses deterministic fictional fixtures and makes no network requests.
It freezes today at September 12, 2026, with completed days through September 11.
No production schema or database has changed.

## Measures

| Goal | Source | Aggregation | Unit | Mode |
|---|---|---|---|---|
| Writing | project-log, selected projects | Union of completed intervals | duration | Cumulative |
| Reading | reading-log | Union of completed intervals | duration | Cumulative |
| Meditation | meditation-log | Union of completed intervals | duration | Cumulative |
| Pull-ups | exercise-line, selected exercises | Sum reps | reps | Cumulative |
| Floss | habit-log, selected habits | Count logs | logs | Cumulative |
| Workout sessions | exercise-session | Count completed sessions | sessions | Calendar week |
| Climbing attempts | boulder-attempt | Sum attempts, default one when absent | attempts | Cumulative |
| Time on the wall | boulder-attempt | Union of completed intervals | duration | Cumulative |
| Climbing sessions | boulder-session | Count completed sessions | sessions | Calendar week |
| Climbing season | boulder-session | Count completed sessions | sessions | Cumulative |
| Deadlift | exercise-line, selected exercise | Maximum weight with at least one rep | kg | Achievement |
| Dead hang | exercise-line, selected exercise | Maximum explicit line duration | duration | Achievement |

The mockup interprets habit counts as records, rather than distinct active days.
Bouldering retry counts use `boulder-attempt/attempts`; `laps` does not multiply the count.
These are proposed counting semantics, exposed in each goal’s “What counts” disclosure.

## Proposed goal entity

Use the standard closed schema, user ownership, and `schema.meta` fields.
Do not add legacy meta fields to this new entity.

- `goal/label`: string.
- `goal/source`: enum of supported source entities.
- `goal/measure`: enum `duration`, `reps`, `logs`, `sessions`, `attempts`, or `weight`.
- `goal/mode`: enum `cumulative`, `calendar-week`, or `achievement`.
- `goal/target`: positive number in the canonical measure unit.
- `goal/unit`: `seconds`, `reps`, `logs`, `sessions`, `attempts`, or `kg`.
- `goal/starts-on`, `goal/ends-on`: inclusive local dates, using the project’s local-date representation.
- `goal/time-zone`: IANA timezone identifier, fixed per goal.
- `goal/project-ids`, `goal/book-ids`, `goal/meditation-ids`, `goal/habit-ids`, `goal/exercise-ids`: optional typed relation sets.
- `goal/threshold-step`: optional positive number in the canonical measure unit.
- `goal/archived`: optional boolean.

Validate source, measure, unit, relation filters, and mode as an allowed combination.
Reject incompatible combinations before saving.
An absent relation filter means all visible records of that source.
An explicit empty selection must not silently become “all.”
The initial weekly mode supports session counts only, matching this deliverable.
Weight targets represent recorded load, not body weight or estimated one-rep maximum.

Store duration targets as seconds; display hours, minutes, or seconds as appropriate.
Convert recorded intervals through tick/Java Duration.
Calendar boundaries use tick local dates and Period; never implement weeks as 604800 elapsed seconds across DST.
Do not store pace, required rate, progress percentage, chart series, or current totals on the goal.

## Existing schema gap

`exercise-line` currently stores reps, weight, and distance, but no explicit duration.
Add optional `exercise-line/duration-seconds` as a positive number before implementing individual duration achievements.
Keep all existing attributes unchanged.
Update the CRUD schema/render/conversion/formatting path and exercise logging UI when this field is implemented.
Do not substitute exercise-set duration: a superset contains multiple exercise lines.
Historical lines without the new field contribute no duration achievement.

Existing boulder-attempt beginning/end fields support attempt duration.
Existing exercise-line weight and weight-unit fields support weight achievements.
Convert pounds to kilograms before comparing values; exclude weights with missing units.

## Boundaries and weekly behavior

Use half-open instant ranges derived from inclusive local dates.
Attribute point records to their local timestamp date.
Attribute exercise lines to their parent set’s local beginning date.
Attribute session counts and attempt counts to their own local beginning date.
Split interval duration at local midnight and clip it to the goal period.
Merge overlapping intervals within the selected source and scope before summing duration.
Exclude open intervals, deleted records, archived records where supported, and records hidden by resolved user settings.
Apply ownership and visibility to referenced parents as well as the leaf record.

Weekly goals reset Monday at local midnight and close at the following Monday.
Only the current week determines the current target, remaining count, and required rate.
A completed week records whether its own target was met; surplus never offsets a missed week.
Keep the stated target for partial boundary weeks and label those weeks as partial in a future goal editor.
For the first editor, default the start date to Monday and the end date to Sunday.

This mockup uses completed days so its denominator and totals share a cutoff.
On September 12, the current week has five completed days and two remaining days.
July 1–October 31 has 123 calendar days: 73 completed and 50 remaining.
A production refresh must retain one explicit cutoff for every dashboard region.

## Calculations and states

For cumulative and current-week goals:

- Average: logged quantity / completed calendar days.
- Required: max(0, target − logged quantity) / remaining calendar days.
- Ratio: required / average, calculated before display rounding.
- Even-pace difference: (logged / target × period days) − completed days.
- Next threshold: the next configured multiple, capped at the target.

Duration rate labels use minutes/day.
Achievement goals show best performance and the remaining improvement.
They omit average rate, rate ratio, and even-pace difference.
Their graph shows a stepwise best performance and a horizontal target.
No linear strength or duration-improvement forecast is implied.

The fixture includes active, positive-total goals.
Production must additionally render unstarted, zero-total, reached, ended, and missing-history states explicitly.
Avoid division by zero; a goal with no logged quantity has no rate ratio.
An ended goal has no “required from today” rate.
A reached goal has zero remaining quantity and no further threshold.
Treat the absence of logs as zero only when source coverage is known.

## Charts and comparison

The main graph combines logged progress, even pace, required path, and progress from each selected prior year.
Weekly graphs show the current week only, without historical surplus.
Prior-year weekly comparisons align Monday–Friday with the ISO-numbered week in the previous ISO week-year.
When the matching ISO week does not exist, show comparison unavailable.
Cumulative and achievement comparisons use matching month/day boundaries.
For leap-day boundaries, use the final valid day of February.

The bottom history shows logging presence, not a daily minimum or weekly success claim.
For achievements, a cell reports that day’s recorded performance; the main graph reports the running maximum.
All three graphs use only the selected goal.
The history panel shows its daily logging; the ghost panel compares it against each selected prior year.
Aggregate comparisons across goals are reserved for a separate dashboard.

Checkboxes allow several prior years simultaneously, with 2025 selected initially.
The mockup also provides 2024 and 2023 fixtures.
Each year has a stable color shared by its checkbox, main-graph line, legend, and comparison bar.
Year selections persist across goal changes during the current page session.
Clearing all years hides the ghost lines and displays a comparison prompt.
Production year choices must derive from coverage metadata for the selected source and scope.
Retain selected years across goals, but label unavailable years and omit their comparisons.

Production ghost comparisons require known source coverage for both periods.
An empty prior-year range alone does not establish zero activity.
Use explicit per-user source coverage metadata, such as source plus known-complete start and end dates.
Unknown coverage displays “No comparable history” and omits the ghost line and percentage.
Coverage tracking is a separate implementation prerequisite, not inferred from the earliest record.

## Query contract

Keep public signatures intent-based, with user, source, relation scope, date range, timezone, and measure.
Implement database access only in `db/queries.clj` and `db/mutations.clj`.
Use targeted time-window projections and bounded parent reads for exercise relationships.
Fetch only fields required for aggregation; never fetch a user’s entire history to filter it in the UI.
Use `resolve-user-settings` for visibility.
Measure XTDB query plans before committing to a scan shape.

## Validation

Browser checks and screenshot evidence are recorded in `goals-08-validation.md`.
The application visual-series baseline was explicitly waived for this standalone mockup.
