# Reading Schema Proposal (Malli Draft)

## Work Unit Summary
- Status: idea
- Problem / intent: Draft Malli schemas for reading entities aligned with Airtable data.
- Constraints: Maintain Airtable lineage fields and normalize enums without losing source data.
- Proposed approach: Finalize book + reading-log schemas, then map import converters to these fields.
- Open questions: Should we store reverse links (`:book/reading-log-ids`) or keep that UI-derived?

Work-in-progress proposal for book and reading-log Malli schemas, aligned to the Airtable source fields and ingest requirements. Keep this doc for the next session.

## Book (`:book`)
Required:
- `:xt/id`
- `::sm/type [:enum :book]`
- `::sm/created-at`
- `:user/id`
- `:book/title`

Optional:
- `:book/author` (string)
- `:book/formats` `[:set [:enum :audiobook :paperback :hardcover]]` (Airtable multi-select)
- `:book/published` `:local-date`
- `:book/from` `[:enum :library-of-america :amazon :audible :barnes-and-nobles-woodland-mall :the-gallery-bookstore-chicago :curious-book-shop-east-lansing :argos-comics-and-used-books-grand-rapids :kurzgesagt-shop :grpl-friends-of-the-library-sale :black-dog-books-and-records-grand-rapids :schuler-books :other]` (normalized single-select)
- `:book/notes` (string)
- Lineage: `:airtable/id` (string), `:airtable/created-time` (:instant), `:airtable/ported-at` (:instant)
- (Optional) `:book/reading-log-ids [:set :reading-log/id]` if we want reverse linkage for UI only

## Reading log (`:reading-log`)
Required:
- `:xt/id`
- `::sm/type [:enum :reading-log]`
- `::sm/created-at`
- `:user/id`
- `:reading-log/book-id` (:book/id)

Optional:
- `:reading-log/beginning` :instant (Airtable `beg`)
- `:reading-log/end` :instant
- `:reading-log/location` `[:enum :dog-park :bed :stressless-wing-chair :car :chair :gym :kaitis-bed :couch :other :porch :beach :desk-gaming :deck :hammock]`
- `:reading-log/format` `[:enum :audiobook :paperback :hardcover]` (single-select)
- `:reading-log/finished?` :boolean (checkbox)
- Lineage: `:airtable/id` (string), `:airtable/created-time` (:instant), `:airtable/ported-at` (:instant)
- (Optional) `:reading-log/timer-id` to link to generic timers without changing ingest

## Mapping / Validation Notes
- Normalize Airtable single/multi-selects to the enums above; unknowns can map to `:other` or be rejected depending on strictness.
- Keep `:airtable/*` optional for native entries; ingest should always populate them.
- Airtable timestamps are UTC → use `:instant`. Published dates are dates → `:local-date`.
- Ingest should use deterministic UUIDs per Airtable record to stay idempotent.
