---
title: "Inline Related Entity Creation"
status: ready
description: "Create related entities mid-form via HTMX inline expansion — no bounce, no form-state loss, auto-select on return; bridges search-empty state to creation"
created: 2026-03-15
updated: 2026-07-18
tags: [ux, forms, crud, relationships, htmx, timers, search]
priority: high
---

# Inline Related Entity Creation

## Intent

When a user fills out a form and discovers a needed related entity doesn't exist,
they should be able to create it **in place** — without bouncing through a
full-page redirect, without losing the form state they've already filled in, and
with the new entity auto-selected when the mini-form closes.

This mirrors the design philosophy of the workout flow (one screen, no bouncing
between generic CRUD forms — see `src/tech/jgood/gleanmo/app/workout.clj:1-21`)
and applies it to the generic CRUD system for any relationship field that opts
in via `:crud/inline-create`.

## Background: what's there today

A stub already exists. `inline-create-link` at
`src/tech/jgood/gleanmo/crud/forms/inputs.clj:245-257` renders a "+ New
\<entity\>" hyperlink that bounces to the related entity's full new-form with a
`redirect` query param pointing back. Two limitations are called out in its own
docstring:

1. **Form state is lost** across the bounce — anything the user typed is gone.
2. **No auto-select** of the freshly-created entity — the user must reopen the
   dropdown and find the new record (it sorts to the top by recency, but the
   user still has to look).

Coverage today is partial. The `:crud/inline-create true` flag is set on four
fields — `:exercise-set/session-id`, `:exercise-line/set-id`,
`:boulder-attempt/session-id`, `:symptom-log/episode-id` — but **not** on the
fields users hit most often when discovering a missing entity mid-flow:
`:exercise-line/exercise-id` (mid-workout), `:task/project-id`,
`:project-log/project-id`, `:reading-log/book-id`, `:meditation-log/type-id`,
`:medication-log/medication-id`, `:habit-log/habit-ids`. The workout flow also
has its own hand-rolled variant of the same broken pattern at
`app/workout.clj:352-358`.

**No "create from empty" pattern exists anywhere in the app today.** Every
"no results" surface is either bare text or a bounce-link:

- `src/tech/jgood/gleanmo/timer/routes.clj:343-344` — timer dashboard empty
  state: `"No <parent>s found. Create some first!"` with **no button, no link,
  no escape hatch**. A user who clicks "project log" in the sidebar Quick Add
  (`app/shared.clj:127-140`) with zero projects lands here and is stuck.
- `app/workout.clj:355-357` — workout picker when zero exercises exist: text
  plus a bounce-link.
- `app/task_focus.clj:324` — `"No tasks match these filters."` (informational
  only).

Inline-create will be the first surface in the app that merges "no results"
with "create one." The pattern it sets will shape every future empty state.

## Specification

### UX decision: inline expansion, not modal or drawer

Resolves the prior draft's first open question.

**Choice:** inline expansion via HTMX swap, replacing the select with a
mini-form in place.

**Why:**
- The workout ns docstring explicitly names "one screen … instead of bouncing
  between generic CRUD forms" as the design intent for the most active flow;
  this feature should be consistent with that.
- Mobile ergonomics — modals are awkward on phone screens (especially
  mid-workout with gloves/sweat). Inline expansion is mobile-native.
- The only existing modal (`app/calendar.clj:92-97`) is a different shape
  (rich event editor). A mini-form with 1–3 fields doesn't justify the overlay.
- Reuses Choices.js + HTMX already in the codebase; no new JS dependency.

### Interaction shape

1. Relationship select renders as today (Choices.js, searchable via
   `:data-enhance "choices"`).
2. **Two affordances to open the mini-form** (both lead to the same endpoint):
   - **Button-below-select** (always visible when `:crud/inline-create true`
     is set): a "+ New \<entity\>" button replacing today's stub link.
   - **Search-empty affordance** (when the user has typed a search term that
     produces no matches): the Choices.js dropdown footer is replaced with a
     `+ Create "<typed text>"` row, seeded with the typed string. This is the
     natural bridge between search and create — it converts a dead-end search
     into a creation path with zero extra typing. See "Search integration"
     below.
3. Clicking either affordance issues an HTMX GET to a new inline-create
   endpoint. The `label` query param is populated from the typed search text
   when the search-empty path was taken; otherwise it is empty.
4. The endpoint returns a mini-form HTML fragment swapped into a `<div>`
   below the select (target via `hx-target`, swap via `outerHTML`).
