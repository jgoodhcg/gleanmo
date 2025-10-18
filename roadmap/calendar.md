# Calendar Implementation Requirements

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
- **Holidays**: Display major holidays on calendar
- **Lunar Phases**: Show moon phases
- **Solar Events**: Equinox, solstice indicators
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

## 🔮 FUTURE FEATURES

### External Calendar Integration
- **Google Calendar Sync**: Via iCal URLs (pull-only or bidirectional)
- **iCal URL Integration**: Extend existing ical-url entity to display events
- **Event deduplication**: Prevent duplicate imports using external IDs
- **Source tracking**: Mark imported events with source (`:google-cal`, `:hey-cal`, etc.)

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

### 🔮 Phase 3: External Calendar Integration (FUTURE)

#### iCal Read Sync (Import External Calendars)
- **Google Calendar iCal URLs** - Read-only import via public/private iCal feeds
- **Hey Calendar iCal** - Import events from Hey's calendar export
- **User-triggered sync** - Manual sync button or automatic on page load
- **Event deduplication** - Prevent duplicate imports using external IDs
- **Source tracking** - Mark imported events as `:google-cal`, `:hey-cal`, etc.
- **Read-only imported events** - External events locked from editing in Gleanmo
- **Schema updates** - Add `:calendar-event/external-id` and `:calendar-event/sync-url` fields

#### Google Calendar API Integration (Bidirectional Sync)
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

### 🔮 Phase 4: Advanced Features (FUTURE)
26. Recurring events
27. Event templates
28. Advanced categorization
29. Multi-timezone support
30. Seasonal/astronomical data
