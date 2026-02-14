---
title: "Calendar Implementation Requirements"
status: active
description: "Year-at-a-glance calendar with event interactions and external sync"
tags: []
priority: medium
created: 2026-02-02
updated: 2026-02-14
---

# Calendar Implementation Requirements

## Work Unit Summary
- Problem / intent: Deliver a polished year-at-a-glance calendar with richer event interactions and external calendar sync.
- Constraints: Keep the UI fast in a dense grid, preserve timezone correctness, and avoid breaking existing event CRUD flows.
- Proposed approach: Incrementally expand event rendering (multi-day, icons, click targets), then add overlay features and external sync.
- Open questions: Which sync path is first (iCal URL vs Google API)? What is the preferred visual shape for events in cells?

## ✅ COMPLETED FEATURES

### Core Calendar Functionality
- **Year-at-a-glance view** with months as rows
- **Uniform day cell sizing** using CSS calc
- **Clean border system** without double-thick lines  
- **Weekend highlighting** with subtle background
- **Today highlighting** with neon-gold border (updated from blue background)
- **Event creation modal** with HTMX form submission
- **Real-time updates** - events appear immediately after creation
- **Event visualization** as colored rectangles on day cells
- **Event hover states** - golden borders for visual feedback
- **Day cell hover states** - golden border hover effects
- **Neon color selection** - 6 vibrant color options (Azure, Cyan, Lime, Violet, Pink, Amber)

### Database & Schema
- **Calendar-event entity** (renamed from "events" for future-proofing)
- **iCal/RFC5545 compatible** fields for future sync
- **Timezone-aware** queries using user settings  
- **Event source**: enum currently restricted to `:gleanmo` (MVP)
- **Database functions** for CRUD operations
- **Consistent naming** throughout codebase

### Technical Implementation
- **HTMX integration** for dynamic interactions
- **Modal functionality** working properly (moved outside calendar container)
- **Separate hidden trigger** for real-time refresh (avoids event conflicts)
- **Color consistency** - fixed neon-violet naming throughout
- **Sidebar integration** - calendar events in navigation
- **Security validation** - all forms converted to biff/form for CSRF protection
- **Schema consistency** - updated to use beginning/end fields (generic naming)
- **Test coverage** - comprehensive tests for user isolation, timezone handling, and edge cases

## 🔄 REMAINING TASKS

### High Priority UI/UX Improvements

#### Multi-day Events (Highest Priority)
- **Schema & Forms**: Collect `end` dates in the modal/CRUD flow, validate inclusive ranges, and set defaults for existing entries.
- **Query Logic**: Fetch events whose `[beginning, end)` spans overlap the requested year/month.
- **Rendering**: Expand multi-day events into per-day segments with start/middle/end styling so pills appear contiguous across cells.
- **Styles**: Adjust pill radii and gutters to show span continuity while preserving click targets.
- **Testing**: Add coverage for multi-day spans crossing months, years, and DST transitions.

#### Visual Design
- **Event Shape Updates**: Change from rectangles to dots/bars/other shapes
- **Event Size**: Make events larger and more prominent in day cells
- **Event Clickability**: Individual events should open edit forms (not just day cells)
- **Icon Support**: Add icon selection to event forms (work, personal, travel, medical, etc.)
- **Remove Labels**: Clean up text clutter in event visualization

#### Layout & Navigation  
- **Month Spacing**: Compress months to use less vertical space
- **Navigation Placement**: Move "Back to Home" button to header/corner (currently requires scrolling)
- **Row Alternation**: Add alternating month colors like spreadsheets
- **Weekend Emphasis**: Make weekends stand out more prominently

#### Interaction
- **Event Details**: Hover tooltips (desktop) and tap details (mobile)
- **Event Edit**: Click events to open edit forms
- **Event Delete**: Delete functionality

### Calendar Data Features
- **Configurable Overlays**  
  - **Settings Surface**: Extend calendar settings with `:calendar/time-zone`, optional `:calendar/holiday-region`, and toggles for holidays, seasons, solar, and lunar overlays.  
  - **Holidays**: Use Jollyday to generate read-only holiday markers per user region; render when the toggle is enabled.  
  - **Solar + Seasons**: Leverage `commons-suncalc` to fetch equinoxes/solstices, derive season bands, and display single-day markers.  
  - **Lunar Phases**: Calculate primary moon phases (new, first quarter, full, last quarter) for the selected timezone with `commons-suncalc`; show them as lightweight badges.  
  - **Caching**: Memoize overlay computations per `(user-id, year)` and invalidate when settings change or year navigation shifts.  
  - **Tests**: Add fixtures asserting expected overlays for a representative timezone/region to keep deterministic outputs.
- **Seasonal Data**: First/last frost dates (future)

### Medium Priority Refactoring

#### Code Organization
- **Route Consistency**: Rename from `/big-calendar/*` to `/calendar/*`
- **Function Naming**: Remove `calendar-*` prefixes from internal functions
- **Namespace Alignment**: Follow existing codebase patterns

