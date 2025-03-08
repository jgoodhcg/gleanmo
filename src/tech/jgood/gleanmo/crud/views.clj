(ns tech.jgood.gleanmo.crud.views
  (:require
   [clojure.pprint :refer [pprint]]
   [clojure.string :as str]
   [com.biffweb :as biff]
   [potpuri.core :as pot]
   [tech.jgood.gleanmo.app.shared :refer [side-bar
                                         get-user-time-zone
                                         link-button
                                         format-date-time-local
                                         param-true?]]
   [tech.jgood.gleanmo.crud.operations :as ops]
   [tech.jgood.gleanmo.crud.fields :as f]
   [tech.jgood.gleanmo.ui :as ui]
   [tech.jgood.gleanmo.schema.meta :as sm]
   [xtdb.api :as xt]))

;; Multimethod for formatting cell values based on field type
(defmulti format-cell-value (fn [type value _] type))

(defmethod format-cell-value :string [_ value _]
  (if (or (nil? value) (str/blank? value))
    [:span.text-gray-400 "—"]
    [:span (str value)]))

(defmethod format-cell-value :boolean [_ value _]
  (if value
    [:span.text-green-600 "✓"]
    [:span.text-red-600 "✗"]))

(defmethod format-cell-value :number [_ value _]
  (if (nil? value)
    [:span.text-gray-400 "—"]
    [:span (str value)]))

(defmethod format-cell-value :float [_ value _]
  (if (nil? value)
    [:span.text-gray-400 "—"]
    [:span (format "%.2f" value)]))

(defmethod format-cell-value :int [_ value _]
  (if (nil? value)
    [:span.text-gray-400 "—"]
    [:span (str value)]))

(defmethod format-cell-value :instant [_ value ctx]
  (if (nil? value)
    [:span.text-gray-400 "—"]
    (let [time-zone (get-user-time-zone ctx)]
      [:span (format-date-time-local value time-zone)])))

(defmethod format-cell-value :single-relationship [_ value {:keys [biff/db] :as ctx}]
  (if (nil? value)
    [:span.text-gray-400 "—"]
    (let [entity (xt/entity db value)
          entity-type (or (-> entity ::sm/type name)
                          (some-> entity keys first namespace))
          label-key (when entity-type (keyword entity-type "label"))
          label (if (and label-key (contains? entity label-key))
                  (get entity label-key)
                  (str (subs (str value) 0 8) "..."))]
      [:span.text-blue-600 label])))

(defmethod format-cell-value :many-relationship [_ values {:keys [biff/db] :as ctx}]
  (cond
    (nil? values)   [:span.text-gray-400 "—"]
    (empty? values) [:span.text-gray-400 "Empty set"]
    :else
    (let [labels         (for [value values]
                           (let [entity      (xt/entity db value)
                                 entity-type (or (-> entity ::sm/type name)
                                                 (some-> entity keys first namespace))
                                 label-key   (when entity-type (keyword entity-type "label"))]
                             (if (and label-key (contains? entity label-key))
                               (get entity label-key)
                               (str (subs (str value) 0 8) "..."))))
          tooltip-labels (str/join ", " labels)]
      [:div
       [:span.bg-blue-100.text-blue-800.text-xs.font-medium.px-2.py-0.5.rounded-full.cursor-help
        {:title tooltip-labels}
        (str (count values) " item" (when (> (count values) 1) "s"))]
       [:div.text-xs.text-gray-500.mt-1.truncate {:title tooltip-labels}
        (when (seq labels)
          (str/join ", " (take 2 labels))
          (when (> (count labels) 2)
            " ..."))]]
      )))

(defmethod format-cell-value :enum [_ value _]
  (if (nil? value)
    [:span.text-gray-400 "—"]
    [:span.bg-purple-100.text-purple-800.text-xs.font-medium.px-2.py-0.5.rounded-full
     (name value)]))

(defmethod format-cell-value :default [_ value _]
  (if (nil? value)
    [:span.text-gray-400 "—"]
    [:span.text-gray-600 (str value)]))

(defn get-display-fields [schema]
  "Extract fields from schema that should be displayed in the table"
  (->> (f/extract-schema-fields schema)
       (map f/prepare)
       ;; remove internal fields
       (remove (fn [{:keys [field-key]}]
                 (let [n (namespace field-key)]
                   (or (= :xt/id field-key)
                       (= "tech.jgood.gleanmo.schema" n)
                       (= "tech.jgood.gleanmo.schema.meta" n)))))))

(defn render-table [{:keys [entities
                            display-fields
                            entity-str]} ctx]
  (let [;; Sort function that places label field first, then alphabetically by name
        sort-with-label-first (fn [fields]
                                (let [label-key (keyword entity-str "label")]
                                  ;; If we have a label field, sort it first
                                  (if (some #(= (:field-key %) label-key) fields)
                                    (concat 
                                     ;; First the label field
                                     (filter #(= (:field-key %) label-key) fields)
                                     ;; Then all other fields sorted alphabetically
                                     (sort-by (comp name :field-key) 
                                              (remove #(= (:field-key %) label-key) fields)))
                                    ;; Otherwise just sort alphabetically
                                    (sort-by (comp name :field-key) fields))))
        
        sorted-fields (sort-with-label-first display-fields)
        ;; Process fields to adjust labels and handle special cases
        processed-fields (map (fn [{:keys [field-key input-label] :as field}]
                                (if (= field-key :user/id)
                                  ;; Special case for user/id - change label to just "User"
                                  (assoc field :input-label "User") 
                                  field))
                              sorted-fields)]
    [:div.overflow-x-auto
     [:table.min-w-full.divide-y.divide-gray-200
      [:thead.bg-gray-50
       [:tr
        (for [{:keys [field-key input-label]} processed-fields]
          [:th.px-6.py-3.text-left.text-xs.font-medium.text-gray-500.uppercase.tracking-wider 
           {:key (name field-key)} 
           input-label])]]
      [:tbody.bg-white.divide-y.divide-gray-200
       (map-indexed
        (fn [idx entity]
          [:tr {:key idx
                :class (when (odd? idx) "bg-gray-50")}
           (for [{:keys [field-key input-type type]} processed-fields]
             [:td.px-6.py-4.whitespace-nowrap.text-sm.text-gray-900 
              {:key (name field-key)}
              (cond
                ;; Special case for user/id
                (= field-key :user/id)
                [:span.text-gray-600 (str (some-> entity (get field-key) str (subs 0 8)) "...")]
                
                ;; Default case - use the regular formatter
                :else
                (format-cell-value input-type (get entity field-key) ctx))])])
        entities)]]]))

(defn list-entities [{:keys [entity-key
                             entity-str
                             plural-str
                             schema] :as args}
                     {:keys [session biff/db] :as ctx}]
  (let [user-id              (:uid session)
        {:user/keys [email]} (xt/entity db user-id)
        entity-type-str      (name entity-key)
        entities             (ops/all-for-user-query {:entity-type-str entity-type-str} ctx)
        display-fields       (get-display-fields schema)]
    (ui/page
     {}
     [:div
      (side-bar (pot/map-of email)
                [:div.p-4 
                 [:h1.text-2xl.font-bold.mb-4
                  (str/capitalize plural-str)]

                 (if (empty? entities)
                   [:div.text-lg "No items found"]
                   [:div
                    [:div.mb-6
                     (render-table (pot/map-of entities
                                               display-fields
                                               entity-str) ctx)]
                    ])])])))
