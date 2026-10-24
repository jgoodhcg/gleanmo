---
title: "Recorded Time Zone on Every Timed Entity"
status: draft
description: "Every entity with a timestamp or interval stores the zone it was recorded in, defaulted from the user setting, used both to build its instants and to render them"
created: 2026-10-24
updated: 2026-10-24
tags: [schema, time-zone, crud, migration, agent-policy]
priority: high
---

# Recorded Time Zone on Every Timed Entity

## Intent

An instant is a point on the UTC timeline. It carries no zone. Converting the
form's local date-time into one uses a zone and then discards it, so
`<entity>/time-zone` is the only place that zone survives. The attribute means
**"this record was recorded in this zone."** It has two jobs:

1. **Write:** interpret the form's local date-time in that zone to build the
   instant.
2. **Read:** render the instant back in that zone, and put it on the right
   local day for charts, goals and statistics.

Two things are wrong today:

- **12 of the 18 timed entities have no `time-zone` attribute at all.** Every
  record of those types renders in whatever the user's zone is *now*. Nothing
  is visibly wrong while the user stays put. After they travel and the user
  setting changes, their whole history shifts to the new zone.
- **The entities that do have it don't use it on write.** The `:instant`
  converter parses submitted date-times in the user's pre-request zone, never
  the zone submitted on the same form. So the first entry after a zone change
  is off by the offset between the two zones.

Nothing in `AGENTS.md` or the new-entity checklist asks for the attribute.
`roadmap/026-roam-integration.md` added `project-log/time-zone` "for
consistency with other log entities", and `085-dream-log.md` plans one, but the
expectation was never written down as a rule. That is how a dozen entities
shipped without it.

## Specification

### 1. Schema: every timed entity declares `time-zone`

Any entity with a `timestamp`, or a `beginning`/`end` interval, declares:

```clojure
[:<entity>/time-zone {:crud/label "Time zone"} :string]
```

It sits directly after the entity's `timestamp` or `end` field.

| Already declares it | Gains it in this unit |
|---|---|
| habit-log, meditation-log, project-log, reading-log, calendar-event (optional), cruddy | symptom-log, symptom-episode, bm-log, mood-log, medication-log, biometric-log, food-log, exercise-session, exercise-set, boulder-session, boulder-attempt, activity-observation |

This list comes from grepping `schema/*.clj` on 2026-10-24. Re-run the check
at implementation time in case entities have been added since.

`calendar-event/time-zone` becomes required once the migration has filled it
(see Open Questions about the ordering).

### 2. Write path: one default, derived in `db/mutations.clj`

Use the `running` flag's mechanism (`roadmap/071-timer-running-flag.md`).
`mutations/create-entity!` fills `<entity>/time-zone` from `user/time-zone`
when the schema declares the attribute and the data omits it. Declaring the
attribute *is* the opt-in, so there is no list of timed types to keep in sync.

This one rule covers every writer without touching each of them:

- **Generic CRUD forms.** These already submit the field.
- **Timer one-tap start.** `timer/routes.clj` `start-timer` already fills it
  when the schema has it.
- **Custom screens with no form.** The workout and bouldering screens create
  sessions, sets and attempts with `(t/now)`.
- **The agent API `tx` endpoint.** It accepts ISO instants with an offset and
  doesn't need a zone to parse them. The stored zone still has to come from
  somewhere.
- **Cronometer sync** (`cronometer/sync.clj` already resolves `user/time-zone`).
  Its policy doesn't change: interpret wall-clock times in the user's zone and
  preserve the source offset without using it. The stored `time-zone` is the
  zone it used.
- **Dev importers and seeds.**

`update-entity!` leaves `time-zone` alone unless the update sets it. A record
keeps the zone it was recorded in.

### 3. Forms: default to the user zone, and parse in the submitted zone

- **New forms** default the zone select to `user/time-zone`. This already
  happens (`crud/forms/inputs.clj:107`).
- **Edit forms** show the record's stored zone. They render its instant inputs
  in that zone, not the user zone (`inputs.clj:67` currently uses the user
  zone).
