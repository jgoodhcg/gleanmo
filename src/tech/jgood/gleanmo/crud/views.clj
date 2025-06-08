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
   [tech.jgood.gleanmo.ui :as ui])
  (:import
   [java.time          Duration Instant]
   [java.time.temporal ChronoUnit]))

(defn get-display-fields
  "Extract fields from schema that should be displayed in the table"
  [schema]
  (->> (schema-utils/extract-schema-fields schema)
       (map schema-utils/prepare-field)
       ;; remove internal fields
       (remove (fn [{:keys [field-key]}]
                 (let [n (namespace field-key)]
                   (or (= :xt/id field-key)
                       (= "tech.jgood.gleanmo.schema" n)
                       (= "tech.jgood.gleanmo.schema.meta" n)))))))

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
    [:div.overflow-x-auto
     [:table.min-w-full.divide-y.divide-gray-200.table-fixed
      {:style {:table-layout "fixed"}} ;; Ensures fixed width columns
      [:thead.bg-gray-50
       [:tr
        (for [{:keys [field-key input-label]} processed-fields]
          [:th.px-6.py-3.text-left.text-xs.font-medium.text-gray-500.uppercase.tracking-wider
           {:key   (str (name field-key)),
            :style {:max-width "250px",
                    :overflow  "hidden"}}
           input-label])
        ;; Add actions column header
        [:th.px-6.py-3.text-right.text-xs.font-medium.text-gray-500.uppercase.tracking-wider
         "Actions"]]]
      [:tbody.bg-white.divide-y.divide-gray-200
       (map-indexed
        (fn [idx entity]
          [:tr
           {:key   idx,
            :class (when (odd? idx) "bg-gray-50")}
           (for [{:keys [field-key input-type type]} processed-fields]
             [:td.px-6.py-4.text-sm.text-gray-900
              {:key   (str (name field-key)),
               :style {:max-width "250px",
                       :overflow  "hidden"}}
              (cond
                 ;; Special case for user/id
                (= field-key :user/id)
                [:span.text-gray-600
                 (str (some-> entity
                              (get field-key)
                              str
                              (subs 0 8))
                      "...")]

                 ;; Default case - use the regular formatter
                :else
                (format-cell-value input-type (get entity field-key) ctx))])
            ;; Add edit link cell
           [:td.px-6.py-4.whitespace-nowrap.text-right.text-sm.font-medium
            [:div.flex.justify-end.space-x-4
             [:a.text-blue-600.hover:text-blue-900
              {:href (str "/app/crud/form/" entity-str
                          "/edit/" (:xt/id entity))}
              "Edit"]
             (biff/form
              {:action
               (str "/app/crud/" entity-str "/" (:xt/id entity) "/delete"),
               :method "post",
               :onsubmit
               "return confirm('Are you sure you want to delete this item? This action cannot be undone.');"}
              [:button.text-red-600.hover:text-red-900
               {:type "submit"}
               "Delete"])]]])
        paginated-entities)]]]))

;; Helper function to format relative time
(defn format-relative-time
  "Format an instant as a relative time string (e.g. '2 hours ago')"
  [instant]
  (when instant
    (let [now (Instant/now)
          seconds-diff (.until instant now ChronoUnit/SECONDS)]
      (cond
        (< seconds-diff 60)       "just now"
        (< seconds-diff 3600)     (str (quot seconds-diff 60)
                                       " minute"
                                       (when (not= 1 (quot seconds-diff 60))
                                         "s")
                                       " ago")
        (< seconds-diff 86400)    (str (quot seconds-diff 3600)
                                       " hour"
                                       (when (not= 1 (quot seconds-diff 3600))
                                         "s")
                                       " ago")
        (< seconds-diff 604800)   (str (quot seconds-diff 86400)
                                       " day"
                                       (when (not= 1 (quot seconds-diff 86400))
                                         "s")
                                       " ago")
        (< seconds-diff 2592000)  (str (quot seconds-diff 604800)
                                       " week"
                                       (when (not= 1 (quot seconds-diff 604800))
                                         "s")
                                       " ago")
        (< seconds-diff 31536000) (str (quot seconds-diff 2592000)
                                       " month"
                                       (when (not= 1
                                                   (quot seconds-diff 2592000))
                                         "s")
                                       " ago")
        :else                     (str (quot seconds-diff 31536000)
                                       " year"
                                       (when (not= 1
                                                   (quot seconds-diff 31536000))
                                         "s")
                                       " ago")))))

