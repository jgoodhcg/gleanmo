# Reading Tracker Roadmap

## Objective
Replace Goodreads for daily tracking with the lightest possible feature set: quick book capture (with metadata lookup) and session logging that reuses the existing timer system. Everything else can layer on later.

## Initial Goals
- Capture a title fast, run a metadata lookup, and accept/override suggested fields.
- Start a timed reading session, pick a book by title, and store page/start end markers.
- Keep the schema lean so future rereads, highlights, or analytics can bolt on without churn.

## Minimal Data Model

### book
Single table for everything we know about a work.
```clojure
:book/id            :uuid
:book/title         :string
:book/subtitle      {:optional true} :string
:book/primary-author-name {:optional true} :string
:book/isbn          {:optional true} :string
:book/cover-url     {:optional true} :string
:book/page-count    {:optional true} :int
:book/metadata      {:optional true} :map    ; raw lookup payload (authors, subjects, etc.)
:book/source        {:optional true} [:enum :manual :lookup :import]
:timestamps/created :instant
:timestamps/updated :instant
```
Future derived entities (authors, topics, editions) can be split out of `:book/metadata` once we need them.

### reading-session
Session rows tie the timer to a book without introducing extra modeling.
```clojure
:reading-session/id         :uuid
:book/id                    :book/id
:timer/session-id           {:optional true} :uuid ; pointer to generic timer record
:reading-session/started-at :instant
:reading-session/ended-at   {:optional true} :instant
:reading-session/page-start {:optional true} :int
:reading-session/page-end   {:optional true} :int
:reading-session/notes      {:optional true} :string
```
When we decide to support rereads or per-run status, we can introduce a `reading-item` table (book + user state) and migrate existing sessions by creating one item per distinct book.

## Metadata Lookup Workflow
- Add a **Lookup Metadata** button to the book create/edit form.
- On click, call the external source (Open Library, ISBNdb, etc.), store the raw response in `:book/metadata`, and prefill surfaced fields (title, author, cover, pages).
- Allow re-running the lookup later to refresh info; merged results overwrite surfaced fields but keep manual overrides.
- Manual entry works without the lookup, so offline additions stay snappy.

## Session Flow
- Timer start form includes a book search (title autocomplete over `book` table). Selecting a title persists `book/id` on the session.
- On start, create the `reading-session` with `started-at`, optional `page-start`.
- When the timer stops, patch the same row with `ended-at` and `page-end`, and roll `page-end` back into `book` (or future `reading-item`) to maintain current progress.
- Optional quick actions: “Same as last session” button to reuse `page-start` defaults.

## Implementation Phases
1. **Phase 0 – Minimal Schema & Forms**
   - Create `book` and `reading-session` tables + migrations.
   - Build the book form with metadata lookup (including raw payload storage).
   - Add a lightweight book list for selection/search.
2. **Phase 1 – Timer Integration**
   - Extend generic timer start flow with book search and page start entry.
   - Persist sessions on start/stop and update `book` progress.
   - Expose a simple “recent sessions” view for sanity checks.
3. **Phase 2 – Comfortable Backlog UX (Optional)**
   - Add status fields via `reading-item` if rereads or backlog triage become pressing.
   - Improve book list filters (format, status) leveraging existing metadata.
4. **Phase 3 – Highlights, Imports, Analytics (Future)**
   - Layer in Goodreads import, highlight capture, dashboards, recommendations, etc.

## Expansion Backlog
- Split metadata: promote authors/topics to first-class tables.
- Goodreads/Kindle imports with dedupe heuristics.
- Highlight vault, reviews, and shareable summaries.
- Reading velocity charts leveraging the generic visualization system.

## Open Questions
- Which metadata source(s) fit within current network restrictions?
- How to surface conflicts between manual edits and refreshed lookup data?
- Do we need timer integration hooks for offline/mobile capture?
- At what point do rereads or parallel formats justify introducing `reading-item`?
