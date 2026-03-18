---
title: "Activity Timeline"
status: draft
description: "Chronological timeline view with day separation, visual design, and quick edit access"
tags: [area/frontend, area/ux, type/feature]
priority: medium
created: 2026-03-17
updated: 2026-03-17
---

# Activity Timeline

## Intent

A dedicated timeline view that shows all logged activities chronologically with clear day separation. Unlike the recent activity feed on the dashboard (which is limited and mixed with other content), this is a focused, scrollable timeline for reviewing and editing past entries. It should be visually pleasing and make it easy to find and correct entries.

## Specification

### Core Features

**Chronological ordering**:
- Primary sort: entity timestamp field (habit-log/timestamp, meditation-log/beginning, etc.)
- Fallback: ::sm/created-at for entities without a timestamp
- Most recent at top, infinite scroll or pagination for older entries

**Day separation**:
- Clear visual boundaries between days
- Date headers (e.g., "Today", "Yesterday", "Monday, March 14")
- Sticky headers while scrolling within a day
- Optional: collapsible day sections

**Entity display**:
- Same visual style as dashboard recent activity feed but expanded
- Show entity type, primary field, timestamp, duration (where applicable)
- Color coding by entity type (reuse existing accent system)
- Relative time ("2 hours ago") alongside absolute time

**Quick edit access**:
- Click any item to go directly to edit form
- Consider inline editing for simple fields (timestamp adjustment)
- Keyboard navigation support

### Visual Design Goals

- Clean, spacious layout that's easy to scan
- Timeline spine connecting entries within a day
- Smooth transitions and micro-interactions
- Mobile-responsive: works well on phone for quick reviews
- Dark mode native (matches app theme)

### Filtering and Scope

- Filter by entity type (show only habit-logs, etc.)
- Date range picker for historical browsing
- Search within visible entries (by label, notes, etc.)
- Related: [search-filter.md](./search-filter.md) for search infrastructure

## Validation

- [ ] Timeline renders with correct chronological ordering
- [ ] Day boundaries are visually distinct
- [ ] All entity types display correctly with their primary fields
- [ ] Edit links navigate to correct forms
- [ ] E2E: scroll through multiple days of activity
- [ ] Mobile: usable on phone viewport

## Scope

**In scope**:
- Timeline view route and page
- Day-separated chronological display
- Entity type filtering
- Date range navigation
- Click-to-edit functionality

**Out of scope**:
- Inline editing (future enhancement)
- Real-time updates (refresh-based for now)
- Advanced analytics (belongs in stats pages)
- Export functionality

## Context

- Current recent activity feed: `src/tech/jgood/gleanmo/app/overview.clj` (render-activity-feed)
- Entity timestamp extraction: `activity-time` function in overview.clj
- Accent colors already defined in `recent-activity-accents`
- Related work: [workflow-optimization.md](./workflow-optimization.md) for dashboard improvements

## Open Questions

- Should this replace or supplement the recent activity on the dashboard?
- How many days/entries to load initially? Lazy-load as user scrolls?
- Should there be a "jump to date" picker?
- How does this relate to calendar views for each entity type?

## Notes

### Design Inspiration

- Similar to GitHub activity feed or Twitter timeline
- Day headers similar to iOS Photos "Years/Months/Days" view
- Consider vertical timeline with connecting spine

### Implementation Notes

- Could reuse `dashboard-recent-entities` with higher limit
- May need indexed queries for efficient date-range filtering
- Consider URL params for filter state (shareable links)
