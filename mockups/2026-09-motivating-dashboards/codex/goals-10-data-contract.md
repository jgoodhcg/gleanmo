# Goals 10 data contract

This iteration extends the [version 09 contract](goals-09-data-contract.md).
It adds book completion and separate position charts to the standalone mockup.
All book totals, editions, and reading sessions are fictional fixtures.
No production schema, database, or route changed.

## Optional book and log attributes

Books can supply independent total pages, total chapters, and total audiobook duration.
Reading logs can supply optional starting and ending page, chapter, and audiobook positions.
A log can supply several measures, regardless of its recorded format.
The user manages differences between editions, ebook pagination, and physical books.
The application does not convert positions between formats or average their percentages.
Store audiobook durations and positions in seconds; display timestamps as hours:minutes:seconds.
Do not add a playback-speed multiplier.

Reading-session duration remains the elapsed interval between beginning and end.
The fixture's minutes represent that interval duration.
Playback position and elapsed interval duration need not agree.
Position charts use the ending position, not a sum of positions or the greatest historical value.
Starting positions remain visible in the disclosure for future activity measures.
A decrease can represent rereading or correcting an earlier position and must not be silently removed.

## Completion

A qualifying reading log with reading-log/finished? set to true completes the goal.
The mockup checks logs for the selected book from the goal start through its current cutoff.
The completion date comes from the first qualifying finished log.
Production must explicitly assign this event to the log's end timestamp in the goal timezone.
Logs outside the user's scope, deleted logs, and hidden parent books follow the existing visibility policy.
A deadline determines whether completion was on time; it does not stop a later completion from being recorded.

Reaching any book total never substitutes for the finished flag.
Conversely, completion does not require any position to equal its book total.
The completed example intentionally finishes at 216 of 224 pages and 6:40:00 of 7:00:00 audiobook duration.
Its chapter position reaches 10 of 10.
These readings describe one completed book without requiring reconciliation.

## Charts and state

The default selected goal is Finish The Odyssey, with no deadline.
The Left Hand of Darkness demonstrates a deadline; A Wizard of Earthsea demonstrates completion.
All three examples contain paperback or hardcover, ebook, and audiobook sessions.

Pages, Chapters, and Audio buttons switch independent position histories.
Each goal remembers its selected measure for the current page session.
The latest recorded position is shown with its date and the matching book total.
The percentage describes that position only; it is never the completion condition.

Dotted lines bridge measurements separated by logs without the selected position.
No equivalent position is inferred from other measures or elapsed duration.
Dots identify recorded ending positions.
Solid lines connect adjacent logs that both contain the selected measure.
Dotted links do not add inferred measurements or extrapolate beyond recorded positions.
The horizontal reference line represents the book total.
Deadline goals optionally show a straight even-pace guide from zero at goal start to the book total at deadline.
The guide is a visual reference, not a forecast or a required reading rate.
No-deadline goals omit the pace control and end their displayed range at today.

Completion rows show the explicit completion state and the selected position.
They omit average rates, required rates, pace ratios, and next numeric thresholds.
The activity strip represents reading-session duration, independent of the selected position measure.
Recent reading shows each session's format and recorded positions.
The What counts disclosure shows the book totals and every log's starting and ending positions, duration, and finished flag.

## Deferred scope

Production schema fields, ingestion, form rendering, persistence, and query implementation remain outside this mockup.
Position selection persists only until the page reloads.
Nonfiction classification and arbitrary-attribute goal editing remain deferred.
