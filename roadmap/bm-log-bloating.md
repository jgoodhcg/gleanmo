---
title: "BM Log Bloating Tracking"
status: draft
description: "Add bloating tracking to bm-log schema with flexible modeling options"
tags: [bm-log, schema, tracking]
priority: medium
created: 2026-04-03
updated: 2026-04-03
---

# BM Log Bloating Tracking

## Intent

Add bloating tracking to bowel movement logs to capture a common GI symptom that correlates with other digestive health metrics.

## Specification

Extend the `bm-log` schema to include bloating information, offering users the ability to track this symptom alongside existing fields like bristol scale, pace, and anxiety.

## Schema Modeling Options

### Option 1: Simple Severity Enum (Recommended for v1)
```clojure
[:bm-log/bloating {:optional true}
 [:enum :none :mild :moderate :severe :n-a]]
```
**Pros:** Simple, consistent with existing fields like `:bm-log/anxiety`, easy to visualize
**Cons:** No location detail, binary "none" vs enum may feel inconsistent

### Option 2: Boolean + Severity
```clojure
[:bm-log/bloating? {:optional true} :boolean]
[:bm-log/bloating-severity {:optional true}
 [:enum :mild :moderate :severe]]
```
**Pros:** Clear presence/absence, then severity
**Cons:** Two fields for one concept, more clicks in UI

### Option 3: Location-aware Tracking
```clojure
[:bm-log/bloating {:optional true}
 [:map
  [:severity [:enum :mild :moderate :severe]]
  [:location {:optional true}
   [:enum :upper-abdomen :lower-abdomen :general]]]]
```
**Pros:** Rich data for medical correlation, supports abdominal mapping
**Cons:** Complex nested structure, heavier UI, may be overkill for initial need

### Option 4: Time-aware Tracking
```clojure
[:bm-log/bloating {:optional true}
 [:enum :none :before :during :after :persistent :n-a]]
```
**Pros:** Captures temporal relationship to BM event
**Cons:** May not capture severity, different use case than simple "did you feel bloated"

## Validation

- [ ] Schema updated in `src/tech/jgood/gleanmo/schema/bm_schema.clj`
- [ ] CRUD form renders bloating field with appropriate priority
- [ ] `just lint-fast src/tech/jgood/gleanmo/schema/bm_schema.clj` passes
- [ ] Smoke test at `/app/crud/form/bm-log/new` shows field correctly

## Scope

**In scope:**
- Schema field addition
- CRUD form integration
- Basic visualization (if easy)

**Out of scope:**
- Historical data migration (no Airtable bloating data to import)
- Advanced abdominal mapping visualizations
- Correlation analysis with other symptoms

## Context

- Current schema: `src/tech/jgood/gleanmo/schema/bm_schema.clj`
- Similar enum patterns: `:bm-log/anxiety`, `:bm-log/urgency`
- CRUD form priority: suggest `{:crud/priority 3}` to place after bristol/pace

## Open Questions

- [ ] Which option best matches your tracking needs? (Recommendation: Option 1 for simplicity)
- [ ] Should bloating be a required field or optional?
- [ ] Any interest in location-aware tracking for future medical correlations?
- [ ] Should this integrate with a broader symptom tracking system (see `symptom_schema.clj`)?