5. The mini-form has two tiers:
   - **Quick-create tier** (default): just the `label` field (or whatever
     `:crud/quick-create-fields` declares). Used when the schema has a label
     field; this is the ~80% case.
   - **Full tier:** a "More fields" toggle that swaps in the full
     `schema->form` for that entity, for cases where required non-label
     fields exist or the user wants to fill in detail. Some entities
     (e.g. `book`, whose `:book/label` is optional and `:book/title` is
     required) skip the quick-create tier and open directly at the full tier.
6. Submit posts to the inline endpoint. Two outcomes:
   - **Success:** returns the parent `<select>` re-rendered with the new
     `<option>` present and `selected`, plus a small `<script>` that
     re-initializes Choices.js on the new select. The mini-form div is
     emptied. Other parent-form fields are untouched.
   - **Validation error:** re-renders the mini-form fragment in place with
     inline error messages. User stays in the mini-form until success or
     explicit cancel.
7. Cancel link clears the mini-form div via HTMX swap.

### Search integration

This work unit and `roadmap/search-filter.md` share a UI primitive (a
text-input-with-results-list) but operate at different layers:
`search-filter.md` is about narrowing list views; this work unit is about
expanding from a select inside a form. They are complementary, not
overlapping.

The single integration point is **the Choices.js dropdown's empty-results
state**. Today `initializeChoices` at `resources/public/js/main.js:250-273`
does not override `noResultsText` or `renderNoResults`, so users see the
library default ("No results found") with no escape hatch. This work unit
adds a Choices.js configuration hook so that any `select[data-enhance="choices"][data-inline-create]`
gets:

- A custom `renderNoResults` (or `noResultsText`) that includes a clickable
  `+ Create "<typed text>"` row.
- A small event listener that captures the typed search string at the moment
  of the no-results state, so it can be passed as the `label` prefill to the
  inline-create endpoint.

Risk: Choices.js is loaded **unpinned from latest** at
`src/tech/jgood/gleanmo/ui.clj:49` (no version in the URL). The
no-results/custom-dropdown-footer API surface could shift under us. Pinning
the version is out of scope for this work unit but should be noted as a
pre-req or follow-up.

### Schema opt-in

- `:crud/inline-create true` — enables the feature (existing flag).
- **NEW** `:crud/quick-create-fields [:<entity>/label]` — optional vector of
  field keys to include in the quick-create tier. Defaults to `[:<entity>/label]`
  when the schema declares one, otherwise the quick-create tier is omitted and
  the mini-form opens directly at the full tier.

### New endpoints

Both live alongside the existing `/app/crud/form/<entity-str>/new` route and
share logic with `handlers/create-entity!` — extracted, not duplicated.

- `GET  /app/crud/inline/<entity-str>/new?label=<prefill>&<other-params>` —
  returns the mini-form fragment only (no page wrapper, no sidebar).
- `POST /app/crud/inline/<entity-str>` — creates the entity, returns either
  the swap response (success) or the re-rendered mini-form (validation error).

Routes are added to `src/tech/jgood/gleanmo/crud/routes.clj` so every entity
gets them for free, parallel to the existing `gen-routes` shape.

### Form state preservation

The mini-form lives in the same DOM as the parent form. Other fields are
never touched — no state loss, no client-side marshalling needed.

### Auto-select on success

The POST success response includes:
- An `outerHTML` swap of the parent `<select>` with the new `<option>` included
  and marked `selected`.
- A `<script>` that calls `initializeChoices` (the existing function at
  `resources/public/js/main.js:250`) on the new select, then sets Choices.js's
  selected value to the new entity id.

No `HX-Redirect` on the success path. The page never reloads.

### Workouts flow integration

`app/workout.clj` uses Choices.js directly (not via `crud/forms/inputs.clj`)
and has its own hand-rolled "Create one" link at line 352-358. Phase 2 of this
work unit replaces that with the shared inline-create component, so
mid-workout "trying a new movement" follows the same flow as everywhere else.

This is the highest-impact testbed:
- Mid-workout is high-friction (sweaty hands, gloves, time pressure).
- The exercise picker is searchable already, so the "no search match → create"
  transition is natural.
- The current gap (`:exercise-line/exercise-id` has no `:crud/inline-create`
  flag) means the stub link doesn't even appear there today.

### Timer integration

Timer new-forms are ordinary CRUD new-forms. Starting a timer is just a deep
link to `/app/crud/form/<entity-str>/new?<rel-param>=<parent-id>&redirect=/app/timer/<entity-str>`
(see `src/tech/jgood/gleanmo/timer/routes.clj:250-270`). Inline-create on a
timer entity's relationship fields therefore works for free once the generic
infrastructure lands — no timer-specific code.

