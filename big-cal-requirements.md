# Big Calendar Implementation Progress

## ✅ Completed
1. **Basic Calendar Structure**
   - Year-at-a-glance view with months as rows
   - Uniform day cell sizing using CSS calc
   - Clean border system without double-thick lines
   - Weekend highlighting with subtle background
   - Today highlighting with blue background

2. **Event Schema & Database**
   - Created event entity schema (`event_schema.clj`)
   - Added to main schema registry
   - Consistent with existing conventions (`:event/label`, `:event/time-zone`)
   - iCal/RFC5545 compatible fields for future sync
   - Event source tracking (`:big-calendar`, `:ical-sync`, `:google-sync`)
   - Database functions: `create-event!`, `get-events-for-user-year`
   - Timezone-aware queries using user settings

## 🔄 Next Steps

### 3. **HTMX Event Creation Form**
- [ ] Create event creation endpoint in app.clj
- [ ] Build simple HTMX form modal (label input + submit)
- [ ] Add click handlers to calendar day cells
- [ ] Wire up form submission to database
- [ ] Handle form validation and errors
- [ ] Close form on successful creation

### 4. **Display Events on Calendar**
- [ ] Query events for current year in calendar view
- [ ] Group events by date for rendering
- [ ] Add event indicators to day cells (small dots, text, etc.)
- [ ] Handle multiple events per day
- [ ] Style event indicators appropriately

### 5. **Event Management**
- [ ] Click event to view/edit details
- [ ] Delete events functionality
- [ ] Event color coding
- [ ] All-day vs timed events handling

### 6. **Polish & UX**
- [ ] Loading states for form submission
- [ ] Success/error messages
- [ ] Keyboard navigation (ESC to close form)
- [ ] Mobile responsiveness for forms
- [ ] Event tooltips on hover

## 🔮 Future Features (from future.md)
- Past days visual indication
- Relative today positioning (6 months before/after)
- Solar events (equinox/solstice)
- First/last frost dates
- Moon phases
- Google calendar sync via iCal URLs

## Technical Notes
- Events stored as instants with timezone info (consistent with existing log schemas)
- Uses existing `get-user-time-zone` function for timezone handling
- HTMX for dynamic form interaction without page refreshes
- All database interactions through db namespace layer