;; Helper function to format duration between two instants
(defn format-duration
  "Format duration between two instants"
  [start-instant end-instant]
  (when (and start-instant end-instant)
    (let [duration (Duration/between start-instant end-instant)
          hours    (.toHours duration)
          minutes  (- (.toMinutes duration) (* 60 hours))
          seconds  (- (.getSeconds duration) (* 60 (.toMinutes duration)))]
      (cond
        (pos? hours)   (format "%dh %dm" hours minutes)
        (pos? minutes) (format "%dm %ds" minutes seconds)
        :else          (format "%ds" seconds)))))

;; Helper to find any field containing a pattern in its name
(defn find-field-by-pattern
  [entity entity-str pattern]
  (->> entity
       (filter (fn [[k _]]
                 (and (keyword? k)
                      (= (namespace k) entity-str)
                      (str/includes? (name k) pattern))))
       first))

;; Find field formatter for a given field key
(defn get-field-formatter
  [field-key display-fields]
  (when field-key
    (let [field-info (->> display-fields
                          (filter #(= (:field-key %) field-key))
                          first)]
      (when field-info
        (:input-type field-info)))))

;; Helper to get value as Instant if possible
(defn as-instant 
  [value]
  (when (instance? java.util.Date value)
    (.toInstant value)))

;; Card view implementation
(defn render-card-view
  [{:keys [paginated-entities display-fields entity-str]} ctx]
  [:div.grid.grid-cols-1.md:grid-cols-2.lg:grid-cols-3.gap-4
   (for [entity paginated-entities]
     (let [;; Find key fields
           label-key         (keyword entity-str "label")
           label-value       (get entity label-key)
           entity-id         (:xt/id entity)

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

           ;; Find notes field if available
           notes-field       (find-field-by-pattern entity entity-str "notes")
           notes-key         (first notes-field)
           notes-value       (second notes-field)]

       ;; Wrapper div for entire card - clickable area
       [:div.bg-white.rounded-lg.shadow-sm.border.border-gray-100.flex.flex-col.relative.group.overflow-hidden.hover:shadow-md.transition-all.duration-200
        {:key (str entity-id)}
        
        ;; Entity type tag (subtle)
        [:div.absolute.top-2.left-2.text-xs.text-gray-400.font-light.px-1.py-0.5.rounded.bg-gray-50.opacity-60
         entity-str]

        ;; Subtle entity ID display in top-right corner
        [:div.absolute.top-2.right-2.text-xs.text-gray-400.opacity-60.font-mono
         (str (subs (str entity-id) 0 8) "...")]

        ;; Main card content area - clickable for edit
        [:a.flex-grow.flex.flex-col.p-4.relative.z-10
         {:href (str "/app/crud/form/" entity-str "/edit/" entity-id),
          :class "focus:outline-none focus:ring-2 focus:ring-blue-300 focus:ring-inset",
          :aria-label (str "Edit " (or label-value entity-str)),
          :role "button"}

         ;; Card header with label
         [:div.mb-4
          [:h3.text-lg.font-medium.text-gray-800
           (or label-value "Unnamed")]]

         ;; Main content section
         [:div.flex-grow.space-y-3

          ;; Timestamp with relative time
          (when timestamp-value
            [:div.flex.justify-between.items-baseline
             [:div
              [:span.text-sm.font-medium.text-gray-500
               (str (str/capitalize (name timestamp-key)) ":")]
              [:span.ml-1.text-sm
               (if timestamp-type
                 (format-cell-value timestamp-type timestamp-value ctx)
                 (str timestamp-value))]]
             (when timestamp-instant
               [:span.text-xs.text-gray-500.bg-gray-50.px-2.py-0.5.rounded
                (format-relative-time timestamp-instant)])])

          ;; Beginning and End with duration (if both exist)
          (when (or beginning-value end-value)
            [:div.bg-gray-50.p-2.rounded
             (when beginning-value
               [:div
                [:span.text-sm.font-medium.text-gray-500
                 (str (str/capitalize (name beginning-key)) ":")]
                [:span.ml-1.text-sm
                 (if beginning-type
                   (format-cell-value beginning-type beginning-value ctx)
                   (str beginning-value))]])

             (when end-value
               [:div.mt-1
                [:span.text-sm.font-medium.text-gray-500
                 (str (str/capitalize (name end-key)) ":")]
                [:span.ml-1.text-sm
                 (if end-type
                   (format-cell-value end-type end-value ctx)
                   (str end-value))]])

             ;; Duration display if we have both beginning and end
             (when duration
               [:div.mt-2.flex.items-center.justify-end
                [:span.text-xs.font-medium.px-2.py-0.5.bg-blue-100.text-blue-800.rounded-full
                 (str "Duration: " duration)]])])

          ;; Notes section
          (when notes-value
            [:div
             [:span.text-sm.font-medium.text-gray-500
              (str (str/capitalize (name notes-key)) ":")]
             [:div.text-sm.text-gray-600.line-clamp-2
              {:title notes-value}
              (str notes-value)]])

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
               [:div.text-sm {:key (name field-key)}
                [:span.text-gray-500.font-medium (str input-label ": ")]
                [:span
                 (format-cell-value input-type
                                    (get entity field-key)
                                    ctx)]])])]

         ;; Small icon to indicate card is clickable
         [:div.absolute.bottom-3.right-3.text-gray-400.opacity-0.group-hover:opacity-100.transition-opacity
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
          [:button.text-gray-400.hover:text-red-600.transition-colors
           {:type       "submit",
            :aria-label (str "Delete " (or label-value entity-str))}
           [:svg.h-4.w-4 
            {:xmlns "http://www.w3.org/2000/svg", 
             :viewBox "0 0 20 20", 
             :fill "currentColor"}
            [:path 
             {:fill-rule "evenodd", 
              :d "M9 2a1 1 0 00-.894.553L7.382 4H4a1 1 0 000 2v10a2 2 0 002 2h8a2 2 0 002-2V6a1 1 0 100-2h-3.382l-.724-1.447A1 1 0 0011 2H9zM7 8a1 1 0 012 0v6a1 1 0 11-2 0V8zm5-1a1 1 0 00-1 1v6a1 1 0 102 0V8a1 1 0 00-1-1z",
              :clip-rule "evenodd"}]]])]]))])