#### Integration
- **Life Chart Cohesion**: Unified design system with life chart
- **Shared Color Schemes**: Consistent across calendar and other views
- **Navigation Flow**: Seamless movement between calendar views

## 🚀 READY: External Calendar Subscription (iCal Import)

### Intent
Enable read-only visualization of external calendars (Google, Hey, Outlook, Apple) by subscribing to their iCal feeds. Events are imported and displayed alongside native Gleanmo events with subscription-based coloring.

### Schema Changes

**`calendar-event` schema:**
```clojure
[:calendar-event/external-id {:optional true} :string]      ; UID from iCal for deduplication
[:calendar-event/ical-url-id {:optional true} :ical-url/id] ; Links to subscription source

;; Deprecate source field - ical-url-id presence now determines origin
[:calendar-event/source {:optional true :deprecated true :crud/hidden true} [:enum :gleanmo]]
```

**`ical-url` schema:**
```clojure
[:ical-url/color-neon {:optional true} [:enum :blue :cyan :green :violet :red :orange]] ; Subscription color
[:ical-url/last-error {:optional true} :string] ; Sync failure tracking
```

### New Logic

**Namespace:** `src/tech/jgood/gleanmo/ical/sync.clj`

- `sync-ical-subscriptions!` - Reusable sync function
- Fetch all active `ical-url` subscriptions for user
- Parse iCal feeds using ical4j (dependency already in deps.edn)
- Map VEVENT → calendar-event:
  - `external-id` ← VEVENT.UID
  - `ical-url-id` ← subscription id
  - `beginning` ← DTSTART
  - `end` ← DTEND
  - `label` ← SUMMARY
  - `description` ← DESCRIPTION
  - `all-day` ← determined from DATE vs DATETIME
- Upsert using external-id for deduplication (update if exists, insert if new)
- Store `last-fetched` timestamp and any errors on ical-url entity

**Source detection from URL patterns (stored in `:ical-url/source` string):**
- `calendar.google.com` → "Google Calendar"
- `hey.com` → "Hey Calendar"
- `outlook.live.com` / `outlook.office365.com` → "Outlook"
- `icloud.com` → "Apple"
- fallback → "iCal"

### UI Changes

**Routes:**
- Re-enable `ical-url/crud-routes` in `app.clj` (currently commented out at line 452)

**Calendar page (`/app/calendar/year`):**
- On page load: call `sync-ical-subscriptions!`
- Show spinner/indicator while sync runs
- On completion: HTMX trigger (`HX-Trigger: calendar-synced`) to refresh calendar grid
- Event rendering: lookup color from `ical-url` if `ical-url-id` present, else use event's own `color-neon`

**ical-url CRUD pages:**
- Add "Sync Now" button on edit page that triggers sync for that subscription
- Show `last-fetched` timestamp and any `last-error`

**Form renderer changes (`src/tech/jgood/gleanmo/crud/forms/`):**
- If `ical-url-id` present on event → render all fields as `disabled` (read-only)
- Native events (no `ical-url-id`) remain fully editable

### Query Changes

**`get-events-for-user-year`** (in `db/queries.clj`):
- Join with `ical-url` to get subscription color for imported events
- Filter: only show events from subscriptions where `ical-url/active` is true
- Coalesce color: use `ical-url/color-neon` if imported, else `calendar-event/color-neon`

### Validation

- Unit tests for ical4j parsing and deduplication logic
- Unit tests for source detection from URL patterns
- Integration test: create ical-url, sync, verify events appear with correct color
- Integration test: re-sync same feed, verify no duplicates
- E2E: subscribe to a calendar, view calendar page, verify events render

### Calendar Enhancements
- **Past Days Visual**: Dimmed/faded styling to distinguish past from future dates
- **Relative Positioning**: Center view around today (6 months before/after)
- **Advanced Timezone**: Multi-timezone support
- **Event Categories**: Enhanced categorization system
- **Recurring Events**: Repeat functionality
- **Event Templates**: Quick creation from templates

### Natural Events & Indicators
- **Holidays**: Display and manage personal/cultural/national holidays
- **Moon Phases**: Lunar cycle indicators (icons or background treatment)
- **Solar Events**: Equinox/solstice markers for seasonal transitions
- **Frost Dates**: First/last frost dates for gardening (location-based)

### Calendar Analytics
- **Vacation Analytics**: Time to next weekend/vacation, PTO tracking
- **Temporal Insights**: Time until end of year, next holiday, next solar/lunar event
- **Event Statistics**: Total vacations/events by type, patterns over time

