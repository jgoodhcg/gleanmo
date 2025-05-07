(ns tech.jgood.gleanmo.crud.views
  (:require
   [clojure.string :as str]
   [com.biffweb :as biff]
   [potpuri.core :as pot]
   [tech.jgood.gleanmo.app.shared :refer
    [side-bar]]
   [tech.jgood.gleanmo.crud.schema-utils :as schema-utils]
   [tech.jgood.gleanmo.crud.views.formatting :refer [format-cell-value]]
   [tech.jgood.gleanmo.db.queries :as db]
   [tech.jgood.gleanmo.ui :as ui]))

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

              ;; Pagination controls
            [:div.flex.items-center.gap-2
             [:a.px-3.py-1.rounded.border
              {:class (if (> offset 0)
                        "bg-blue-500 text-white"
                        "bg-gray-100 text-gray-400"),
               :href  (if (> offset 0)
                        (str "/app/crud/" entity-str
                             "?offset="   (max 0 (- offset limit))
                             "&limit="    limit)
                        "#")}
              "Previous"]
             [:a.px-3.py-1.rounded.border.bg-blue-500.text-white
              {:href  (if (< (+ offset (count paginated-entities))
                             total-count)
                        (str "/app/crud/" entity-str
                             "?offset="   (+ offset limit)
                             "&limit="    limit)
                        "#"),
               :class (if (< (+ offset (count paginated-entities))
                             total-count)
                        "bg-blue-500 text-white"
                        "bg-gray-100 text-gray-400")}
              "Next"]]]

             ;; Table
           [:div.mb-6
            (render-table (pot/map-of paginated-entities
                                      display-fields
                                      entity-str)
                          ctx)]])])])))