**Timer-entity relationship fields to opt in** (all reference label-bearing
entities, so all get the quick-create tier):

| Field | → Parent | Schema location |
|---|---|---|
| `:project-log/project-id` | `project` | `schema/project_schema.clj:24` |
| `:project-log/location-id` | `location` | `schema/project_schema.clj:28` |
| `:reading-log/book-id` | `book` | `schema/reading_schema.clj:46` (full-form tier — `book/label` optional, `book/title` required) |
| `:reading-log/location-id` | `location` | `schema/reading_schema.clj:50-51` |
| `:meditation-log/type-id` | `meditation` | `schema/meditation_schema.clj:38-39` |
| `:meditation-log/location-id` | `location` | `schema/meditation_schema.clj:32` |

**Related but separate: the timer dashboard empty state** at
`src/tech/jgood/gleanmo/timer/routes.clj:336-344`. When a user has zero parent
entities (no projects, no books, no meditations), they hit bare text with no
button. This is the same shape of dead-end as the workout picker
(`workout.clj:352-358`) but worse — at least the workout picker has a
bounce-link.

Scope decision for this work unit (recorded in
`.decisions/timer-dashboard-inline-create.json`): **tactical fix in scope,
real inline-create deferred to a follow-up work unit.** Concretely:

- **In scope here:** replace the bare text at `timer/routes.clj:343-344` with a
  bounce-link to the parent entity's new-form with `redirect` back to the
  timer dashboard (mirrors the workout picker at `workout.clj:355-357`).
  Two-line tactical fix; no infrastructure; removes the dead-end today.
- **Out of scope here:** true inline-create on the timer dashboard
  (`render the parent picker + mini-form inline on the dashboard itself`).
  This is a list-view surface, not a form surface, and the spec's
  "form-only" boundary (see Scope) exists to keep this work unit bounded.
  Tracked as a follow-up work unit `timer-dashboard-inline-create.md`.

## Validation

- [ ] Unit: inline-create GET returns a mini-form fragment with the label field
  pre-populated from the `label` query param.
- [ ] Unit: inline-create POST creates the entity and returns a swap response
  containing the new `<option>` and a Choices.js re-init script.
- [ ] Unit: inline-create POST with invalid data re-renders the mini-form with
  errors and does **not** create the entity.
- [ ] Unit: entity with no label field falls back to full-form tier (no
  quick-create tier). Cover with `book` (`book/title` required, no usable
  `book/label`).
- [ ] Unit: Choices.js `noResultsText`/`renderNoResults` hook captures the
  typed search string and renders a `+ Create "<text>"` affordance.
- [ ] E2E (generic CRUD): from `/app/crud/form/exercise-line/new`, fill in
  other fields, click "+ New exercise", submit quick-create, verify the
  exercise select now shows the new exercise selected and the other fields
  retain their values, submit the line, verify the line references the new
  exercise.
- [ ] E2E (search-empty path): on the same form, type a non-matching search
  into the exercise select, verify the `+ Create "<text>"` affordance
  appears, click it, verify the mini-form's label field is pre-populated with
  the typed text, submit.
- [ ] E2E (workout flow): start a session, on the line form click "+ New
  exercise" with a pre-typed search term, submit, verify the new exercise is
  selected and `exercise-memory` still prefills reps/weight on the next add.
- [ ] E2E (timer entity): from `/app/crud/form/project-log/new`, click
  "+ New project", submit quick-create, verify the project is selected and
  the project-log form retains other field values.
- [ ] E2E (bouldering): create a boulder-attempt, inline-create the missing
  boulder-session, verify the attempt references it.
- [ ] E2E (timer dashboard tactical fix): with zero projects, visit
  `/app/timer/project-log`, verify the empty state now shows a link to
  `/app/crud/form/project/new?redirect=...` rather than bare text.
- [ ] Visual: before/after screenshots on `/app/crud/form/exercise-line/new`
  (mobile + desktop) using `SCREENSHOT_PHASE=before|after just e2e-test-*`.
- [ ] Visual: before/after screenshots on `/app/exercise/session` (mid-workout
  picker, both collapsed and inline-expanded states, plus the search-empty
  state showing the `+ Create "<text>"` affordance).
