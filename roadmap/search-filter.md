---
title: "Search Tool for CRUD and Timer Views"
status: idea
description: "Text search tool for CRUD and timer view pages"
tags: []
priority: medium
created: 2026-02-02
updated: 2026-02-02
---

# Search Tool for CRUD and Timer Views

## Work Unit Summary
- Problem / intent: Users need quick filtering of entity lists by label, especially useful for projects with many entries
- Constraints: Must work across all CRUD view pages and timer pages; maintain existing UI patterns
- Proposed approach: Add client-side text search that filters displayed rows in real-time
- Open questions: Should search filter all fields or just labels? Where should search input be positioned?

## Notes

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
