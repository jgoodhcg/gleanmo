---
title: "Exercise Tracking Requirements"
status: active
description: "Exercise tracking with superset support and Airtable backfill"
tags: []
priority: high
created: 2026-02-02
updated: 2026-10-04
---

# Exercise Tracking Requirements

## Work Unit Summary
- Problem / intent: Build exercise tracking that supports supersets and enables Airtable backfill.
- Constraints: Preserve Airtable lineage fields and keep data entry lightweight during workouts.
- Proposed approach: Add the missing exercise-rep entity, fix schema types, then build ingesters and UI in that order.
- Open questions: Should rep-level timestamps ever be captured, or remain optional forever?

## Overview
A comprehensive exercise tracking system that supports flexible workout logging, including supersets and detailed rep tracking. Designed to handle Airtable data migration while providing an intuitive workout flow.

## Core Entities

### Exercise
**Purpose**: Basic exercise definitions (e.g., "Bench Press", "Squats")
```clojure
:exercise/id          :uuid
:exercise/label       :string          ; "Bench Press", "Deadlift"  
:exercise/notes       {:optional true} :string
```

### Exercise Session  
**Purpose**: Represents a single trip to the gym or workout session
```clojure
:exercise-session/id         :uuid
:exercise-session/beginning  :instant     ; Start of gym session
:exercise-session/end        {:optional true} :instant ; End of gym session
:exercise-session/notes      {:optional true} :string
```

### Exercise Set
**Purpose**: A single set or superset within a session
```clojure
:exercise-set/id             :uuid
:exercise-session/id         :exercise-session/id  ; Links to session
:exercise-set/beginning      :instant               ; Start of set
:exercise-set/end            {:optional true} :instant ; End of set  
:exercise-set/notes          {:optional true} :string
```

### Exercise Rep
**Purpose**: Individual rep data within a set (supports supersets)
```clojure
:exercise-rep/id             :uuid
:exercise-set/id             :exercise-set/id       ; Links to set
:exercise/id                 :exercise/id           ; Which exercise
:exercise-rep/rep-number     {:optional true} :int  ; Rep sequence in set
:exercise-rep/weight         {:optional true} :number
:exercise-rep/weight-unit    {:optional true} :string ; "lbs", "kg"
:exercise-rep/reps           {:optional true} :int   ; Number of reps
:exercise-rep/distance       {:optional true} :number ; For cardio
:exercise-rep/distance-unit  {:optional true} :string ; "miles", "km"
:exercise-rep/notes          {:optional true} :string
```

## Data Entry Workflow

### Starting a Workout
1. **Create Session**: User starts a new exercise session
2. **Session Screen**: Shows active session with ability to add sets

### During Workout  
1. **Add Set**: User clicks to add a new set before starting
2. **Start Set**: Timer begins for the set
3. **Stop Set**: User stops the set timer when complete
4. **Add Reps**: User adds rep data (exercise, weight, reps)
   - **Single Exercise**: One rep entry per set (most common)
   - **Superset**: Multiple rep entries per set (different exercises)

### Completing Workout
1. **Finish Session**: Mark session as complete
2. **Review**: Option to review and edit entered data

## Key Features

### Superset Support
- Multiple `exercise-rep` entries can belong to a single `exercise-set`
- Each rep entry specifies its exercise, allowing mixed exercises per set
- Set timing covers the entire superset duration

### Flexible Data Entry
- **No timestamps on reps**: Avoids tedious data entry during workouts
- **Optional rep numbering**: For tracking progression within sets
- **Multiple units**: Support for both metric and imperial measurements

### Data Migration Support
- Schema accommodates existing Airtable data structure
- Migration fields can be removed after successful data port

## Schema Discrepancies vs Current Implementation

### Missing Entities
- **Exercise Rep**: Current schema puts rep data directly on exercise-set
- **Proper superset support**: Current design doesn't clearly separate set timing from rep data

### Current Schema Issues
1. **Type Error**: Exercise entity has `[:sm/type [:enum :habit-log]]` instead of `:exercise`
2. **Exercise Log Confusion**: Current `exercise-log` entity overlaps with proposed `exercise-set`
3. **Mixed Concerns**: Current `exercise-set` combines timing and rep data

### Airtable Historical Fields
Current schema includes Airtable fields for preserving data lineage:
- `:airtable/exercise-log` - Original Airtable log reference
- `:airtable/log-count` - Historical log count
- `:airtable/id` - Original Airtable record ID
- `:airtable/ported` - Migration status flag
- `:airtable/created-time` - Original creation timestamp
- `:airtable/missing-duration` - Data quality indicator

These fields preserve historical context and should be maintained permanently.

## Recommended Schema Updates

