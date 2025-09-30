# Gleanmo Project Integration Requirements

## Project Overview

Gleanmo is a personal tracking and analytics application built in Clojure that helps users track habits, meditation, exercise, medications, and other life metrics. The goal of this project integration feature is to connect Gleanmo with Roam Research to create a unified project tracking and time management system.

## Goals

1. **Project Definition**: Create projects in Gleanmo that can be associated with one or more Roam Research pages
2. **Roam Metrics Integration**: Pull metrics from Roam pages (todo counts, recent activity, word counts) to track project health
3. **Time Tracking**: Link existing time tracking capabilities to projects for comprehensive project analytics
4. **Dashboard View**: Provide a consolidated view showing project status, recent activity, and time investment
5. **Motivation Enhancement**: Surface meaningful metrics to maintain momentum on projects

## Technical Approach

### MVP Deliverable: Basic Project Time Tracking

#### MVP Project Schema
```clojure
(def project
  (-> [:map {:closed true}
       [:xt/id :project/id]
       [::sm/type [:enum :project]]
       [::sm/created-at :instant]
       [::sm/deleted-at {:optional true} :instant]
       [:user/id :user/id]
       [:project/label :string]
       [:project/sensitive {:optional true} :boolean]
       [:project/archived {:optional true} :boolean]
       [:project/notes {:optional true} :string]]
      (concat sm/legacy-meta)
      vec))
```

#### MVP Project Log Schema
```clojure
(def project-log
  (-> [:map {:closed true}
       [:xt/id :project-log/id]
       [::sm/type [:enum :project-log]]
       [::sm/created-at :instant]
       [::sm/deleted-at {:optional true} :instant]
       [:user/id :user/id]
       [:project/id :project/id]  ; required - all time must be linked to a project
       [:project-log/beginning :instant]
       [:project-log/end {:optional true} :instant]
       [:location/id {:optional true} :location/id]  ; links to existing location entities
       [:project-log/notes {:optional true} :string]]
      (concat sm/legacy-meta)
      vec))
```

### Next Level: Roam Research Integration

#### Extended Project Schema
```clojure
(def project
  (-> [:map {:closed true}
       [:xt/id :project/id]
       [::sm/type [:enum :project]]
       [::sm/created-at :instant]
       [::sm/deleted-at {:optional true} :instant]
       [:user/id :user/id]
       [:project/label :string]
       [:project/sensitive {:optional true} :boolean]
       [:project/roam-pages {:optional true} [:set :string]]  ; multiple roam page titles
       [:project/archived {:optional true} :boolean]
       [:project/notes {:optional true} :string]]
      (concat sm/legacy-meta)
      vec))
```

#### Roam Metric Schema
```clojure
(def roam-metric
  (-> [:map {:closed true}
       [:xt/id :roam-metric/id]
       [::sm/type [:enum :roam-metric]]
       [::sm/created-at :instant]
       [::sm/deleted-at {:optional true} :instant]
       [:user/id :user/id]
       [:project/id :project/id]
       [:roam-metric/roam-page :string]      ; specific page this metric is for
       [:roam-metric/fetched-at :instant]
       [:roam-metric/counts [:map
                             [:todo :int]
                             [:might :int]
                             [:breadcrumb :int]
                             [:dogfood :int]
                             [:feedback :int]]]
       [:roam-metric/last-edit {:optional true} :instant]
       [:roam-metric/words-7d {:optional true} :int]]
      (concat sm/legacy-meta)
      vec))
```

## Roam Research Integration

### API Access
- Use Roam's `/q` endpoint with datalog queries
- Authentication via API token
- Query strategy: Fetch all blocks with specific tags per project page

### Data Collection
- **Tag Counts**: Count blocks tagged with `#todo`, `#might`, `#breadcrumb`, `#dogfood`, `#feedback`
- **Activity Metrics**: Track last edit time and recent word count (7-day window)
- **Caching Strategy**: Store results in `roam-metric` entities, refresh nightly + on-demand

