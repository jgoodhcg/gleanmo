---
title: "Reading Log Page Numbers"
status: draft
description: "Add page number tracking to reading-log schema"
tags: [reading-log, schema, tracking]
priority: low
created: 2026-04-03
updated: 2026-04-03
---

# Reading Log Page Numbers

## Intent

Add page number tracking to reading logs to measure reading progress and calculate reading speed (pages per session).

## Specification

Extend the `reading-log` schema to include page number fields, enabling users to track where they started and finished in each reading session.

## Schema Modeling Options

### Option 1: Start + End Page (Recommended)
```clojure
[:reading-log/start-page {:optional true, :crud/priority 5} :int]
[:reading-log/end-page {:optional true, :crud/priority 6} :int]
```
**Pros:** 
- Calculates pages read per session (`end - start`)
- Enables reading speed metrics (pages/hour)
- Works well with timer data
- Natural for session-based tracking

**Cons:** 
- Two fields to fill
- May not fit audiobook format well

### Option 2: Single Current Page
```clojure
[:reading-log/current-page {:optional true, :crud/priority 5} :int]
```
**Pros:** Quick entry, minimal friction
**Cons:** Can't calculate pages read in session, loses granularity

### Option 3: Pages Read Count
```clojure
[:reading-log/pages-read {:optional true, :crud/priority 5} :int]
```
**Pros:** Single field, directly captures progress amount
**Cons:** No absolute position tracking, harder to correlate with book length

### Option 4: Percentage-based
```clojure
[:reading-log/progress-percent {:optional true, :crud/priority 5}
 [:and :int [:>= 0] [:<= 100]]]
```
**Pros:** Format-agnostic (works for ebooks, audiobooks, PDFs)
**Cons:** Loses page granularity, requires book total page count

### Option 5: Format-aware (Hybrid)
```clojure
[:reading-log/start-page {:optional true, :crud/priority 5} :int]
[:reading-log/end-page {:optional true, :crud/priority 6} :int]
[:reading-log/audiobook-chapter {:optional true, :crud/priority 7} :string]
[:reading-log/audiobook-timestamp {:optional true, :crud/priority 8} :string]
```
**Pros:** Handles all formats properly
**Cons:** Complex schema, conditional field rendering needed

## Validation

- [ ] Schema updated in `src/tech/jgood/gleanmo/schema/reading_schema.clj`
- [ ] CRUD form renders page fields with appropriate input type (number)
- [ ] `just lint-fast src/tech/jgood/gleanmo/schema/reading_schema.clj` passes
- [ ] Smoke test at `/app/crud/form/reading-log/new` shows field correctly
- [ ] Validation: end-page >= start-page (if both present)

## Scope

**In scope:**
- Schema field addition
- CRUD form integration
- Basic display in reading log list view

**Out of scope:**
- Reading speed calculations (future work)
- Progress visualizations (future work)
- Book total page count tracking
- Percentage auto-calculation

## Context

- Current schema: `src/tech/jgood/gleanmo/schema/reading_schema.clj`
- Timer integration: reading-logs already support `:reading-log/beginning` and `:reading-log/end`
- Similar patterns: meditation logs have duration calculation from timestamps
- Airtable lineage: existing reading-logs have `:airtable/id`, `:airtable/ported-at`

## Open Questions

- [ ] Which option best fits your reading habits? (Recommendation: Option 1 for session tracking)
- [ ] Should page fields be required or optional?
- [ ] Any interest in audiobook-specific tracking (chapters, timestamps)?
- [ ] Should we validate `end-page >= start-page` at the schema level or just UI?
- [ ] Want reading speed metrics (pages/hour) in future visualizations?
