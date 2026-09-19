---
title: "Dream log"
status: draft
description: "A dream-log entity for recording dreams, so they can later be mined for recurring themes"
created: 2026-09-18
updated: 2026-09-18
tags: [schema, new-entity, journal]
priority: low
---

# Dream log

## Intent

Dreams have no home in Gleanmo. A dedicated log puts them next to sleep,
mood, and symptom data, and gives any later pass looking for recurring themes
a structured source to read.

## Specification

- New `dream-log` entity: `timestamp`, `time-zone`, `notes` (the dream text),
  optional `label`, optional `lucid` boolean, optional `vividness` or mood
  rating if one earns its place (see AGENTS.md "Ratings").
- Follows the AGENTS.md new-entity checklist (schema, registration, crud
  routes, activity-logs dashboard, sidebar quick add, smoke test).

## Validation

- [ ] Schema registered and CRUD form round-trips
- [ ] Appears on the activity logs dashboard and quick add
- [ ] `e2e/scripts/test-smoke.ts` covers it

## Scope

Theme extraction, tagging, and any AI summarization are out of scope — they
are the follow-up this entity enables.

## Context

- Importing dream records kept elsewhere is a separate decision.

## Open Questions (draft only)

- Is there an existing archive of dreams to import, and in what format?
- Tags / recurring people and places as fields, or notes only?
- Any rating worth recording at log time?