- **The `:instant` converter** (`crud/forms/converters.clj:85`) parses
  submitted local date-times in the submitted `<entity>/time-zone`. It falls
  back to `user/time-zone` only when the form has no zone field.
- **Changing the zone updates the user setting.** That already happens on
  create and edit (`crud/handlers.clj:102-120`, `:202-220`), but the trigger
  is wrong for edits. Today the handler updates the setting whenever the
  submitted zone differs from the *user* zone. Editing an old travel record
  without touching its zone therefore resets your current zone to the trip's
  zone. The trigger becomes:
  - on create: the submitted zone differs from the user zone;
  - on edit: the submitted zone differs from the record's stored zone (the
    user actually changed it).
- **Custom non-CRUD forms that take a date or time** (meditation,
  medication-history and any bm-log/habit-log screens that parse dates) do the
  same: parse in the entity's zone, which defaults to the user zone.

### 4. Migration `m008-backfill-time-zone`

A migration is needed. Without it, records lacking a zone would need readers
to fall back to "the user's current zone." That is exactly the bug described
above, and it is also the fallback chain `AGENTS.md` forbids ("Readers use the
new attribute only"). Backfilling makes the attribute's claim true for every
record, so readers have one source.

- **Scope:** every document of every timed entity that has no
  `<entity>/time-zone`. That includes any `calendar-event` documents missing
  the optional field.
- **Value:** the owning user's `user/time-zone`, read per document from that
  document's `:user/id`. This is not a hard-coded zone. The backfill is only
  correct if each owner's history was logged in their current
  `user/time-zone`; the user confirmed that for production on 2026-10-24. Seed
  and e2e accounts locally get their own setting.
- **Refuses:** if an owner has no `user/time-zone`, the migration reports
  those documents and writes nothing for them. It does not fall back to UTC.
- **Dry run:** prints, per entity, the count to fill and the zone each owner
  resolves to. Check that the prod dry run shows the expected zone before the
  real run.
- **Idempotent:** a second run finds no documents missing the field and writes
  nothing. Use the same shape as `m007` (an audit query in `db/queries.clj`,
  then writes through `mutations/update-entity!`).
- **No instants change.** Existing instants are already correct. CRUD entries
  were parsed in `user/time-zone`, which has not changed. Airtable
  imports were UTC instants. Cronometer used the user zone. The migration only
  records the zone they were made in.
- Prod runs follow `AGENTS.md` deployment rules: the user copies the prod
  `XTDB_JDBC_URL` into the local `config.env` and runs `--target prod`
  personally.

### 5. Agent instructions

Add the rule to `AGENTS.md` under "Temporal fields and imported values", and
add a step to the new-entity checklist:

- **The rule:** every entity with a `timestamp` or a `beginning`/`end`
  interval declares `<entity>/time-zone`, meaning the zone the record was
  recorded in. Do not omit it because the instant "already has a time zone".
  An instant has none.
- **Checklist step:** declare `[:<entity>/time-zone ...]` beside the
  temporal fields (if the entity has a timestamp or interval). The write-time
  default fills it.
- **Enforcement:** a unit test that walks the schema registry and fails for
  any entity with a `timestamp`/`beginning` field and no `time-zone`. A
  checklist step can be skipped. A failing test can't, which is the point of
  this unit. Required in `test/tech/jgood/gleanmo/test.clj` per "Adding a
  Clojure test namespace."

## Validation

- [ ] Registry test: every entity with `timestamp`/`beginning` declares
      `time-zone`, and fails when one is removed.
- [ ] Mutations test: create without a zone fills `user/time-zone`. Create
      with a zone keeps it. Update without a zone leaves the stored zone.
- [ ] Converter test: a local date-time submitted with `Europe/Lisbon` while
      `user/time-zone` is `America/New_York` becomes the Lisbon instant, not
      the New York one.
- [ ] Handler test: editing a record whose stored zone differs from the user
      zone, without changing the zone, leaves `user/time-zone` unchanged.
      Changing it updates the setting.
- [ ] Edit form renders an instant in the record's stored zone.
- [ ] Workout, bouldering, timer start and API `tx` writes each produce a
      stored zone.
- [ ] `m008` dry run and real run against a local dev copy. A second run
      writes nothing. An owner with no zone is reported and skipped.
- [ ] `just validate`, and `just e2e-test-all` for smoke/timer/workout/boulder
      flows. Add an e2e flow that logs in a non-user zone and checks the
      rendered time.
- [ ] `just e2e-shot-series` before and after, since the zone select appears
      on 12 more forms.

## Scope

- **Not included:** multi-zone display features (a "show in original vs.
  current zone" toggle, a zone column on list views beyond the existing
  `show-tz?` marker).
- **Not included:** changing any stored instant. Only the zone is recorded.
- **Not included:** zone-aware goal and statistics bucketing beyond what
  already reads the entity's zone. Consumers that bucket by the *user* zone
  (goals use their own saved zone by design, see 081) are a follow-up audit,
  listed in Notes.
- `task`, `goal` and other entities without a timestamp/interval are out of
  scope. `goal/time-zone` is a different concept: the zone a goal's windows
  are counted in.

## Context

- `crud/views.clj:303` `entity-time-zone-info`: read path. Entity zone, then
  user zone, then UTC. It shows a marker when the record's zone differs from
  the user's.
- `crud/forms/converters.clj:85`: `:instant` converter, parses in the user zone.
- `crud/forms/inputs.clj:67`, `:107`: instant inputs render in the user zone.
  The zone select defaults to the stored value, then the user zone.
- `crud/handlers.clj:102-120`, `:202-220`: create/edit update `user/time-zone`
  when the submitted zone differs from it.
- `app/shared.clj:338` `get-user-time-zone`: reads from the request's
  `:biff/db` snapshot.
- `timer/routes.clj:545` `start-timer`: already schema-driven zone fill.
- `db/mutations.clj`: write-time `running` derivation, the model for the
  default.
- `dev/tasks/migrations/m007_timer_running_flag.clj`: migration shape to copy.
- `roadmap/026-roam-integration.md:291-292`: zone hierarchy and "for
  consistency with other log entities."
- `roadmap/085-dream-log.md`: planned entity already lists `time-zone`.

## Open Questions (draft only)

- **Required or optional?** After `m008`, every document has a zone, so
  `:string` (required) is accurate and lets validation enforce it. The write
  default in `create-entity!` means new writes never omit it. Ordering risk:
  if the required schema deploys before `m008` runs, editing an old document
  fails validation until the migration runs. That's a visible error, not data
  loss. Options:
  - (a) Deploy and run `m008` back to back.
  - (b) Have `update-entity!` also fill a missing zone. This self-heals but
    makes updates write a field they weren't asked to.
  - (c) Ship optional first, migrate, then make it required.

  Leaning (a).
- **Do the custom workout and bouldering screens need a zone picker?** They're
  one-tap and never show one today. The write default gives them the user
  zone, which satisfies requirement 2 ("the user doesn't have to think about
  this"). Proposal: no picker in this unit.
- **`food-log`, `biometric-log`, `activity-observation` come from Cronometer.**
  Confirm the stored zone should be the user zone used at sync time, not one
  derived from the preserved source offset.

## Notes

- 2026-10-24: raised from "do symptom logs not have a time zone?" The answer
  was no, and the same was true of 11 other timed entities. Four requirements
  from the user, verbatim:
  1. "We need a time zone attribute on each entity with timestamps or interval
     instants so that interpretation can be accurate when creating the instant
     and when rendering it back. The time zone on the entity is meant to say
     'this was the time zone it was recorded in'."
  2. "The default on all forms should be the user time zone setting so that
     the user doesn't have to think about this when logging things. If they do
     change it it should set the user time zone setting."
  3. "We need agent instructions so this kind of lapse doesn't happen again."
  4. "We probably … need a migration to add a time zone to all entities that
     didn't have one." The user confirmed their history was all logged in
     their current user time zone setting.
- Follow-up audit, not in scope: find readers that bucket entity instants by
  `get-user-time-zone` rather than the entity's zone (viz, heatmaps, home
  timeline, statistics in 095). After this unit they have a correct per-record
  zone available.