;; List view implementation
(defn render-list-view
  [{:keys [paginated-entities display-fields entity-str]} ctx]
  [:div.divide-y.divide-gray-200
   (for [entity paginated-entities]
     [:div.py-4.flex.justify-between.items-center
      {:key (str (:xt/id entity))}
      [:div.flex-1
       [:div.font-medium "List View - Entity ID: "
        (str (subs (str (:xt/id entity)) 0 8) "...")]]
      [:div.flex.space-x-2
       [:a.text-blue-600.hover:text-blue-900
        {:href (str "/app/crud/form/" entity-str "/edit/" (:xt/id entity))}
        "Edit"]
       (biff/form
        {:action (str "/app/crud/" entity-str "/" (:xt/id entity) "/delete"),
         :method "post",
         :class "inline",
         :onsubmit
         "return confirm('Are you sure you want to delete this item? This action cannot be undone.');"}
        [:button.text-red-600.hover:text-red-900
         {:type "submit"}
         "Delete"])]])])

;; View type selector component
(defn view-selector
  [{:keys [entity-str view-type offset limit]}]
  [:div.flex.space-x-2.mb-4
   [:a.px-3.py-1.rounded.border
    {:class (if (= view-type "table")
              "bg-blue-500 text-white"
              "bg-gray-100 hover:bg-gray-200"),
     :href  (str "/app/crud/" entity-str
                 "?view=table"
                 (when (or offset limit)
                   (str "&offset=" (or offset 0)
                        "&limit="  (or limit 15))))}
    "Table"]
   [:a.px-3.py-1.rounded.border
    {:class (if (= view-type "card")
              "bg-blue-500 text-white"
              "bg-gray-100 hover:bg-gray-200"),
     :href  (str "/app/crud/" entity-str
                 "?view=card"
                 (when (or offset limit)
                   (str "&offset=" (or offset 0)
                        "&limit="  (or limit 15))))}
    "Cards"]
   [:a.px-3.py-1.rounded.border
    {:class (if (= view-type "list")
              "bg-blue-500 text-white"
              "bg-gray-100 hover:bg-gray-200"),
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
        view-type          (or (:view params) "table")
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
        [:h1.text-2xl.font-bold.mb-4
         (str/capitalize plural-str)]

          ;; New entity button
        [:div.mb-4
         [:a.bg-blue-500.hover:bg-blue-700.text-white.font-bold.py-2.px-4.rounded
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
            [:p.text-sm.text-gray-600
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
             [:a.px-3.py-1.rounded.border
              {:class (if (> offset 0)
                        "bg-blue-500 text-white"
                        "bg-gray-100 text-gray-400"),
               :href  (if (> offset 0)
                        (str "/app/crud/" entity-str
                             "?view="     view-type
                             "&offset="   (max 0 (- offset limit))
                             "&limit="    limit)
                        "#")}
              "Previous"]
             [:a.px-3.py-1.rounded.border.bg-blue-500.text-white
              {:href  (if (< (+ offset (count paginated-entities))
                             total-count)
                        (str "/app/crud/" entity-str
                             "?view="     view-type
                             "&offset="   (+ offset limit)
                             "&limit="    limit)
                        "#"),
               :class (if (< (+ offset (count paginated-entities))
                             total-count)
                        "bg-blue-500 text-white"
                        "bg-gray-100 text-gray-400")}
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
