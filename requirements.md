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

### Schemas

#### Project Schema
```clojure
(def project
  (-> [:map {:closed true}
       [:xt/id :project/id]
       [::sm/type [:enum :project]]
       [::sm/created-at :instant]
       [::sm/deleted-at {:optional true} :instant]
       [:user/id :user/id]
       [:project/label :string]
       [:project/roam-pages [:set :string]]  ; multiple roam page titles
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

#### Project Time Schema
```clojure
(def project-time
  (-> [:map {:closed true}
       [:xt/id :project-time/id]
       [::sm/type [:enum :project-time]]
       [::sm/created-at :instant]
       [::sm/deleted-at {:optional true} :instant]
       [:user/id :user/id]
       [:project/id {:optional true} :project/id]  ; nullable for unlinked entries
       [:project-time/beginning :instant]
       [:project-time/end {:optional true} :instant]
       [:project-time/source [:enum :manual :import :timer]]
       [:project-time/notes {:optional true} :string]]
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

### Phase 1: Core Schemas & CRUD
- [ ] Add project, roam-metric, and project-time schemas to schema registry
- [ ] Leverage existing CRUD abstraction for basic project management
- [ ] Create project list view with basic information

### Phase 2: Roam Integration
- [ ] Implement Roam API client with authentication
- [ ] Create datalog queries for tag counting and activity metrics
- [ ] Build metric fetching and caching system
- [ ] Add on-demand refresh capability

### Phase 3: Time Integration
- [ ] Link existing time tracking to projects
- [ ] Support regex-based project linking via `[[roam-page]]` references in notes
- [ ] Add project selection dropdown for manual time entries

### Phase 4: Dashboard & Analytics
- [ ] Create project dashboard showing:
  - Project status (todo/might counts)
  - Recent activity (last edit, words added)
  - Time investment (hours this week/month)
- [ ] Add basic charts and trend visualization

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