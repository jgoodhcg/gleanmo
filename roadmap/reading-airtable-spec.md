# Reading Airtable Schema (Reference)

## Work Unit Summary
- Status: active
- Problem / intent: Preserve Airtable schema details needed for reading migration and Malli mapping.
- Constraints: Keep the source fields intact for accurate enum mapping and import planning.
- Proposed approach: Maintain this file as the canonical Airtable field reference and link from the reading roadmap.
- Open questions: Which Airtable values need normalization vs passthrough?

Source: Airtable API field definitions for the reading base (books + reading logs). Keep this intact for migration planning and Malli schema mapping.

## Tables

### reading-log

| Field Name | Field ID | Type | Description / Notes | Example / Values |
| --- | --- | --- | --- | --- |
| id | fldlqIyBGNstQkbs5 | Auto Number (number) | Auto-incrementing counter | 1, 2, 3 |
| book | fldmuMeQGzWSLspmB | Link to another record (array of record IDs) | Links to books table | `["rec8116cdd76088af", "rec245db9343f55e8", "rec4f3bade67ff565"]` |
| beg | fldGXP0Cg9Z2qZwRB | Date and time (ISO 8601) | UTC datetime | `"2014-09-05T07:00:00.000Z"` |
| location | fld74KPIkqqn5m7T1 | Single select (string) | Exact match unless typecast=true | Dog park, Bed, Stressless wing chair, Car, Chair, Gym, Kaiti’s bed, couch, Other, Porch, Beach, Desk (gaming), Deck, Hammock |
| format | fldujIp5V9Rly2PY7 | Single select (string) | Exact match unless typecast=true | audiobook, paperback, hardcover |
| end | fldesZ22OUC6CAg0A | Date and time (ISO 8601) | UTC datetime | `"2014-09-05T07:00:00.000Z"` |
| finished | fldv8bjBZhIxad90g | Checkbox (boolean) | true when checked, else empty | `true` |

### books

| Field Name | Field ID | Type | Description / Notes | Example / Values |
| --- | --- | --- | --- | --- |
| title | fldQNPc78HtHmZhLS | Text (string) | Single line | 2150 A.D, Methuselah's Children, The composting Handbook |
| author | fldzOyjQSCvkhmAVN | Long text (string) | May include mention tokens | Thea Alexander, Robert A. Heinlein, Robert Rynk |
| formats | fldjkv0wFuaoFsm44 | Multiple select (array of strings) | Exact match unless typecast=true | audiobook, paperback, hardcover |
| published | fld3LC8JmkRmWHU9n | Date (ISO 8601) | UTC date | 1971-01-01, 1958-01-01, 2016-05-10 |
| from | fldnIV43jdTBZuCx1 | Single select (string) | Exact match unless typecast=true | Library of America, Amazon, Audible, Barnes and Nobles (woodland mall), The Gallery Bookstore (Chicago), Curious Book Shop (East Lansing), Argos Comics and Used Books (Grand Rapids), Kurzgesagt Shop, GRPL Friends of the library sale, Black Dog Books and Records (Grand Rapids), Schuler Books |
| reading-log | fleyoOpPZffMhcvsP7 | Link to another record (array of record IDs) | Links to reading-log table | `["rec8116cdd76088af", "rec245db9343f55e8", "rec4f3bade67ff565"]` |
| notes | fldMhL9zAusHxXZyp | Long text (string) | May include mention tokens | Lorem ipsum… |

## Migration + Malli Notes (to-be-done)
- Map single-select and multi-select values to enums; decide how to handle new/unknown values (e.g., keep raw string, map to :other, store original value).
- Preserve Airtable lineage fields in the target schema (:airtable/id, :airtable/created-time, :airtable/ported-at) for repeatable backfills.
- Determine relationship direction in Gleanmo: likely `reading-log` references `book` (single) and not vice versa; we can store a derived `:book/reading-log-ids` only if needed for UI.
- Normalize `beg`/`end` to `:reading-log/beginning` / `:reading-log/end` and decide timezone handling (Airtable timestamps are UTC).
- Add optional “finished” boolean to either the log or the book; keep provenance if moved.