### 1. Fix Exercise Type
Change `[:sm/type [:enum :habit-log]]` to `[:sm/type [:enum :exercise]]`

### 2. Simplify Current Entities
- Keep current `exercise`, `exercise-session` entities
- Rename `exercise-log` to `exercise-set` (if not already used)
- Add new `exercise-rep` entity

### 3. Migration Strategy
- Preserve Airtable fields permanently for historical data lineage
- Map current `exercise-set` data to new `exercise-rep` structure
- Maintain data provenance through optional Airtable fields

## Success Metrics
- **Quick Entry**: Can log a full workout in under 2 minutes
- **Superset Support**: Easy to track complex workout patterns  
- **Data Integrity**: Full historical data preserved via Airtable fields
- **Flexible Analysis**: Support for various workout analytics and trends

## Implementation + Migration Notes
- **Naming update (2026-07-10, implemented):** the entities shipped as `exercise-session` → `exercise-block` (the timed chunk this doc calls "exercise-set"; superset-capable) → `exercise-set` (the per-exercise reps×weight entry this doc calls "exercise-rep"). Read the schema in `exercise_schema.clj` as authoritative; this doc's entity names predate the rename.
- The Malli schemas in `src/tech/jgood/gleanmo/schema/exercise_schema.clj` exist but no routes, CRUD pages, or background tasks currently persist exercise data—treat the roadmap above as unimplemented work.
- Airtable remains the system of record. We must:
  1. Export the latest exercise + log tables (sessions, sets, reps) into `airtable_data/`.
  2. Build REPL ingesters mirroring the BM log approach: deterministic UUIDs per Airtable record, enum normalization, and Malli validation.
  3. Persist historical sessions before enabling the new UI so trends remain continuous.
- Once migration helpers exist, document the run (record counts, file names) alongside any cleanup scripts so future backfills are reproducible.

## First real-workout feedback (2026-08-01)

The screen was used to run an actual workout for the first time and three
things failed. All three shared one root cause: **lines could only be written
against "whatever set is running,"** so a set that was timed honestly could
never be described afterwards.

1. **Timing accuracy and exercise entry were in competition.** The recording
   state's only logging action was `Log <exercise> × <reps>`, which logs *and*
   ends the set. Reaching for the picker while the clock ran cost real
   seconds, so the set was ended bare instead — and then there was no way back
   to it.
2. **A submit with no exercise ended the set and discarded the entry.**
   `add-line!` guarded the line write with `(when exercise-id ...)` but ended
   the set unconditionally. Reps and weight went nowhere and nothing said so.
3. **The generic CRUD edit forms were unusable at production scale.**
   `:exercise-line/set-id` renders as a `:single-relationship` select, and
   `relation-options` pulls every entity of the related type with no limit.
   After the Airtable import that is ~10,190 `<option>` elements plus a
   Choices.js init — a freeze on mobile, not a form.

### Shipped

- Lines are written against a **set id**, never against the running set:
  `POST /set/:id/line` (add), `POST /line/:id` (update), `POST /line/:id/delete`.
  Adding a line to an existing set never touches its `beginning`/`end`.
- Every set card carries **+ Add exercise** (loud on a bare set, quiet once a
  line exists — the after-the-fact superset), and every line row opens an
  **inline edit form**. Both fetch the form as an htmx fragment
  (`GET /set/:id/line/new`, `GET /line/:id/edit`) so only one exercise picker
  exists on the page at a time.
- The edit fragment deliberately carries **no exercise memory**: swapping a
  mis-picked exercise must not overwrite the reps and weight being kept.
- **`POST /set/:id/resume`** reopens the newest set, guarded three ways —
  session still open, nothing else running, newest set only — because each
  would corrupt an interval rather than merely annoy.
- No-exercise submits write nothing, **end nothing**, and show a banner.
- The summary page for a finished session carries the same editors (minus
  resume), so a badly logged workout is fixable later.

### Second pass, same day: stopping the clock is a first-class path

The first pass gave `End set` a destination but buried it — the set dropped
into the history and the log form vanished, so "stop first" still meant
scrolling and a different affordance than the one you'd been using. Fixed by
adding a fourth state:

- **`stopped`** — the newest set has ended with nothing logged on it. A
  frozen-timer card takes the running card's place, and the log form stays
  exactly where it was: same card, same fields, same `Log <exercise> × <reps>`
  button, now posting to that set instead of the session. Nothing about
  logging changes except that the clock is no longer moving.
- The button is now **`Stop timer`**, not `End set` — the set isn't finished
  with, and `Resume timer` can take the clock back.
- **`Skip — start next set`** moves on without describing it; the bare set
  stays in the history where `+ Add exercise` still works.
- The stopped set is pulled out of the history list while it holds the panel,
  so its `resume` isn't offered twice. Deleting the last line off the newest
  set drops back into this state, which is correct — it is timed and
  undescribed again.
