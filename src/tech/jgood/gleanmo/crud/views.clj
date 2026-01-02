(ns tech.jgood.gleanmo.crud.views
  (:require
   [clojure.string :as str]
   [clojure.tools.logging :as log]
   [com.biffweb :as biff]
   [potpuri.core :as pot]
   [tech.jgood.gleanmo.app.shared :refer
    [format-date-time-local
     get-user-time-zone
     side-bar]]
   [tech.jgood.gleanmo.schema.utils :as schema-utils]
   [tech.jgood.gleanmo.schema.meta :as sm]
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

(defn get-field-priority
  "Get the priority of a field from its schema definition.
   Returns 99 if no priority is specified (lowest priority)."
  [field]
  (or (:crud/priority (:opts field)) 99))

(defn sort-by-priority-then-arbitrary
  "Sort fields so that any fields with explicit priority rankings come first
  (ordered by their numerical priority value), followed by all other fields
  in arbitrary order. Returns all fields, just reordered."
  [display-fields]
  (let [;; Separate fields with explicit priority (not the default 99) from those without
        fields-with-priority (filter #(< (get-field-priority %) 99) display-fields)
        fields-without-priority (filter #(>= (get-field-priority %) 99) display-fields)
        
        ;; Sort priority fields by their priority value (lower = higher priority)
        sorted-priority-fields (sort-by get-field-priority fields-with-priority)]
    
    (log/info "Priority sorting - Fields with priority:" 
               (clojure.string/join ", " 
                                     (map (fn [field] 
                                            (str (name (:field-key field)) 
                                                 "(priority:" (get-field-priority field) ")"))
                                          sorted-priority-fields)))
    (log/info "Priority sorting - Fields without priority:" 
               (clojure.string/join ", " 
                                     (map (fn [field] 
                                            (str (name (:field-key field)) 
                                                 "(priority:" (get-field-priority field) ")"))
                                          fields-without-priority)))
    
    ;; Combine prioritized fields with remaining fields in arbitrary order
    (let [result (concat sorted-priority-fields fields-without-priority)]
      (log/info "Priority sorting - Final order:" 
                 (clojure.string/join ", " 
                                       (map (fn [field] 
                                              (str (name (:field-key field)) 
                                                   "(priority:" (get-field-priority field) ")"))
                                            result)))
      result)))

(defn humanize-key
  [k]
  (when k
    (-> k
        name
        (str/replace "-" " ")
        str/capitalize)))

(defn find-display-field
  [display-fields field-key]
  (some #(when (= (:field-key %) field-key) %) display-fields))

(defn display-label
  [display-fields field-key]
  (or (some-> (find-display-field display-fields field-key)
              :input-label)
      (humanize-key field-key)))

(defn prioritized-field?
  [field]
  (when field
    (< (get-field-priority field) 99)))

(defn prioritized-fields
  [fields]
  (filter prioritized-field? fields))

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

        priority-sorted-fields (sort-by-priority-then-arbitrary display-fields)
        sorted-fields         (sort-with-label-first priority-sorted-fields)
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

(defn entity-time-zone-info
  [entity entity-str ctx]
  (let [user-tz        (get-user-time-zone ctx)
        tz-field       (find-field-by-pattern entity entity-str "time-zone")
        tz-from-field  (second tz-field)
        tz-from-entity (or tz-from-field (:time-zone entity))
        tz-from-entity (when (and tz-from-entity
                                  (not (str/blank? (str tz-from-entity))))
                         tz-from-entity)
        entity-tz      (or tz-from-entity user-tz "UTC")
        show-tz?       (and tz-from-entity (not= tz-from-entity user-tz))]
    {:user-tz   user-tz
     :entity-tz entity-tz
     :show-tz?  show-tz?}))

(defn format-entity-instant
  [instant time-zone]
  (when (and instant time-zone)
    (format-date-time-local (t/instant instant) time-zone)))

(defn build-time-display
  [entity entity-str display-fields ctx]
  (let [timestamp-field   (find-field-by-pattern entity entity-str "timestamp")
        timestamp-key     (first timestamp-field)
        timestamp-value   (second timestamp-field)
        timestamp-instant (as-instant timestamp-value)
        timestamp-label   (or (display-label display-fields timestamp-key)
                              (when timestamp-key (humanize-key timestamp-key)))

        beginning-field   (find-field-by-pattern entity entity-str "beginning")
        beginning-key     (first beginning-field)
        beginning-value   (second beginning-field)
        beginning-instant (as-instant beginning-value)
        beginning-label   (or (display-label display-fields beginning-key)
                              (when beginning-key (humanize-key beginning-key))
                              "Beginning")

        end-field         (find-field-by-pattern entity entity-str "end")
        end-key           (first end-field)
        end-value         (second end-field)
        end-instant       (as-instant end-value)

        duration          (when (and beginning-instant end-instant)
                            (format-duration beginning-instant end-instant))
        {:keys [entity-tz show-tz?]} (entity-time-zone-info entity entity-str ctx)
        mode              (cond
                            (and beginning-instant end-instant) :duration
                            timestamp-instant                   :timestamp
                            beginning-instant                   :beginning
                            :else                               nil)
        time-instant      (case mode
                            :timestamp timestamp-instant
                            :beginning beginning-instant
                            nil)
        time-str          (when time-instant
                            (format-entity-instant time-instant entity-tz))
        label             (case mode
                            :timestamp timestamp-label
                            :beginning beginning-label
                            nil)
        since-instant     (case mode
                            :duration end-instant
                            :timestamp timestamp-instant
                            :beginning beginning-instant
                            nil)]
    {:mode            mode
     :label           label
     :time-str        time-str
     :time-zone       (when show-tz? entity-tz)
     :duration        duration
     :since-instant   since-instant
     :timestamp-key   timestamp-key
     :beginning-key   beginning-key
     :end-key         end-key}))

;; Card view implementation
(defn render-card-view
  [{:keys [paginated-entities display-fields entity-str]} ctx]
  [:div.grid.grid-cols-1.md:grid-cols-2.lg:grid-cols-3.gap-4
   (for [entity paginated-entities]
     (let [;; Find key fields
           entity-id         (:xt/id entity)

           label-key         (keyword entity-str "label")
           label-value       (get entity label-key)
           label-field-info  (find-display-field display-fields label-key)
           label-in-schema?  (field-exists-in-schema? label-key display-fields)

           {:keys [mode
                   label
                   time-str
                   time-zone
                   duration
                   since-instant
                   timestamp-key
                   beginning-key
                   end-key]} (build-time-display entity entity-str display-fields ctx)

           ;; Determine primary title field
           sorted-fields     (sort-by-priority-then-arbitrary display-fields)
           prioritized       (prioritized-fields sorted-fields)
           first-priority-field
           (some (fn [{:keys [field-key] :as field}]
                   (let [value (get entity field-key)]
                     (when (and (not= field-key label-key)
                                (some? value)
                                (not (and (string? value)
                                          (str/blank? value))))
                       (assoc field :value value))))
                 prioritized)
           label-title-field (when (and (some? label-value)
                                        (not (and (string? label-value)
                                                  (str/blank? label-value))))
                               (merge {:field-key  label-key
                                       :input-type (:input-type label-field-info)
                                       :opts       (:opts label-field-info)}
                                      label-field-info
                                      {:value label-value}))
           title-field       (or label-title-field first-priority-field)
           title-key         (:field-key title-field)
           title-label       (or (:input-label title-field)
                                 (display-label display-fields title-key))
           title-content     (when title-field
                               (if (= title-key label-key)
                                 (let [value (:value title-field)]
                                   (if (string? value) value (str value)))
                                 (format-cell-value (:input-type title-field)
                                                    (:value title-field)
                                                    ctx)))
           title-raw-value   (:value title-field)

           ;; Helper for rendering content consistently
           ->display         (fn [content]
                               (cond
                                 (nil? content) [:span.text-secondary.italic "None"]
                                 (vector? content) content
                                 :else [:span content]))

           ;; Additional highlight fields
           highlight-fields  (->> prioritized
                                  (remove #(let [fk (:field-key %)]
                                             (or (= fk label-key)
                                                 (= fk timestamp-key)
                                                 (= fk beginning-key)
                                                 (= fk end-key)
                                                 (= fk title-key))))
                                  (map (fn [{:keys [field-key input-type input-label]}]
                                         (let [value (get entity field-key)]
                                           (when (and (some? value)
                                                      (not (and (string? value)
                                                                (str/blank? value))))
                                             {:field-key   field-key
                                              :input-label (or input-label
                                                                (display-label display-fields field-key))
                                              :content     (format-cell-value input-type value ctx)}))))
                                  (remove nil?)
                                  (take 4))

           aria-title        (or (when (string? title-content) title-content)
                                 (when (string? label-value) label-value)
                                 (some-> title-raw-value str)
                                 entity-str)
           title-node        (cond
                               (= title-key label-key)
                               [:h2.card-header.truncate
                                (if (string? title-content)
                                  title-content
                                  (or (some-> title-raw-value str)
                                      entity-str))]
                               title-field
                               (let [content-node (->display title-content)]
                                 [:div.space-y-1
                                  [:span.card-text-secondary.font-medium title-label]
                                  [:div.card-text.font-medium content-node]])
                               (and label-in-schema? (nil? label-value))
                               [:span.card-text-secondary.italic "No label"]
                               :else
                               [:span.card-text-secondary.italic "Untitled"])
           time-row          (case mode
                               :duration
                               (when duration
                                 [:div.flex.items-center.justify-between.gap-3
                                  [:div.flex.items-center.gap-2.text-sm.text-secondary
                                   [:span.font-medium "Duration:"]
                                   [:span duration]]
                                  (when since-instant
                                    [:span.card-tag.text-xs.font-medium
                                     (format-relative-time since-instant)])])
                               (:timestamp :beginning)
                               (when time-str
                                 [:div.flex.flex-wrap.items-center.justify-between.gap-3
                                  [:div.flex.flex-wrap.items-center.gap-2.text-sm.text-secondary
                                   (when label
                                     [:span.font-medium (str label ":")])
                                   [:span time-str]
                                   (when time-zone
                                     [:span.text-xs.text-secondary.font-mono
                                      (str "TZ " time-zone)])]
                                  (when since-instant
                                    [:span.card-tag.text-xs.font-medium
                                     (format-relative-time since-instant)])])
                               nil)]

       [:div.card-container.group
        {:key (str entity-id)}

        [:div.card-tag.font-mono
         (str (subs (str entity-id) 0 8) "...")]

        [:a.flex-grow.flex.flex-col.pt-8.pb-4.px-4.relative.z-10
         {:href (str "/app/crud/form/" entity-str "/edit/" entity-id),
          :class
          "focus:outline-none focus:ring-2 focus:ring-blue-300 focus:ring-inset",
          :aria-label (str "Edit " aria-title),
          :role "button"}

         [:div.flex-grow.space-y-4
          title-node
          (when time-row time-row)
          (when (seq highlight-fields)
            [:div.space-y-3
             (for [{:keys [field-key input-label content]} highlight-fields]
               [:div {:key (name field-key)
                      :class "flex flex-col gap-1"}
                [:span.card-text-secondary.font-medium input-label]
                [:div.card-text (->display content)]])])]]

        [:div.absolute.bottom-3.right-3.z-20
         (biff/form
          {:action (str "/app/crud/" entity-str "/" entity-id "/delete"),
           :method "post",
           :class "inline",
           :onsubmit
           "return confirm('Are you sure you want to delete this item? This action cannot be undone.');"}
          [:button.text-secondary.hover:text-red-600.transition-colors
           {:type       "submit",
            :aria-label (str "Delete " aria-title)}
            [:svg.h-4.w-4
             {:xmlns   "http://www.w3.org/2000/svg",
              :viewBox "0 0 20 20",
              :fill    "currentColor"}
             [:path
              {:fill-rule "evenodd",
               :d
               "M9 2a1 1 0 00-.894.553L7.382 4H4a1 1 0 000 2v10a2 2 0 002 2h8a2 2 0 002-2V6a1 1 0 100-2h-3.382l-.724-1.447A1 1 0 0011 2H9zM7 8a1 1 0 012 0v6a1 1 0 11-2 0V8zm5-1a1 1 0 00-1 1v6a1 1 0 102 0V8a1 1 0 00-1-1z",
               :clip-rule "evenodd"}]]])]]))])

;; ---------------------------------------------------------------------------
;; Condensed card grid (lightweight, requires preformatted items)
;; ---------------------------------------------------------------------------

(defn render-condensed-card-grid
  "Render a lightweight grid of cards.
   Expects {:items [{:id ... :title ... :pill ... :meta ... :href ...}], :heading ..., :subheading ..., :empty-text ...}"
  [{:keys [items heading subheading empty-text]}]
  [:div {:class "bg-dark-surface border border-dark rounded-xl p-6 shadow-lg shadow-black/20"}
   [:div.flex.items-center.justify-between.mb-6
    [:div
     (when heading [:p.text-sm.uppercase.tracking-wide.text-gray-400 heading])
     (when subheading [:h2.text-2xl.font-semibold.text-white subheading])]
    (when (seq items)
      [:span.text-sm.text-gray-500 (str "Showing " (count items))])]
   (if (seq items)
     [:div.grid.grid-cols-1.md:grid-cols-2.lg:grid-cols-3.gap-4
      (for [{:keys [id title pill meta href]} items]
        [:div.card-container.group {:key (str id)}
         [:a.flex-grow.flex.flex-col.space-y-3.p-4.rounded-lg.border.border-dark.bg-dark.hover:border-neon.transition-all.duration-150
          {:href href}
          [:div.flex.items-center.justify-between
           [:div.space-y-1
            [:p.text-xs.text-gray-500 pill]
            [:p.text-lg.font-semibold.text-white title]]
           [:div.text-xs.text-gray-400 (or meta "–")]]
          [:div.flex.items-center.gap-2
           [:span.inline-flex.items-center.rounded-full.bg-dark-light.border.border-dark.px-3.py-1.text-xs.font-semibold.text-neon
            pill]
           [:span.text-xs.text-gray-500 "Tap to edit"]]]])]
     [:p.text-sm.text-gray-400 (or empty-text "No items to display.")])])

;; List view implementation
(defn render-list-view
  [{:keys [paginated-entities display-fields entity-str]} ctx]
  [:div.list-container
   {:style {:list-style "none"}
    :class "w-full"}
   (for [entity paginated-entities]
     (let [entity-id         (:xt/id entity)
           label-key         (keyword entity-str "label")
           label-value       (get entity label-key)
           label-field-info  (find-display-field display-fields label-key)
           label-in-schema?  (field-exists-in-schema? label-key display-fields)
           sorted-fields     (sort-by-priority-then-arbitrary display-fields)
           prioritized       (prioritized-fields sorted-fields)

           {:keys [mode
                   label
                   time-str
                   time-zone
                   duration
                   since-instant
                   timestamp-key
                   beginning-key
                   end-key]} (build-time-display entity entity-str display-fields ctx)

           ;; Title determination
           first-priority-field
           (some (fn [{:keys [field-key] :as field}]
                   (let [value (get entity field-key)]
                     (when (and (not= field-key label-key)
                                (prioritized-field? field)
                                (some? value)
                                (not (and (string? value)
                                          (str/blank? value))))
                       (assoc field :value value))))
                 prioritized)
           label-title-field (when (and (some? label-value)
                                        (not (and (string? label-value)
                                                  (str/blank? label-value))))
                               (merge {:field-key  label-key
                                       :input-type (:input-type label-field-info)
                                       :opts       (:opts label-field-info)}
                                      label-field-info
                                      {:value label-value}))
           title-field       (or label-title-field first-priority-field)
           title-key         (:field-key title-field)
           title-label       (or (:input-label title-field)
                                 (display-label display-fields title-key))
           title-content     (when title-field
                               (if (= title-key label-key)
                                 (let [value (:value title-field)]
                                   (if (string? value) value (str value)))
                                 (format-cell-value (:input-type title-field)
                                                    (:value title-field)
                                                    ctx)))
           title-raw-value   (:value title-field)

           ->display         (fn [content]
                               (cond
                                 (nil? content) [:span.text-secondary.italic "None"]
                                 (vector? content) content
                                 :else [:span content]))

           highlight-fields  (->> prioritized
                                  (remove #(let [fk (:field-key %)]
                                             (or (= fk label-key)
                                                 (= fk timestamp-key)
                                                 (= fk beginning-key)
                                                 (= fk end-key)
                                                 (= fk title-key))))
                                  (map (fn [{:keys [field-key input-type input-label]}]
                                         (let [value (get entity field-key)]
                                           (when (and (some? value)
                                                      (not (and (string? value)
                                                                (str/blank? value))))
                                             {:field-key   field-key
                                              :input-label (or input-label
                                                                (display-label display-fields field-key))
                                              :content     (format-cell-value input-type value ctx)}))))
                                  (remove nil?)
                                  (take 3))

           aria-title        (or (when (string? title-content) title-content)
                                 (when (string? label-value) label-value)
                                 (some-> title-raw-value str)
                                 entity-str)
           title-node        (cond
                               (= title-key label-key)
                               [:h3.list-title.truncate
                                (if (string? title-content)
                                  title-content
                                  (or (some-> title-raw-value str) entity-str))]
                               title-field
                               [:div.flex.flex-wrap.items-center.gap-2.text-sm.text-secondary
                                [:span.font-medium (str title-label ":")]
                                (->display title-content)]
                               (and label-in-schema? (nil? label-value))
                               [:span.text-secondary.italic.text-sm "No label"]
                               :else
                               [:span.text-secondary.italic.text-sm "Untitled"])

           time-row          (case mode
                               :duration
                               (when duration
                                 [:div.flex.items-center.flex-wrap.gap-2.text-sm.text-secondary
                                  [:span.font-medium "Duration:"]
                                  [:span duration]
                                  (when since-instant
                                    [:span.card-tag.text-xs.font-medium.text-secondary
                                     (format-relative-time since-instant)])])
                               (:timestamp :beginning)
                               (when time-str
                                 [:div.flex.items-center.flex-wrap.gap-2.text-sm.text-secondary
                                  (when label
                                    [:span.font-medium (str label ":")])
                                  [:span time-str]
                                  (when time-zone
                                    [:span.text-xs.text-secondary.font-mono
                                     (str "TZ " time-zone)])
                                  (when since-instant
                                    [:span.card-tag.text-xs.font-medium.text-secondary
                                     (format-relative-time since-instant)])])
                               nil)]
       [:div
        {:key   (str entity-id)
         :class "list-item group relative"}
        [:div {:class "flex flex-col gap-4 p-4 w-full sm:flex-row sm:items-start sm:justify-between"}
         [:a {:href       (str "/app/crud/form/" entity-str "/edit/" entity-id)
              :class      "flex-1 min-w-0 w-full space-y-2 pr-4 focus:outline-none focus-visible:ring-2 focus-visible:ring-blue-300 rounded-lg"
              :aria-label (str "Edit " aria-title)}
          [:div {:class "flex flex-col gap-2"}
           title-node]
          (when time-row time-row)
          (when (seq highlight-fields)
            [:div {:class "flex flex-wrap gap-x-6 gap-y-2 text-sm"}
             (for [{:keys [field-key input-label content]} highlight-fields]
               (let [node (->display content)]
                 [:div {:key   (name field-key)
                        :class "flex flex-col gap-1 min-w-0 text-secondary"}
                  [:span.font-medium (str input-label)]
                  [:div.min-w-0 node]]))])
          [:div {:class "text-xs text-secondary font-mono opacity-70"}
           (str "ID " (subs (str entity-id) 0 8) "...")]]
         [:div {:class "flex items-center gap-3 sm:flex-col sm:items-end sm:gap-2 opacity-100 sm:opacity-0 sm:group-hover:opacity-100 transition-opacity"}
          [:a.link {:href (str "/app/crud/form/" entity-str "/edit/" entity-id)}
           "Edit"]
          (biff/form
           {:action (str "/app/crud/" entity-str "/" entity-id "/delete")
            :method "post"
            :onsubmit
            "return confirm('Are you sure you want to delete this item? This action cannot be undone.');"}
           [:button.link
            {:type "submit"}
            "Delete"])]]]))])

;; View type selector component
(defn view-selector
  [{:keys [entity-str view-type offset limit]}]
  [:div.flex.space-x-4.mb-4
   [:a.text-sm.transition-colors
    {:class (if (= view-type "table") 
              "text-primary font-medium" 
              "text-secondary hover:text-primary"),
     :href  (str "/app/crud/" entity-str
                 "?view=table"
                 (when (or offset limit)
                   (str "&offset=" (or offset 0)
                        "&limit="  (or limit 15))))}
    "Table"]
   [:a.text-sm.transition-colors
    {:class (if (= view-type "card") 
              "text-primary font-medium" 
              "text-secondary hover:text-primary"),
     :href  (str "/app/crud/" entity-str
                 "?view=card"
                 (when (or offset limit)
                   (str "&offset=" (or offset 0)
                        "&limit="  (or limit 15))))}
    "Cards"]
   [:a.text-sm.transition-colors
    {:class (if (= view-type "list") 
              "text-primary font-medium" 
              "text-secondary hover:text-primary"),
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
        ;; Query with pagination at the database level
        order-key          ::sm/created-at
        query-opts         {:entity-type-str    entity-type-str
                            :schema             schema
                            :filter-references  filter-references
                            :limit              limit
                            :offset             offset
                            :order-key          order-key
                            :order-direction    :desc}
        paginated-entities (db/all-for-user-query query-opts ctx)
        page-count         (count paginated-entities)
        more-query-opts    (assoc query-opts
                                  :offset (+ offset limit)
                                  :limit  1)
        has-more?          (and (> limit 0)
                                (seq (db/all-for-user-query more-query-opts ctx)))
        showing-start      (when (pos? page-count) (inc offset))
        showing-end        (when (pos? page-count) (+ offset page-count))
        ;; Fields
        display-fields     (get-display-fields schema)]
    (ui/page
     {}
     [:div
      (side-bar
       ctx
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

        (if (zero? page-count)
          [:div.text-lg "No items found"]
         [:div
             ;; Pagination summary
           [:div.flex.items-center.justify-between.mb-4
            [:p.text-sm.text-secondary
             (if (pos? page-count)
               (str "Showing "
                    showing-start
                    "-"
                    showing-end
                    (when has-more? "+"))
               (str "No "
                    entity-str
                    (when (not= 1 limit) "s")
                    " found"))]

              ;; Pagination controls with view type preserved
            [:div.flex.items-center.gap-4
             [:a.text-sm.text-secondary.hover:text-primary.transition-colors
              {:class (when (<= offset 0) "opacity-50 pointer-events-none"),
               :href  (if (> offset 0)
                        (str "/app/crud/" entity-str
                             "?view="     view-type
                             "&offset="   (max 0 (- offset limit))
                            "&limit="    limit)
                        "#")}
              "← Previous"]
             [:a.text-sm.text-secondary.hover:text-primary.transition-colors
              {:href  (if has-more?
                        (str "/app/crud/" entity-str
                             "?view="     view-type
                             "&offset="   (+ offset limit)
                             "&limit="    limit)
                        "#"),
               :class (when (not has-more?)
                        "opacity-50 pointer-events-none")}
              "Next →"]]]

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
