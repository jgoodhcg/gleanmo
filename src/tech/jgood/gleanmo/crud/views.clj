(ns tech.jgood.gleanmo.crud.views
  (:require
   [clojure.string :as str]
   [clojure.tools.logging :as log]
   [com.biffweb :as biff]
   [potpuri.core :as pot]
   [tech.jgood.gleanmo.app.shared :refer
    [side-bar]]
   [tech.jgood.gleanmo.crud.schema-utils :as schema-utils]
   [tech.jgood.gleanmo.crud.views.formatting :refer [format-cell-value]]
   [tech.jgood.gleanmo.db.queries :as db]
   [tech.jgood.gleanmo.ui :as ui]
   [tick.core :as t]))

(defn get-display-fields
  "Extract fields from schema that should be displayed in the table"
  [schema]
  (->> (schema-utils/extract-schema-fields schema)
       (map schema-utils/prepare-field)
       ;; remove system, user, and deprecated fields
       (remove schema-utils/should-remove-system-or-user-field?)))

(defn render-table
  [{:keys [paginated-entities display-fields entity-str]} ctx]
  (let [;; Sort function that places label field first, then alphabetically
        ;; by name
        sort-with-label-first (fn [fields]
                                (let [label-key (keyword entity-str "label")]
                                  ;; If we have a label field, sort it
                                  ;; first
                                  (if (some #(= (:field-key %) label-key)
                                            fields)
                                    (concat
                                      ;; First the label field
                                     (filter #(= (:field-key %) label-key)
                                             fields)
                                      ;; Then all other fields sorted
                                      ;; alphabetically
                                     (sort-by (comp name :field-key)
                                              (remove #(= (:field-key %)
                                                          label-key)
                                                      fields)))
                                    ;; Otherwise just sort alphabetically
                                    (sort-by (comp name :field-key) fields))))

        sorted-fields         (sort-with-label-first display-fields)
        ;; Process fields to adjust labels and handle special cases
        processed-fields      (map (fn [{:keys [field-key input-label],
                                         :as   field}]
                                     (if (= field-key :user/id)
                                       ;; Special case for user/id - change
                                       ;; label to just "User"
                                       (assoc field :input-label "User")
                                       field))
                                   sorted-fields)]
    [:div.table-container
     [:table.min-w-full.table-fixed
      {:style {:table-layout "fixed"}} ;; Ensures fixed width columns
      [:thead.table-header
       [:tr
        (for [{:keys [field-key input-label]} processed-fields]
          [:th.table-header-cell
           {:key   (str (name field-key)),
            :style {:max-width "250px",
                    :overflow  "hidden"}}
           input-label])
        ;; Add actions column header
        [:th.table-header-cell.text-right
         "Actions"]]]
      [:tbody.table-body
       (map-indexed
        (fn [idx entity]
          [:tr.table-row
           {:key   idx}
           (for [{:keys [field-key input-type type]} processed-fields]
             [:td.table-cell
              {:key   (str (name field-key)),
               :style {:max-width "250px",
                       :overflow  "hidden"}}
              (cond
                 ;; Special case for user/id
                (= field-key :user/id)
                [:span.text-secondary
                 (str (some-> entity
                              (get field-key)
                              str
                              (subs 0 8))
                      "...")]

                 ;; Default case - use the regular formatter
                :else
                (format-cell-value input-type (get entity field-key) ctx))])
            ;; Add edit link cell
           [:td.table-cell.whitespace-nowrap.text-right.font-medium
            [:div.flex.justify-end.space-x-4
             [:a.link
              {:href (str "/app/crud/form/" entity-str
                          "/edit/" (:xt/id entity))}
              "Edit"]
             (biff/form
              {:action
               (str "/app/crud/" entity-str "/" (:xt/id entity) "/delete"),
               :method "post",
               :onsubmit
               "return confirm('Are you sure you want to delete this item? This action cannot be undone.');"}
              [:button.link
               {:type "submit"}
               "Delete"])]]])
        paginated-entities)]]]))