- `resume` and `edit` in the set-card header now share a baseline: the resume
  form was `display:inline`, so it inherited the row's strut instead of
  hugging its 11px button and sat ~2px low. It's `flex items-center` now,
  with an E2E assertion on the two bounding boxes.

### Deliberately not changed

- **The recording state's primary action** stays `Log <exercise> × <reps>`.
  It is still the cheapest path when you already know what you did.
- **State stays out of the URL.** All three states (idle / between-sets /
  recording) derive from the open session and open set. A `/recording` path
  would be a second source of truth that can disagree with the data after an
  `End session` from another device, and would make bookmarks and the back
  button land on states that no longer exist. Identity does belong in the
  path and already is: `/session/:id/summary`.

### Still open

- The unbounded relationship select (point 3 above) is only *routed around*,
  not fixed. `edit` on a set card still opens the `exercise-set` CRUD form
  with a ~2,563-option Session select. See
  [069-crud-relation-select-scale.md](./069-crud-relation-select-scale.md).

## Session-use feedback (2026-09-19)

Four notes from using the workout screen in a real session. None is a bug in
the data model; all four are about what the screen says and shows while a
session is running.

### Cool-down timer and in-session stats

While a session runs, the screen shows the session clock and the running set,
but nothing about **rest**. Wanted: a cool-down / rest timer between sets —
how long since the last set ended — plus a few relevant stats for the session
so far (sets done, total volume or working time, time per set, maybe last
set's rest).

- The rest interval is already derivable: it's `now` minus the newest set's
  `end`, so the idle and stopped states both have what they need with no new
  entity.
- Rendering must tick on the client, not server-side — the same
  `data-epoch-ms` / `data-fmt` mechanism the session clock already uses.
- Open: does rest belong in the idle ("Start set") panel only, or also
  alongside the stopped-set card? Which stats earn the space on a phone?
- Related: the home page has the inverse problem — its timers don't tick at
  all (see `008-backlog.md`, "Home Page Active Timers Don't Tick").

### "Skip — start next set" doesn't explain itself

The stopped state's secondary button reads `Skip — start next set`, and in use
it wasn't clear what gets skipped (describing the set? the rest? the set
itself?). The semantics are documented in `app/workout.clj` — it leaves the
bare set undescribed in the history and starts a new one — but the label
carries none of that.

- Consider wording that names the consequence, e.g. "Start next set — leave
  this one blank", or a short helper line under the button.
- Same question applies to `Stop timer` vs `End session` at a glance.

### Notes: set or line, and how to edit them

`exercise-set/notes` and `exercise-line/notes` both exist (and
`exercise-session/notes`, and `exercise/notes`), and it's not obvious from the
screen which one a note lands on or where it shows up afterward.

- Decide which level is the one the workout screen writes to. A note in
  practice is usually about *what happened on that exercise* (form felt off,
  dropped weight), which argues for the line; a note about the block of work
  (interrupted, shared the rack) argues for the set.
- Whichever it is, it needs an affordance on the recording/stopped card rather
  than only through the CRUD form, and notes already written need to be
  **visible** on the set/line rows — today they aren't surfaced there at all.
- The other levels stay in the schema; this is about which one the fast path
  writes and reads.
- **Raised again (2026-10-04)**, dogfood list, verbatim: "I need to be able
  to take notes on workout sets and exercise lines in the workout view".
  That answers the "which level" question for now: **both**. Sets and lines
  each get a note affordance on the workout screen, and their notes show on
  the set card and line row. Second report of the same need, so it should
  move up ahead of the other session-use notes.

### Start set gives no sign it registered (2026-10-04)

Dogfood list, verbatim: "There needs to be better indication between pressing
the "start set" or session button and the timer starting".

`Start set` and `Start session` are native form posts that reload the page.
The double-submit guard in `main.js` already marks the tapped button
`.is-submitting` (`tailwind.css:754`: opacity 0.55, wait cursor) until the
reload, so feedback exists; it is too faint to read as "starting" on a bright
neon-cyan button in a gym. The timer starts only when the new page renders,
which is the gap the user feels.

That is the same failure [079](./079-htmx-navigation-consistency.md) records
for timer Start ("the tap looked unregistered, a second tap followed"); 079
requires visible in-flight feedback when it replaces the guard, and now names
this note as the bar that feedback has to clear. Candidates: label swap
("Starting…"), a spinner or pulse on the button, or showing the running clock
from the tap and reconciling on response. Applies to the bouldering screen's
Start attempt / Start session too.

### Failure check on set lines

Confirms [082-exercise-effort.md](./082-exercise-effort.md) and nudges it
toward a simple **check** on the line rather than a numeric scale. Recorded
there.
