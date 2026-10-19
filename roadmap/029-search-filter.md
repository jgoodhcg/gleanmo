---
title: "Search Tool for CRUD and Timer Views"
status: draft
description: "Search entity labels before creating duplicates, across CRUD lists and timer views"
tags: []
priority: medium
created: 2026-02-02
updated: 2026-10-18
---

# Search Tool for CRUD and Timer Views

## Work Unit Summary
- Problem / intent: Users need quick filtering of entity lists by label, especially useful for projects with many entries
- Constraints: Must work across all CRUD view pages and timer pages; maintain existing UI patterns
- Proposed approach: Add label search; choose client-side filtering or server-side search based on list size and pagination.
- Open questions: Should search filter all fields or just labels? Where should search input be positioned?

## Notes

### Entity management need (2026-10-18)

Before creating an exercise, the user needs to check whether it already exists by trying different labels or fragments.
The current interface does not make that lookup practical.
Search must cover the user's accessible entities of that type, including records outside the displayed page.
Filtering only the current page cannot establish whether an exercise already exists.

Search helps prevent duplicates; [Entity Merge](./042-entity-merge.md) handles duplicates already created.
Keep both work units generic across entity types.

### Validation to include when refined

- Find an existing exercise by label or fragment before creating another exercise.
- Find a matching exercise outside the initial page of results.
- Try several search terms and clear the search without losing access to the full list.
- Respect ownership and existing visibility settings in search results.

### Open questions for entity lookup

- Is search on the entity list sufficient, or does the create flow also need an existing-entity lookup?
- Is case-insensitive substring matching sufficient, or are typo tolerance and alternate labels needed?

### Use Cases
- Quickly find a specific project among many projects
- Filter habits by name when the list is long
- Locate tasks, medications, or other entities without scrolling

### Implementation Options

#### Client-Side Filtering
- Filter DOM elements in place using JavaScript
- Works with existing server rendering
- Fast for moderate datasets (<1000 items)

#### Server-Side Search
- Add query parameter to CRUD routes
- Better for large datasets
- Requires backend changes to all CRUD handlers

### UI Placement
Options for search input location:
- Page header (above tabs/actions)
- In table header next to column headers
- Floating or sticky position

### Search Scope
- Label-only: Fastest, most common use case
- Label + notes: More comprehensive, useful for keyword search
- All fields: Maximum flexibility, potentially confusing

### Timer Pages
Timer pages have a different structure than CRUD views:
- Display active/inactive timers
- May need separate search implementation
- Consider unified approach for consistency

**Update 2026-07-27:** the timer case ships with `066-unified-timer-page.md` —
a client-side substring filter over the combined parent list, implemented as
a generic `data-filter-list` behavior in `main.js`.
CRUD list views can reuse that primitive when all searchable records are loaded.
Paginated lists need search across the full accessible dataset.
What remains here is the CRUD-view rollout (placement, scope-beyond-labels, large-list handling).