;; Helper function to format relative time using tick
(defn format-relative-time
  "Format an instant as a relative time string (e.g. '2 hours ago') using tick"
  [instant]
  (when instant
    (let [now          (t/now)
          duration     (t/between instant now)
          seconds-diff (t/seconds duration)]
      (cond
        ;; For durations under a month, use duration units
        (< seconds-diff 60) "just now"
        (< seconds-diff 3600)
        (let [mins (t/minutes duration)]
          (str mins " minute" (when (not= 1 mins) "s") " ago"))
        (< seconds-diff 86400)
        (let [hours (t/hours duration)]
          (str hours " hour" (when (not= 1 hours) "s") " ago"))
        (< seconds-diff 604800)
        (let [days (t/days duration)]
          (str days " day" (when (not= 1 days) "s") " ago"))
        (< seconds-diff 2592000)
        (let [weeks (int (/ (t/days duration) 7))]
          (str weeks " week" (when (not= 1 weeks) "s") " ago"))

        ;; For durations over a month, convert to period between dates
        :else
        (let [;; Convert instants to dates for period calculation
              start-date (t/date instant)
              end-date   (t/date now)
              period     (t/between start-date end-date)
                ;; Get years and months components
              years      (t/years period)
              months     (t/months (t/- period
                                        (t/new-period years :years)))]
          (if (pos? years)
            (str years
                 " year"
                 (when (not= 1 years) "s")
                 (when (pos? months)
                   (str ", " months " month" (when (not= 1 months) "s")))
                 " ago")
            (str months " month" (when (not= 1 months) "s") " ago")))))))

;; Helper function to format duration between two instants using tick
(defn format-duration
  "Format duration between two instants using tick library"
  [start-instant end-instant]
  (when (and start-instant end-instant)
    (let [start (t/instant start-instant)
          end   (t/instant end-instant)]
      (if (t/> start end)
        ;; If start is after end, swap them (prevent negative durations)
        (format-duration end start)
        (let [duration (t/between start end)]
          (cond
            ;; For durations over a day, show days/hours/minutes
            (t/>= duration (t/new-duration 1 :days))
            (let [days       (t/days duration)
                  hours-part (t/hours (t/- duration
                                           (t/new-duration days :days)))
                  mins-part  (t/minutes (t/- duration
                                             (t/+ (t/new-duration days :days)
                                                  (t/new-duration hours-part
                                                                  :hours))))]
              (format "%dd %dh %dm" days hours-part mins-part))

            ;; For durations over an hour, show hours/minutes
            (t/>= duration (t/new-duration 1 :hours))
            (let [hours     (t/hours duration)
                  mins-part (t/minutes (t/- duration
                                            (t/new-duration hours :hours)))]
              (format "%dh %dm" hours mins-part))

            ;; For durations over a minute, show minutes/seconds
            (t/>= duration (t/new-duration 1 :minutes))
            (let [mins      (t/minutes duration)
                  secs-part (t/seconds (t/- duration
                                            (t/new-duration mins :minutes)))]
              (format "%dm %ds" mins secs-part))

            ;; For durations under a minute, just show seconds
            :else
            (format "%ds" (t/seconds duration))))))))

;; Helper to find any field containing a pattern in its name
(defn find-field-by-pattern
  [entity entity-str pattern]
  (->> entity
       (filter (fn [[k _]]
                 (and (keyword? k)
                      (= (namespace k) entity-str)
                      (str/includes? (name k) pattern))))
       first))

