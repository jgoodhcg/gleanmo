---
title: "Ownership checks on relation fields"
status: draft
description: "Accept a related-record id on write only when that record belongs to the same user, in CRUD forms and the custom timer, bouldering and workout handlers"
created: 2026-10-15
updated: 2026-10-15
tags: [security, crud]
priority: low
---

# Ownership checks on relation fields

## Intent

Finding 8 of the 2026-10 security audit ([097](./097-security-audit.md)).
Finding 1 made every write check that the record being written belongs to
the user. The ids *inside* that record — a habit log's habits, a timer's
parent and location, an attempt's problem, a workout line's exercise — are
still taken as given. A record can therefore point at another user's record,
and list views then show that record's label. Nothing of the other user's
changes; the risk is Low because the id has to be known first.

## Specification

Any write that stores a relation id checks that the target exists, has the
expected entity type, and has the same `:user/id` as the writer. A failed
check is a 400 (or the form re-rendered with an error), not a silent drop.

Paths to cover:

- Generic CRUD create/update: `:single-relationship` and
  `:many-relationship` fields after form conversion (`crud/handlers.clj`)
- Timer start: `parent-id` and `location-id` (`timer/routes.clj`)
- Bouldering attempts: `problem-id` (`app/boulder.clj`)
- Workout lines: `exercise-id` (`app/workout.clj`)
- Agent API `tx` endpoint, if it does not already check relation targets

One shared check, ideally a batch query in `db/queries.clj` taking the user,
the expected type and a set of ids, rather than a lookup per id. Read-side
belt and braces: relation label formatting
(`crud/views/formatting.clj`) could skip targets owned by someone else.

## Validation

- [ ] Unit tests per path: a foreign id is refused, an own id is accepted, against a real XTDB node
- [ ] `just validate` and `just e2e-test-all` green

## Scope

Write-side checks for new writes. Existing documents are not audited or
rewritten.

## Context

- Audit record: [097-security-audit.md](./097-security-audit.md)
- Record-ownership checks from finding 1: `git log dev..security-audit`

## Open Questions (draft only)

- Should the read-side formatter also refuse foreign labels, or is the write check enough?
- Does the agent API `tx` endpoint already validate relation targets?

## Notes

- 2026-10-15: roadmapped rather than fixed in the audit branch — it touches
  every relation write path, which is more than a Low finding warrants there.