- [ ] Visual: before/after on `/app/timer/project-log` empty state.
- [ ] Accessibility: keyboard navigation — Tab to select, type a non-matching
  search, Tab/Enter to the `+ Create "<text>"` affordance, Enter opens
  mini-form, Tab through mini-form fields, Enter submits, Escape/Cancel
  closes, focus returns to the select with the new value selected.

## Scope

**In scope:**
- Two new inline-create endpoints under `/app/crud/inline/`, mounted by
  `gen-routes` so they exist for every entity.
- Replacement of `inline-create-link` in `crud/forms/inputs.clj` with the new
  HTMX-driven component (the old bounce link is removed).
- Generic support for both `:single-relationship` and `:many-relationship`
  fields (many-relationship selects the new entity as one of several).
- Workouts flow integration — replace the hand-rolled "Create one" link at
  `app/workout.clj:352-358` with the shared component.
- Search-empty → create bridge in Choices.js (custom no-results render).
- Add `:crud/inline-create true` to: `:exercise-line/exercise-id`,
  `:project-log/project-id`, `:project-log/location-id`,
  `:reading-log/book-id`, `:reading-log/location-id`,
  `:meditation-log/type-id`, `:meditation-log/location-id`,
  `:task/project-id`, `:medication-log/medication-id`, and (Phase 3)
  `:habit-log/habit-ids`.
- Quick-create (label only) and full-form tiers.
- New optional `:crud/quick-create-fields` schema attribute.
- **Tactical fix:** replace the bare-text timer dashboard empty state at
  `src/tech/jgood/gleanmo/timer/routes.clj:343-344` with a bounce-link to the
  parent new-form (mirrors the workout picker pattern). No new infrastructure.

**Out of scope:**
- Inline **editing** of existing entities (separate work unit).
- Bulk creation of multiple entities at once.
- Nested inline creation (inline form that itself references a missing entity
  — defer to a follow-up; the inline form's relationship fields will use the
  bounce-link fallback for now).
- Inline create from list views or dashboards. The timer dashboard empty state
  gets the tactical bounce-link fix (above); **true inline-create on the
  dashboard surface** is deferred to a follow-up work unit
  `timer-dashboard-inline-create.md`.
- List-view text filtering (tracked separately in `roadmap/search-filter.md`).
- Pinning the Choices.js CDN version (noted as a risk; separate concern).

## Context

- Stub link and current relationship render:
  `src/tech/jgood/gleanmo/crud/forms/inputs.clj:245-322`
- Workouts flow (design inspiration and Phase 2 integration target):
  `src/tech/jgood/gleanmo/app/workout.clj:1-21,197-258,352-358`
- Timer system (relationship-field integration via the same generic CRUD
  path; empty-state tactical-fix target):
  `src/tech/jgood/gleanmo/timer/routes.clj:250-270,303-349`
- Sidebar Quick Add (every entry is a bounce to a full CRUD form; many lead
  into forms whose first field is an empty relationship select):
  `src/tech/jgood/gleanmo/app/shared.clj:127-140`
- Existing `redirect` param wiring (kept for non-inline fallback paths):
  `src/tech/jgood/gleanmo/crud/handlers.clj:69-109,111-172`
- Choices.js init (the re-init function the success script will call; also
  where the `noResultsText` / `renderNoResults` hook will land):
  `resources/public/js/main.js:250-286`
- Choices.js loaded unpinned (risk for the no-results API surface):
  `src/tech/jgood/gleanmo/ui.clj:49`
- Schema opt-in flag usages today: `schema/exercise_schema.clj:65,91`,
  `schema/bouldering_schema.clj:38`, `schema/symptom_schema.clj:59`
- Gaps to fix (high-traffic fields currently without the flag):
  `schema/exercise_schema.clj:93-95` (`:exercise-line/exercise-id`),
  `schema/project_schema.clj:24,28` (`:project-log/project-id`,
  `:project-log/location-id`), `schema/reading_schema.clj:46,50-51`
  (`:reading-log/book-id`, `:reading-log/location-id`),
  `schema/meditation_schema.clj:32,38-39` (`:meditation-log/type-id`,
  `:meditation-log/location-id`), `schema/task_schema.clj:40-41`
  (`:task/project-id`), `schema/medication_schema.clj:29-30`
  (`:medication-log/medication-id`), `schema/habit_schema.clj:37-38`
  (`:habit-log/habit-ids` — Phase 3, `:many-relationship`)
- HTMX modal reference (different shape, but useful for swap conventions):
  `src/tech/jgood/gleanmo/app/calendar.clj:92-97`