;; Helper to check if a field exists in the schema
(defn field-exists-in-schema?
  "Check if a field key exists in the schema by looking at display fields"
  [field-key display-fields]
  (boolean (some #(= (:field-key %) field-key) display-fields)))

;; Find field formatter for a given field key
(defn get-field-formatter
  [field-key display-fields]
  (when field-key
    (let [field-info (->> display-fields
                          (filter #(= (:field-key %) field-key))
                          first)]
      (when field-info
        (:input-type field-info)))))

;; Helper to get value as Instant if possible using tick
(defn as-instant
  [value]
  (when value
    (try
      (t/instant value)
      (catch Exception _
        nil))))

;; Card view implementation
(defn render-card-view
  [{:keys [paginated-entities display-fields entity-str]} ctx]
  [:div.grid.grid-cols-1.md:grid-cols-2.lg:grid-cols-3.gap-4
   (for [entity paginated-entities]
     (let [;; Find key fields
           entity-id         (:xt/id entity)

           ;; Check for label field - determine if it exists in schema but is nil
           ;; or doesn't exist in schema at all
           label-key         (keyword entity-str "label")
           label-value       (get entity label-key)
           label-in-schema?  (field-exists-in-schema? label-key display-fields)

           ;; Find timestamp field
           timestamp-field   (find-field-by-pattern entity
                                                    entity-str
                                                    "timestamp")
           timestamp-key     (first timestamp-field)
           timestamp-value   (second timestamp-field)
           timestamp-type    (get-field-formatter timestamp-key display-fields)
           timestamp-instant (as-instant timestamp-value)

           ;; Find beginning and end fields
           beginning-field   (find-field-by-pattern entity
                                                    entity-str
                                                    "beginning")
           beginning-key     (first beginning-field)
           beginning-value   (second beginning-field)
           beginning-type    (get-field-formatter beginning-key display-fields)
           beginning-instant (as-instant beginning-value)

           end-field         (find-field-by-pattern entity entity-str "end")
           end-key           (first end-field)
           end-value         (second end-field)
           end-type          (get-field-formatter end-key display-fields)
           end-instant       (as-instant end-value)

           ;; Calculate duration if beginning and end exist
           duration          (when (and beginning-instant end-instant)
                               (format-duration beginning-instant end-instant))

           ;; Find notes field if available
           notes-field       (find-field-by-pattern entity entity-str "notes")
           notes-key         (first notes-field)
           notes-value       (second notes-field)
           notes-in-schema?  (and notes-key (field-exists-in-schema? notes-key display-fields))]

       ;; Wrapper div for entire card - clickable area
       [:div.card-container.group
        {:key (str entity-id)}

        ;; Entity type tag (subtle)
        [:div.absolute.top-2.left-2.card-tag.font-light
         entity-str]

        ;; Subtle entity ID display in top-right corner
        [:div.card-tag.font-mono
         (str (subs (str entity-id) 0 8) "...")]

        ;; Main card content area - clickable for edit  
        [:a.flex-grow.flex.flex-col.pt-8.pb-4.px-4.relative.z-10
         {:href (str "/app/crud/form/" entity-str "/edit/" entity-id),
          :class
          "focus:outline-none focus:ring-2 focus:ring-blue-300 focus:ring-inset",
          :aria-label (str "Edit " (or label-value entity-str)),
          :role "button"}

         ;; Card header with label
         [:div.mb-4
          [:h3.card-header
           (cond
             ;; If label exists in schema but is nil, show message
             (and label-in-schema? (nil? label-value))
             [:span.text-secondary.italic "No Label"]

             ;; If label has a value, show it
             label-value
             label-value

             ;; If label doesn't exist in schema or is absent, show entity type
             :else
             entity-str)]]

         ;; Main content section
         [:div.flex-grow.space-y-3

          ;; Timestamp with relative time
          (when timestamp-value
            [:div.flex.justify-between.items-baseline
             [:div
              [:span.card-text-secondary.font-medium
               (str (str/capitalize (name timestamp-key)) ":")]
              [:span.ml-1.card-text
               (if timestamp-type
                 (format-cell-value timestamp-type timestamp-value ctx)
                 (str timestamp-value))]]
             (when timestamp-instant
               [:span.card-tag.px-2.py-0.5
                (format-relative-time timestamp-instant)])])

          ;; Beginning and End with duration (if both exist)
          (when (or beginning-value end-value)
            [:div.bg-dark.p-2.rounded
             (when beginning-value
               [:div
                [:span.card-text-secondary.font-medium
                 (str (str/capitalize (name beginning-key)) ":")]
                [:span.ml-1.card-text
                 (if beginning-type
                   (format-cell-value beginning-type beginning-value ctx)
                   (str beginning-value))]])

             (when end-value
               [:div.mt-1
                [:span.card-text-secondary.font-medium
                 (str (str/capitalize (name end-key)) ":")]
                [:span.ml-1.card-text
                 (if end-type
                   (format-cell-value end-type end-value ctx)
                   (str end-value))]])

             ;; Duration display if we have both beginning and end
             (when duration
               [:div.mt-2.flex.items-center.justify-end
                [:span.card-tag.font-medium.px-2.py-0.5.rounded-full
                 (str "Duration: " duration)]])])

          ;; Notes section - only show if in schema (even if nil)
          (when notes-in-schema?
            [:div
             [:span.text-sm.font-medium.card-text-secondary
              (str (str/capitalize (name notes-key)) ":")]
             (if notes-value
               [:div.text-sm.card-text.line-clamp-2
                {:title notes-value}
                (str notes-value)]
               [:span.text-sm.text-secondary.italic "No notes"])])

          ;; Additional fields section
          (let [important-fields
                (->> display-fields
                     (filter #(not (or
                                    (= (:field-key %) label-key)
                                    (= (:field-key %) timestamp-key)
                                    (= (:field-key %) beginning-key)
                                    (= (:field-key %) end-key)
                                    (= (:field-key %) notes-key))))
                     (take 3))]
            [:div.mt-2.space-y-1
             (for [{:keys [field-key input-type input-label]}
                   important-fields]
               (let [value (get entity field-key)]
                 [:div.text-sm {:key (name field-key)}
                  [:span.card-text-secondary.font-medium (str input-label ": ")]
                  (if (nil? value)
                    [:span.text-secondary.italic "None"]
                    [:span (format-cell-value input-type value ctx)])]))])]

         ;; Small icon to indicate card is clickable
         [:div.absolute.bottom-3.right-3.text-secondary.opacity-0.group-hover:opacity-100.transition-opacity
          [:svg.h-4.w-4
           {:xmlns   "http://www.w3.org/2000/svg",
            :viewBox "0 0 20 20",
            :fill    "currentColor"}
           [:path
            {:fill-rule "evenodd",
             :d
             "M7.293 14.707a1 1 0 010-1.414L10.586 10 7.293 6.707a1 1 0 011.414-1.414l4 4a1 1 0 010 1.414l-4 4a1 1 0 01-1.414 0z",
             :clip-rule "evenodd"}]]]]

        ;; Delete button - small icon at the bottom-left
        [:div.absolute.bottom-3.left-3.z-20
         (biff/form
          {:action (str "/app/crud/" entity-str "/" entity-id "/delete"),
           :method "post",
           :class "inline",
           :onsubmit
           "return confirm('Are you sure you want to delete this item? This action cannot be undone.');"}
          [:button.text-secondary.hover:text-red-600.transition-colors
           {:type       "submit",
            :aria-label (str "Delete " (or label-value entity-str))}
           [:svg.h-4.w-4
            {:xmlns   "http://www.w3.org/2000/svg",
             :viewBox "0 0 20 20",
             :fill    "currentColor"}
            [:path
             {:fill-rule "evenodd",
              :d
              "M9 2a1 1 0 00-.894.553L7.382 4H4a1 1 0 000 2v10a2 2 0 002 2h8a2 2 0 002-2V6a1 1 0 100-2h-3.382l-.724-1.447A1 1 0 0011 2H9zM7 8a1 1 0 012 0v6a1 1 0 11-2 0V8zm5-1a1 1 0 00-1 1v6a1 1 0 102 0V8a1 1 0 00-1-1z",
              :clip-rule "evenodd"}]]])]]))])

;; List view implementation
(defn render-list-view
  [{:keys [paginated-entities display-fields entity-str]} ctx]
  [:div.list-container
   (for [entity paginated-entities]
     (let [;; Find key fields
           entity-id         (:xt/id entity)

           ;; Check for label field
           label-key         (keyword entity-str "label")
           label-value       (get entity label-key)
           label-in-schema?  (field-exists-in-schema? label-key display-fields)

           ;; Find timestamp field
           timestamp-field   (find-field-by-pattern entity entity-str "timestamp")
           timestamp-key     (first timestamp-field)
           timestamp-value   (second timestamp-field)
           timestamp-type    (get-field-formatter timestamp-key display-fields)
           timestamp-instant (as-instant timestamp-value)

           ;; Find beginning and end fields
           beginning-field   (find-field-by-pattern entity entity-str "beginning")
           beginning-key     (first beginning-field)
           beginning-value   (second beginning-field)
           beginning-type    (get-field-formatter beginning-key display-fields)
           beginning-instant (as-instant beginning-value)

           end-field         (find-field-by-pattern entity entity-str "end")
           end-key           (first end-field)
           end-value         (second end-field)
           end-type          (get-field-formatter end-key display-fields)
           end-instant       (as-instant end-value)

           ;; Calculate duration if beginning and end exist
           duration          (when (and beginning-instant end-instant)
                               (format-duration beginning-instant end-instant))

           ;; Get primary information to display
           display-title     (cond
                               ;; If label exists in schema but is nil
                               (and label-in-schema? (nil? label-value))
                               [:span.text-secondary.italic "No Label"]

                               ;; If label has a value
                               label-value
                               label-value

                               ;; If label doesn't exist, use entity type
                               :else
                               entity-str)

           ;; Get secondary information (timestamp or beginning/end)
           timestamp-display (when timestamp-value
                               (if timestamp-type
                                 (format-cell-value timestamp-type timestamp-value ctx)
                                 (str timestamp-value)))

           time-display      (cond
                               ;; Show timestamp with relative time if available
                               timestamp-instant
                               [:div.flex.items-center.gap-2
                                [:span timestamp-display]
                                [:span.card-tag.px-1.py-0.5.rounded
                                 (format-relative-time timestamp-instant)]]

                               ;; Show beginning/end with duration if available
                               (and beginning-value end-value)
                               [:div.flex.items-center.gap-2
                                [:span (if beginning-type
                                         (format-cell-value beginning-type beginning-value ctx)
                                         (str beginning-value))]
                                [:span.text-secondary "→"]
                                [:span (if end-type
                                         (format-cell-value end-type end-value ctx)
                                         (str end-value))]
                                (when duration
                                  [:span.card-tag.px-1.py-0.5.rounded
                                   duration])]

                               ;; Show just beginning if that's all we have
                               beginning-value
                               [:span (if beginning-type
                                        (format-cell-value beginning-type beginning-value ctx)
                                        (str beginning-value))]

                               ;; Show nothing if no time information
                               :else
                               nil)]

       [:div.list-item.group
        {:key (str entity-id)}

        ;; Main row content - clickable for edit
        [:a.flex.items-center.justify-between.py-4.px-4.relative
         {:href (str "/app/crud/form/" entity-str "/edit/" entity-id)
          :class "focus:outline-none"
          :aria-label (str "Edit " (or label-value entity-str))
          :role "button"}

         ;; Left side - main content
         [:div.flex-1.min-w-0
          ;; Title and subtle entity type indicator
          [:div.flex.items-baseline.gap-2
           [:h3.list-title.truncate
            display-title]
           [:div.card-tag.hidden.sm:block
            entity-str]]

          ;; Timestamp/duration info
          (when time-display
            [:div.card-text-secondary.mt-1
             time-display])

          ;; Subtle entity ID display
          [:div.mt-1.card-tag.font-mono
           (str "ID: " (subs (str entity-id) 0 8) "...")]]

         ;; Right side - actions
         [:div.flex.items-center.space-x-4.opacity-0.group-hover:opacity-100.transition-opacity
          ;; Edit button (duplicate, whole row already clickable)
          [:div.text-primary
           [:svg.h-4.w-4 {:xmlns "http://www.w3.org/2000/svg" :viewBox "0 0 20 20" :fill "currentColor"}
            [:path {:d "M13.586 3.586a2 2 0 112.828 2.828l-.793.793-2.828-2.828.793-.793zM11.379 5.793L3 14.172V17h2.828l8.38-8.379-2.83-2.828z"}]]]

          ;; Delete button wrapper (to stop click propagation)
          [:div {:onClick "event.stopPropagation();"}
           (biff/form
            {:action (str "/app/crud/" entity-str "/" entity-id "/delete")
             :method "post"
             :class "inline"
             :onsubmit "event.stopPropagation(); return confirm('Are you sure you want to delete this item? This action cannot be undone.');"}
            [:button.text-secondary.hover:text-red-600.transition-colors.focus:outline-none
             {:type "submit"
              :aria-label (str "Delete " (or label-value entity-str))
              :onClick "event.stopPropagation();"}
             [:svg.h-4.w-4 {:xmlns "http://www.w3.org/2000/svg" :viewBox "0 0 20 20" :fill "currentColor"}
              [:path {:fill-rule "evenodd"
                      :d "M9 2a1 1 0 00-.894.553L7.382 4H4a1 1 0 000 2v10a2 2 0 002 2h8a2 2 0 002-2V6a1 1 0 100-2h-3.382l-.724-1.447A1 1 0 0011 2H9zM7 8a1 1 0 012 0v6a1 1 0 11-2 0V8zm5-1a1 1 0 00-1 1v6a1 1 0 102 0V8a1 1 0 00-1-1z"
                      :clip-rule "evenodd"}]]])]]]]))])

;; View type selector component
(defn view-selector
  [{:keys [entity-str view-type offset limit]}]
  [:div.flex.space-x-2.mb-4
   [:a.view-selector-button
    {:class (when (= view-type "table") "active"),
     :href  (str "/app/crud/" entity-str
                 "?view=table"
                 (when (or offset limit)
                   (str "&offset=" (or offset 0)
                        "&limit="  (or limit 15))))}
    "Table"]
   [:a.view-selector-button
    {:class (when (= view-type "card") "active"),
     :href  (str "/app/crud/" entity-str
                 "?view=card"
                 (when (or offset limit)
                   (str "&offset=" (or offset 0)
                        "&limit="  (or limit 15))))}
    "Cards"]
   [:a.view-selector-button
    {:class (when (= view-type "list") "active"),
     :href  (str "/app/crud/" entity-str
                 "?view=list"
                 (when (or offset limit)
                   (str "&offset=" (or offset 0)
                        "&limit="  (or limit 15))))}
    "List"]])

(defn list-entities
  [{:keys [entity-key
           entity-str
           plural-str
           schema],
    :as   args}
   {:keys [session biff/db params], :as ctx}]
  (let [user-id            (:uid session)
        {:user/keys [email]} (db/get-entity-by-id db user-id)
        entity-type-str    (name entity-key)
        ;; Get view type from query param or default to "table"
        view-type          (or (:view params) "list")
        ;; Parse pagination parameters safely
        default-limit      15
        offset-str         (:offset params)
        limit-str          (:limit params)
        offset             (try (Integer/parseInt offset-str)
                                (catch Exception _ 0))
        limit              (try (Integer/parseInt limit-str)
                                (catch Exception _ default-limit))
        filter-references  true
        ;; Get all entities
        entities           (db/all-for-user-query
                            (pot/map-of entity-type-str
                                        schema
                                        filter-references)
                            ctx)
        ;; Count for pagination
        total-count        (count entities)
        ;; Apply pagination
        paginated-entities (->> entities
                                (drop offset)
                                (take limit))
        ;; Fields
        display-fields     (get-display-fields schema)]
    (ui/page
     {}
     [:div
      (side-bar
       (pot/map-of email)
       [:div.p-4
        [:h1.form-header
         (str/capitalize plural-str)]

          ;; New entity button
        [:div.mb-4
         [:a.form-button-primary
          {:href (str "/app/crud/form/" entity-str "/new")}
          (str "New " entity-str)]]

          ;; View selector
        (view-selector (pot/map-of entity-str
                                   view-type
                                   offset
                                   limit))

        (if (empty? entities)
          [:div.text-lg "No items found"]
          [:div
             ;; Pagination summary
           [:div.flex.items-center.justify-between.mb-4
            [:p.text-sm.text-secondary
             (str "Showing "
                  (inc offset)
                  "-"
                  (min total-count (+ offset (count paginated-entities)))
                  " of "
                  total-count
                  " "
                  entity-str
                  (when (not= 1 total-count) "s"))]

              ;; Pagination controls with view type preserved
            [:div.flex.items-center.gap-2
             [:a.pagination-button
              {:class (if (> offset 0) "active" "disabled"),
               :href  (if (> offset 0)
                        (str "/app/crud/" entity-str
                             "?view="     view-type
                             "&offset="   (max 0 (- offset limit))
                             "&limit="    limit)
                        "#")}
              "Previous"]
             [:a.pagination-button
              {:href  (if (< (+ offset (count paginated-entities))
                             total-count)
                        (str "/app/crud/" entity-str
                             "?view="     view-type
                             "&offset="   (+ offset limit)
                             "&limit="    limit)
                        "#"),
               :class (if (< (+ offset (count paginated-entities))
                             total-count) "active" "disabled")}
              "Next"]]]

             ;; View based on selected view-type
           [:div.mb-6
            (case view-type
              "card" (render-card-view (pot/map-of paginated-entities
                                                   display-fields
                                                   entity-str)
                                       ctx)
              "list" (render-list-view (pot/map-of paginated-entities
                                                   display-fields
                                                   entity-str)
                                       ctx)
                ;; Default to table view
              (render-table (pot/map-of paginated-entities
                                        display-fields
                                        entity-str)
                            ctx))]])])])))