### Example Datalog Query
```clojure
[:find ?block-uid ?content ?tags
 :where
 [?page :node/title "Project Page Title"]
 [?block :block/page ?page]
 [?block :block/uid ?block-uid]
 [?block :block/string ?content]
 [?block :block/refs ?tag-page]
 [?tag-page :node/title ?tags]
 [(contains? #{"todo" "might" "breadcrumb" "dogfood" "feedback"} ?tags)]]
```

## Implementation Phases

### MVP Phase: Basic Time Tracking
- [ ] Add project and project-log schemas to schema registry
- [ ] Leverage existing CRUD abstraction for basic project management
- [ ] Create project timer page with:
  - [ ] List of all projects (click to start timer)
  - [ ] Active timers showing elapsed time
  - [ ] Quick stop buttons (sets end to "now")
  - [ ] Quick edit buttons (±5, ±15, ±30 minutes for start/end times)
  - [ ] Mobile-friendly touch interface
- [ ] Basic time reporting (calendar heatmap or streak-style motivation view)
- [ ] Sensitive/archived filtering using existing db query patterns

### Next Level Phase: Roam Integration
- [ ] Extend project schema with roam-pages field
- [ ] Add roam-metric schema to registry
- [ ] Implement Roam API client with authentication
- [ ] Create datalog queries for tag counting and activity metrics
- [ ] Build metric fetching and caching system
- [ ] Add on-demand refresh capability
- [ ] Support regex-based project linking via `[[roam-page]]` references in notes

### Advanced Phase: Dashboard & Analytics
- [ ] Create project dashboard showing:
  - Project status (todo/might counts)
  - Recent activity (last edit, words added)
  - Time investment (hours this week/month)
- [ ] Add basic charts and trend visualization
- [ ] Project analytics and insights

## Success Metrics

1. **Instant Feedback**: Metrics update within minutes of Roam changes
2. **Low Maintenance**: Automated syncing with minimal manual intervention
3. **Motivation Boost**: Clear visibility into project progress and time investment
4. **Extensibility**: Easy to add new metrics or integrate additional data sources

## Technical Benefits

- **Leverages Existing Infrastructure**: Uses established Malli schemas and CRUD patterns
- **Minimal Complexity**: Simple caching layer avoids complex real-time synchronization
- **Incremental Development**: Each phase delivers immediate value
- **Future-Proof**: Schema design supports additional Roam pages and metric types

## CRUD Form Pre-population and Redirect Enhancement

### Overview
Enhance CRUD creation forms to support pre-population of relationship fields and custom redirect URLs for better timer integration and workflow continuity.

### Query Parameters

**Relationship Pre-population**: `?{entity-field-key}={uuid}`
- Any entity field key can be used as query parameter name
- Value must be valid UUID for the related entity
- Form will pre-populate that relationship field
- Examples:
  - `/app/crud/form/project-log/new?project-id=abc123` - Pre-selects project
  - `/app/crud/form/exercise-set/new?exercise-id=def456` - Pre-selects exercise

**Custom Redirect**: `?redirect={url-encoded-path}`  
- Overrides default form submission redirect
- URL must be properly encoded
- Examples:
  - `?redirect=%2Fapp%2Ftimer%2Fproject-log` → `/app/timer/project-log`
  - `?redirect=%2Fapp%2Fdashboards%2Fanalytics` → `/app/dashboards/analytics`

### Implementation Requirements

#### Form Pre-population (`new-form` handler)
```clojure
;; In forms.clj new-form function
;; Check ctx [:params] for field pre-population
;; Pass pre-populated values to schema->form
```

#### Custom Redirect (`create-entity!` handler) 
```clojure  
;; In handlers.clj create-entity! function
;; Check for :redirect param in form submission
;; Use custom redirect instead of default "/app/crud/form/{entity}/new"
```

### Timer Integration Usage
Timer "Start Timer" buttons link to:
```
/app/crud/form/project-log/new?project-id=<project-uuid>&redirect=%2Fapp%2Ftimer%2Fproject-log
```

**Flow:**
1. User clicks "Start Timer" on project
2. Opens CRUD form with project pre-selected
3. User fills remaining fields (time, location, notes) 
4. Submits form → redirects back to timer page
5. Timer page shows new running timer

