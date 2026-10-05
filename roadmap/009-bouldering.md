---
title: "Bouldering Log Roadmap"
status: draft
description: "Climbing sessions and problem attempts with Airtable backfill"
tags: []
priority: high
created: 2026-02-02
updated: 2026-10-04
---

# Bouldering Log Roadmap

## Work Unit Summary
- Problem / intent: Capture climbing sessions and problem attempts with Airtable backfill.
- Constraints: Preserve Airtable schema fidelity for import and keep data entry lightweight at the gym.
- Proposed approach: Add session + attempt entities, CRUD flows, then build an Airtable ingester.
- Open questions: What is the final grade enum list and how should gym-specific grade systems map?

Capture climbing sessions and problem attempts (grades, gyms, attempts, sends) with Airtable backfill.

## Objectives
- Reproduce the Airtable schema so historical climbs import cleanly.
- Support both session-level tracking (date, gym, duration, RPE) and per-problem attempts (grade, color, number of tries, send status).
- Enable future analytics (grade pyramid, session frequency, workload).

## Proposed Data Model
```clojure
:boulder-session/id         :uuid
:user/id                    :user/id
:boulder-session/date       :date
:boulder-session/gym        :string
:boulder-session/duration   {:optional true} :duration
:boulder-session/rpe        {:optional true} [:enum :easy :moderate :hard :limit]
:boulder-session/notes      {:optional true} :string

:boulder-attempt/id         :uuid
:boulder-session/id         :boulder-session/id
:boulder-attempt/problem-id {:optional true} :string   ; gym identifier / color
:boulder-attempt/grade      [:enum :v0 :v1 :v2 ... :v13 :project] ; adjust to Airtable list
:boulder-attempt/color      {:optional true} :string
:boulder-attempt/attempts   {:optional true} :int      ; total tries
:boulder-attempt/send?      :boolean
:boulder-attempt/notes      {:optional true} :string

;; Airtable provenance
:airtable/id                {:optional true} :string
:airtable/created-time      {:optional true} :instant
```

## Implementation Plan
1. **Schema + Routes**  
   - Add Malli schemas and CRUD modules for sessions/attempts (similar to exercise session/set).  
   - Provide nested forms or separate create flows (session first, then attempts via link).
2. **Airtable Import**  
   - Export existing bouldering Airtable base.  
   - Build converters for session + attempt tables, ensuring deterministic UUIDs and grade normalization.  
   - Import history before enabling new UI to avoid dual entry.
3. **Visualizations**  
   - Calendar heatmap for sessions (timestamp).  
   - Grade pyramid + send rate charts (future, once data is in).

## Questions
- Do we also track hangboard/campus workouts here or separately?
- Should non-send attempts include per-try timestamps, or is aggregated count enough?
- Any shared taxonomy with exercise timers (locations, RPE scales) worth reusing?

## Gym-use feedback (2026-10-04)

Dogfood notes from using the bouldering session screen at the gym.
The screen is built (`app/boulder.clj`, `/app/boulder/session`) even though the plan above predates it;
the data model it shipped with is the session → attempt → problem library described in that namespace's docstring, not the sketch above.

### Stop and cancel a running attempt

User, verbatim:

- "Bouldering needs a stop timer too"
- "Need a cancel/reset on bouldering"

Treated as one item because both are about getting out of a running attempt without logging it.
Today an attempt interval can only close by being logged: the routes are `start-attempt!` and `add-attempt!`, with no stop, resume, or discard (`boulder.clj` routes near line 754).
The only Cancel on the screen closes the "log without a start time" backfill form; it does nothing to a running attempt.

"Stop timer too" points at the workout screen, which has exactly this (see [015](./015-exercise.md), "Second pass, same day: stopping the clock is a first-class path"):
`Stop timer` freezes the clock with nothing recorded and keeps the log form in place, `Resume timer` reopens it if stopped a beat early.
Mirroring that gives bouldering a `stopped` state: the attempt keeps its honest duration while the result (sent, falls, problem) is entered after climbing down.

"Cancel/reset" is a separate need: an attempt started by mistake, or on the wrong problem, should be discardable without leaving a bogus zero-result attempt in the session.
Open: discard means soft-delete the running attempt, versus resetting its beginning to now (restart the clock); possibly both, as "Discard" and "Restart".
Session-level reset (discard a whole session started by mistake) is not clearly asked for; confirm before adding.

### Visual differentiation from the workout screen

User, verbatim: "Need more visual differentiation between bouldering and workout".

The bouldering screen deliberately "mirrors the workout screen's state machine and visual language" (`boulder.clj` docstring), down to the same neon-cyan primary buttons and toggles.
At a glance, with a session running, the two read as the same screen.
Interpretation: keep the shared interaction model, give each activity its own identity — an accent color (the entities dashboard already gives bouldering neon-lime per [063](./063-qol-quick-actions.md)), header icon, and title treatment.
Approach: mock up and review with the user before changing; check against [087](./087-essential-information-redesign.md) so the redesign does not undo it.

### Delete confirmation is unstyled

User, verbatim: "Delete model on bouldering isn't styled" (read as "modal").

Bouldering edits (attempts, sessions, problems) go through the generic CRUD forms, whose delete uses the browser's native `confirm()` (`crud/forms.clj:180`, and three places in `crud/views.clj`).
So this is app-wide, not bouldering-specific, and it is tracked in [067](./067-global-action-modal.md), which now lists CRUD delete-confirm as a first consumer.