### Recurring Events Architecture
- **Data Model**: Keep one `calendar-event` namespace, adding a `series` entity that carries recurrence metadata (RRULE, EXDATE/EXRULE, `DTSTART`) and an `instance` entity for concrete occurrences with per-event overrides; single events stay as detached instances for a uniform rendering path.
- **RRULE Storage**: Persist RFC5545 rule strings alongside a parsed form so we remain compatible with external calendar sync while still enabling fast, in-app evaluation.
- **Expansion Strategy**: Expand lazily for the visible window (month/year) via a pure helper that accepts the rule, timezone, and date bounds; cache per user/session and only materialize overrides when an occurrence is edited.
- **Exception Handling**: Support edit-this-only (detached instance), edit-this-and-following (split series by cloning with a new `DTSTART`), and delete-this (record EXDATE); keep shared metadata (title, color, icon) on the series to avoid duplication.
- **UI/HTMX**: Reuse the existing modal flow with an additional recurrence section and partial HTMX updates so only affected cells refresh; render expanded occurrences through the current event view model.
- **Testing**: Add property-style coverage around the recurrence expander (DST boundaries, rule edge cases) and extend integration tests to assert user scoping, timezone handling, and detached-instance regressions.

## TECHNICAL IMPLEMENTATION NOTES

### Schema Extensions Needed
```clojure
;; Add to calendar-event schema
[:calendar-event/icon {:optional true} [:enum :work :personal :travel :medical :celebration :other]]
```

### CSS/Styling Updates Needed
- Month row alternating colors (odd/even styling)
- Weekend cell enhanced styling (stronger visual differentiation) 
- Today cell border styling (neon-gold border candidate)
- Event box sizing (larger, more prominent in day cells)
- Month label/spacing compression
- Header/navigation repositioning

### Interaction Updates Needed
- Event click handlers for edit functionality
- Distinguish event clicks from day cell clicks  
- Event hover states for better UX
- Responsive event details (CSS hover on desktop, JS touch on mobile)

### Database Considerations
- Events stored as instants with timezone info
- Uses existing `get-user-time-zone` function
- All database interactions through db namespace layer
- Timezone-aware queries for accurate date grouping
- Event source is currently `:gleanmo` only; extend schema enum when adding iCal/Google sync

## SUCCESS CRITERIA

### ✅ Phase 1: Core Functionality (COMPLETED)
1. Events appear immediately after creation
2. Color selection available (neon colors implemented)
3. Real-time updates working (HTMX trigger implemented)
4. Entity renamed to calendar-events
5. Neon color consistency fixed
6. Consistent naming throughout codebase
7. Ready for life chart integration

### 🔄 Phase 2: Polish & UX (IN PROGRESS)  
8. Clean visual design without text clutter
9. Interactive event details on all devices
10. Icon selection in forms
11. Compressed month spacing
12. Header/corner navigation placement
13. Alternating month row colors
14. Enhanced weekend visual emphasis
15. Border-based today highlighting
16. Improved event visualization size
17. Individual event click editing
18. Holiday display
19. Lunar/solar phase indicators

### 🔮 Phase 3: Bidirectional Calendar Sync (FUTURE)

#### Google Calendar API Integration
- **OAuth 2.0 authentication** - One-time setup per user
- **Bidirectional sync** - Create, read, update, delete events in Google Calendar
- **Real-time updates** - Webhook notifications for external changes
- **Event linking** - Maintain relationship between Gleanmo and Google events
- **Conflict resolution** - Handle simultaneous edits in both systems
- **Bulk operations** - Efficient multi-event sync

#### Email Export (Alternative Write Method)
- **iCal attachment emails** - Generate RFC5545 .ics files and email to target calendars
- **Universal compatibility** - Works with Google, Outlook, Apple Calendar, Hey
- **User friction trade-off** - Requires manual "Add to Calendar" action per event
- **No update capability** - Each email creates new event (cannot update existing)
- **Good for one-time exports** - Suitable for sharing events rather than ongoing sync

### 🔮 Phase 4: External Calendar Subscription (READY)

#### Core Sync
24. User can create ical-url subscription with label and color
25. On calendar page load, active subscriptions sync automatically
26. Spinner/indicator shows sync in progress
27. Calendar grid auto-refreshes when sync completes (HTMX trigger)
28. Events from external calendars appear with subscription color
29. Sync deduplicates events using external-id (no duplicates on re-sync)
30. Source detected from URL and stored on ical-url

#### UI/UX
31. ical-url CRUD routes re-enabled at /app/ical-url/*
32. "Sync Now" button on ical-url edit page
33. Imported events open as read-only (all fields disabled)
34. last-fetched timestamp visible on ical-url pages
35. Sync errors displayed if last-error is set

### 🔮 Phase 5: Advanced Features (FUTURE)
26. Recurring events
27. Event templates
28. Advanced categorization
29. Multi-timezone support
30. Seasonal/astronomical data

## ✅ Implementation Update (2025-10-23)
- **Fixed header navigation**: Desktop view now keeps year navigation and home link in a persistent top bar so the user never has to scroll to switch years or exit the calendar.
- **Clickable event tiles**: Events rendered inside day cells link straight to the CRUD edit form and stop the day-level click handler, enabling in-place edits without extra steps.
- **Past-day emphasis**: Historical dates automatically dim via opacity/text changes while keeping weekends highlighted, giving clear temporal context when scanning the grid.
- **HTMX refresh hook**: Successful event submissions emit an `eventCreated` trigger that re-requests the year grid, so newly created events appear immediately without a manual reload.
