# Goals 09 data contract

This iteration extends the [version 08 contract](goals-08-data-contract.md).
Fixtures remain fictional and use its September 12, 2026 cutoff.
Production schemas and queries remain unchanged.

## Measurement and timing

Measurement and timing are independent choices.
Supported measurements remain duration, counts, and best performance.
Count labels retain their domain units, such as sessions, logs, or repetitions.
Timing supports dated targets, weekly resets, and open-ended accumulation.
Weekly duration now uses the same calendar boundaries as weekly session counts.

Replace the proposed mixed `goal/mode` with separate aggregation and timing fields before production implementation.
An aggregation declares duration union, count, sum, or maximum.
Timing declares a fixed date range, a calendar week, or no deadline.
Validate the supported combinations through a measurement registry.
The editor must expose supported measurements and relation filters rather than arbitrary attribute expressions.

## Relation scope

The new examples include duration for one book and weekly sessions for one meditation.
Use the existing reading-log/book-id and meditation-log/type-id relationships.
All-books and selected-book goals can independently receive credit from the same interval.
Selection never changes the aggregation rules or creates duplicate credit within one goal.
The Odyssey fixture measures time invested, not completion or reading percentage.

The user confirmed that reading-log/finished? means the book was finished.
Completion goals remain deferred because they need a suitable presentation.
Page counts, format-dependent progress, nonfiction classification, and arbitrary attribute filters also remain deferred.

## Open-ended totals

The meditation and selected-book examples count from July 1, 2026.
Earlier activity is excluded from their totals.
Their targets never reset, and neither has an end date.
A production goal must declare its counting start; including earlier history requires an explicit baseline choice.

The table retains average rate, progress, and next milestone.
Required rate, required/average ratio, and even-pace difference display an em dash.
No even-pace marker appears on its progress bar.
The main chart ends at the current cutoff and shows a horizontal target.
No required-rate projection or deadline forecast appears.

The lower-right panel shows duration added during the last 28 completed days and the number of active days.
Prior-year controls are hidden for open-ended goals; selections remain available when returning to scheduled goals.
The 12-week history remains an activity view, including days before the goal started.
Those earlier days do not contribute to the goal total.

## Interaction and layout

Measurement and timing filters intersect with text search.
Every table row names its timing alongside its source.
The initial selection is the open-ended meditation goal.
The selected card preserves one main graph and two supporting panels.
At 1440 × 900, the main graph fits within the viewport.
Mobile retains horizontal scrolling inside the goals table and stacks the supporting panels.
