# Gleanmo Project Integration Requirements (Refined)

## Scope

Foundation to: (a) show basic activity from Roam notes, and (b) capture time entries in this app via existing CRUD. This sets the stage for later graphs and user stories without dictating UI specifics now.

## Goals (near-term)

1. Link Gleanmo projects to Roam pages via Roam UIDs (not titles).
2. Pull minimal activity signals from linked Roam pages: tag counts and last edit recency (optionally words over 7d).
3. Capture project time by creating project-log entries with start and end timestamps (CRUD-based; no timer UI yet).
4. Provide a simple list/dashboard that shows per-project: recent activity snapshot and recent time investment.

## Schemas

Note: Keep schemas small and UID-based where Roam is involved. Titles can change; UIDs do not. Relationship naming pattern: use :user/id for user ownership; for other relations use child-namespaced -id fields (e.g., :time-entry/project-id), not bare :project/id at the top level.

### Project
```clojure
(def project
  (-> [:map {:closed true}
       [:xt/id :project/id]
       [::sm/type [:enum :project]]
       [::sm/created-at :instant]
       [::sm/deleted-at {:optional true} :instant]
       [:user/id :user/id]
       [:project/label :string]
       ;; Linked Roam pages by UID, with optional cached title for display
       [:project/roam-pages [:set [:map
                                   [:uid :string]
                                   [:title {:optional true} :string]]]]
       [:project/archived {:optional true} :boolean]
       [:project/sensitive {:optional true} :boolean]
       [:project/notes {:optional true} :string]]
      vec))
```

### Roam Metric (per page snapshot)
```clojure
(def roam-metric
  (-> [:map {:closed true}
       [:xt/id :roam-metric/id]
       [::sm/type [:enum :roam-metric]]
       [::sm/created-at :instant]
       [::sm/deleted-at {:optional true} :instant]
       [:user/id :user/id]
       [:roam-metric/project-id :project/id]
       [:roam-metric/page-uid :string]
       [:roam-metric/page-title {:optional true} :string] ; cached, may change in Roam
       [:roam-metric/fetched-at :instant]
       ;; counts are flexible; implementation may hardcode an initial set of tags
       [:roam-metric/counts [:map-of :keyword :int]]
       [:roam-metric/last-edit {:optional true} :instant]
       [:roam-metric/words-7d {:optional true} :int]]
      vec))
```

### Project Log (CRUD-based time capture)
```clojure
(def project-log
  (-> [:map {:closed true}
       [:xt/id :project-log/id]
       [::sm/type [:enum :project-log]]
       [::sm/created-at :instant]
       [::sm/deleted-at {:optional true} :instant]
       [:user/id :user/id]
       [:project-log/project-id :project/id]
       [:project-log/beginning :instant]
       [:project-log/end :instant]
       [:project-log/notes {:optional true} :string]]
      vec))
```

## Roam Integration (minimal)

- Identify Roam pages by UID; store title only as cached display.
- Fetch per-page signals: counts for tags (todo, might, breadcrumb, dogfood, feedback), last edit, optional 7d word count.
- Store each fetch as a snapshot (roam-metric) and show the latest per page.

Example query shape using UIDs (pseudocode):
```clojure
[:find ?block-uid ?content ?tags
 :in $ ?page-uid
 :where
 [?page :node/uid ?page-uid]
 [?block :block/page ?page]
 [?block :block/uid ?block-uid]
 [?block :block/string ?content]
 [?block :block/refs ?tag-page]
 [?tag-page :node/title ?tags]
 [(contains? #{"todo" "might" "breadcrumb" "dogfood" "feedback"} ?tags)]]
```

## Implementation Phases (focused)

### Phase 0: Time + Projects (no Roam yet)
- [ ] Add project-log schema and CRUD routes (create with beginning/end timestamps, required project).
- [ ] Basic list/detail for projects and time entries.

### Phase 1: Roam Activity Snapshots (UID-based)
- [ ] Link projects to Roam page UIDs (with optional cached titles).
- [ ] Fetch and store per-page activity snapshots (counts, last edit, optional 7d words). Initially hardcode the tag set in code; schema supports arbitrary tags.
- [ ] Show latest snapshot per linked page on the project view; simple refresh action.

### Phase 2: Simple Dashboard
- [ ] Project list shows: recent activity at-a-glance and recent time totals (e.g., this week).
- [ ] Optional nightly refresh for Roam snapshots.

## Success (near-term)

- Can create time entries with start/end via CRUD and see them grouped by project.
- Can link a project to one or more Roam UIDs and see basic activity from those pages.
- Can view a simple project list/dashboard that surfaces recent activity and time.

## Notes for later

- Timers/active sessions, goals, streaks, and richer graphs come after this foundation.
- Roam page titles may change; UIDs ensure stable linkage.