### Potential Issues Identified

1. **Security**: Query parameters could potentially set any field
   - **Mitigation**: Validate that field keys exist in schema
   - **Mitigation**: Validate that UUIDs reference entities owned by current user

2. **URL Length**: Long redirect URLs could exceed URL limits
   - **Mitigation**: Use relative paths, URL encoding

3. **Form State**: Pre-populated fields might conflict with form validation
   - **Mitigation**: Ensure pre-populated values pass schema validation

4. **HTMX Compatibility**: Current forms use HTMX for submission
   - **Issue**: HTMX redirects might not work with custom redirect param
   - **Solution**: May need to handle redirect in HTMX response or use standard form POST

5. **Entity Ownership**: Pre-populated relationships must respect user ownership
   - **Mitigation**: Validate related entity belongs to current user

---

## Implementation Notes

### Timer Functionality Status (2025-09-01)

**✅ Working:**
- Timer display shows correctly with elapsed time calculation
- "Start Timer" buttons generate proper instant timestamps in URLs
- Active timers are detected and displayed with correct project names
- Form pre-population works correctly for project selection
- Beginning time pre-population works with proper timezone handling

**❌ Issues Found:**
1. **Stop Timer button doesn't work**
   - URL: `/app/timer/:id/stop` 
   - Issue: Route may not be properly registered or handler has runtime error

2. **Form redirect after creating timer doesn't work**
   - Expected: After submitting new timer form, redirect to `/app/timer/project-log`
   - Current: Redirect parameter appears to be ignored in create handler
   - Investigation needed: Check if redirect logic in create handler is working

3. **Edit button needs redirect enhancement**
   - Current: Edit button goes to `/app/crud/form/project-log/edit/:id`
   - Problem: After editing timer, user stays on edit page instead of returning to timer page
   - **Required**: Extend redirect functionality to update/edit handler (currently only exists for create handler)
   - **Solution**: Update edit button URL to include redirect parameter

**🔧 Next Steps:**
1. Debug stop timer route registration and handler
2. Investigate why create form redirect isn't working  
3. Add redirect support to CRUD edit/update handlers
4. Update edit button to use redirect parameter

**Technical Decisions Made:**
- Used proper instant timestamps instead of magic "now" strings in URLs
- Implemented timezone hierarchy: query params → entity timezone → user timezone → UTC fallback
- Added `project-log/time-zone` field for consistency with other log entities
- Used tick.core duration calculations with seconds-based arithmetic for elapsed time display

---

## Outstanding Issues (Current)

### Timer Page Redirect Issues
1. **Stop timer button redirect problem**
   - Issue: After stopping a timer, user doesn't return to timer page properly
   - Location: Stop timer functionality in timer routes
   - Expected: Should redirect back to `/app/timer/project-log` after stopping

2. **Edit button redirect problem**
   - Issue: Edit button doesn't redirect back to timer page after form submission
   - Current: Edit button goes to `/app/crud/form/project-log/edit/:id`
   - Problem: After editing timer, user stays on edit page instead of returning to timer page
   - Solution needed: Add redirect parameter to edit button URL and ensure edit handler supports it

### Code Quality Issues
3. **Refactor declare usage**
   - Issue: `(declare active-timer-card)` usage in timer routes should be eliminated
   - Problem: Forward declarations indicate poor function organization
   - Solution: Reorganize function definitions so no forward references are needed
   - Location: `src/tech/jgood/gleanmo/timer/routes.clj`

### User Experience Issues
4. **Timer auto-update without refresh**
   - Issue: Timer elapsed time display is static - requires manual page refresh to see updated time
   - Current: Shows elapsed time calculated at page load (e.g., "Running for 2h 15m")
   - Expected: Timer should automatically update every second/minute to show current elapsed time
   - Implementation options:
     - JavaScript setInterval to update DOM elements
     - HTMX polling for periodic page sections updates
     - WebSocket connection for real-time updates
   - Location: `active-timer-card` function in `src/tech/jgood/gleanmo/timer/routes.clj`