- Schema-utils FK detection (drives `:single-relationship` vs
  `:many-relationship` dispatch): `src/tech/jgood/gleanmo/schema/utils.clj:84-111`
- Form pre-population pattern this feature obviates for inline use:
  `src/tech/jgood/gleanmo/crud/forms.clj:44-91`
- Route generation to extend: `src/tech/jgood/gleanmo/crud/routes.clj`
- Task Focus server-side search (the only existing search implementation;
  reference for any future server-side search needs, but this work unit is
  client-side via Choices.js): `src/tech/jgood/gleanmo/app/task_focus.clj:55-63`

## Related work

- `roadmap/search-filter.md` — list-view text filtering. Complementary; shares
  the text-input-with-results-list primitive but operates at a different
  layer. The two work units should land independently and reference each
  other. `search-filter.md`'s "Timer Pages" section explicitly calls out the
  timer dashboard as a separate case — its concerns are addressed here by the
  tactical bounce-link fix and the follow-up `timer-dashboard-inline-create.md`
  work unit.
- Future `roadmap/timer-dashboard-inline-create.md` — true inline-create on the
  timer dashboard's parent-picker (replacing the tactical bounce-link shipped
  by this work unit). Out of scope here.
- `roadmap/keyboard-navigation.md` — keyboard a11y across forms. The
  inline-create component must satisfy its tab-order and focus-return
  requirements; visual and a11y validation steps above cover this.

## Phasing

1. **Phase 1 — Generic inline-create for `:single-relationship`, button path
   only.** Quick-create (label) tier only. Ship exercise-line → exercise as
   the first end-to-end path. Replace the stub link. Add `:crud/inline-create
   true` to `:exercise-line/exercise-id`. Add the same flag to the timer-entity
   relationship fields (`:project-log/project-id`, `:meditation-log/type-id`,
   `:reading-log/book-id`, `:reading-log/location-id`, etc.) since they ride
   the same code path.
2. **Phase 2 — Search-empty → create bridge + workouts integration.** Add the
   Choices.js `noResultsText`/custom-render hook and the `+ Create "<text>"`
   affordance. Replace `app/workout.clj:352-358` hand-rolled link with the
   shared component (which now also picks up the search-empty affordance).
   Ship the tactical timer-dashboard empty-state fix at
   `timer/routes.clj:343-344`.
3. **Phase 3 — Full-form tier and `:many-relationship` support.** Roll out to
   bouldering, symptom-log, `:habit-log/habit-ids`, `:task/project-id`, etc.

## Notes

This spec resolves the four open questions left in the prior draft:

- **Modal vs drawer vs inline expansion?** → inline expansion via HTMX swap.
- **Required fields vs quick-create?** → both, via two-tier (quick-create
  default, "more fields" toggle for full form).
- **How to handle validation errors?** → re-render the mini-form in place;
  never dismiss on error.
- **Quick-create vs full form?** → quick-create when the entity has a label
  field, full form otherwise.

Design intent mirrors the workout ns docstring at
`src/tech/jgood/gleanmo/app/workout.clj:1-21` — "one screen … instead of
bouncing between generic CRUD forms." Inline creation extends that principle
to every relationship field that opts in.

The search-empty → create bridge closes a related loop: every searchable
select in the app today has a hidden dead-end (type a non-matching term and
you get "No results found" with no escape). Converting that dead-end into a
creation path means the same interaction surface — a Choices.js dropdown —
handles find-or-create uniformly. This is also the right place to set the
app's first "no results → create" pattern, since future empty states (list
views, dashboards) will follow the precedent established here.

**Risk register:**
- **Choices.js API drift** (unpinned CDN at `ui.clj:49`) — could shift the
  `noResultsText` / `renderNoResults` surface between versions. Mitigation:
  pin before Phase 2 ships, or write the no-results hook defensively against
  the documented public API only.
- **Form-within-form nesting** — the mini-form lives inside the parent
  `<form>` element. Nested `<form>` tags are invalid HTML and browsers ignore
  the inner one. Mitigation: the mini-form is **not** a `<form>` element; it
  is a `<div>` whose submit button issues an HTMX POST directly (Hiccup
  `:hx-post`), not a native form submit. This is the same pattern Biff uses
  for inline HTMX actions elsewhere.
- **`exercise-memory` cache on the workout picker** — after Phase 2 lands,
  the new exercise won't have memory entries, so the form's reps/weight
  prefill must fall back to defaults cleanly. Already handled by
  `workout.clj:189-194` (memory miss → defaults), but worth a regression
  check in Phase 2 validation.
