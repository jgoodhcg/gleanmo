(ns tech.jgood.gleanmo.crud.forms.inputs
  (:require
   [clojure.string :as str]
   [tech.jgood.gleanmo.app.shared :refer
    [format-date-time-local
     get-user-time-zone]]
   [tech.jgood.gleanmo.db.queries :refer [all-for-user-query]]
   [tech.jgood.gleanmo.schema :as schema]
   [tick.core :as t])
  (:import
   [java.time ZoneId]))

;; Multimethod for form input fields
(defmulti render
  "Render an input field based on its type.
   Dispatches on the :input-type of the field."
  :input-type)

(defmethod render :string
  [field ctx]
  (let [{:keys [input-name
                input-label
                input-required
                value]}
        field
        time-zone (get-user-time-zone ctx)]
    (cond
      (str/includes? input-name "label")
      [:div
       [:label.block.text-sm.font-medium.leading-6.text-gray-900
        {:for input-name} input-label]
       [:div.mt-2
        [:input.rounded-md.shadow-sm.block.w-full.border-0.py-1.5.text-gray-900.focus:ring-2.focus:ring-blue-600
         (cond-> {:type         "text",
                  :name         input-name,
                  :required     input-required,
                  :autocomplete "off"}
           value (assoc :value value))]]]

      (str/includes? input-name "time-zone")
      [:div
       [:label.block.text-sm.font-medium.leading-6.text-gray-900
        {:for input-name} input-label]
       [:div.mt-2
        [:select.rounded-md.shadow-sm.block.w-full.border-0.py-1.5.text-gray-900.focus:ring-2.focus:ring-blue-600
         {:name         input-name,
          :required     input-required,
          :autocomplete "off"}
         (->> (ZoneId/getAvailableZoneIds)
              sort
              (mapv (fn [zoneId]
                      [:option
                       {:value    zoneId,
                        :selected (or (= zoneId value) (= zoneId time-zone))}
                       zoneId])))]]]
      :else
      [:div
       [:label.block.text-sm.font-medium.leading-6.text-gray-900
        {:for input-name} input-label]
       [:div.mt-2
        [:textarea.rounded-md.shadow-sm.block.w-full.border-0.py-1.5.text-gray-900.focus:ring-2.focus:ring-blue-600
         (cond-> {:name         input-name,
                  :rows         3,
                  :required     input-required,
                  :placeholder  "...",
                  :autocomplete "off"}
           value (assoc :value value))]]])))

(defmethod render :boolean
  [field _]
  (let [{:keys [input-name
                input-label
                value]}
        field]
    [:div.flex.items-center
     [:input.rounded.shadow-sm.mr-2.text-indigo-600.focus:ring-blue-500.focus:border-indigo-500
      (cond-> {:type         "checkbox",
               :name         input-name,
               :autocomplete "off"}
        value (assoc :checked "checked"))]
     [:label.text-sm.font-medium.leading-6.text-gray-900 {:for input-name}
      input-label]]))

(defmethod render :number
  [field _]
  (let [{:keys [input-name
                input-label
                input-required
                value]}
        field]
    [:div
     [:label.block.text-sm.font-medium.leading-6.text-gray-900 {:for input-name}
      input-label]
     [:div.mt-2
      [:input.rounded-md.shadow-sm.block.w-full.border-0.py-1.5.text-gray-900.focus:ring-2.focus:ring-blue-600
       (cond-> {:type         "number",
                :step         "any",
                :name         input-name,
                :required     input-required,
                :autocomplete "off"}
         value (assoc :value value))]]]))

(defmethod render :int
  [field _]
  (let [{:keys [input-name
                input-label
                input-required
                value]}
        field]
    [:div
     [:label.block.text-sm.font-medium.leading-6.text-gray-900 {:for input-name}
      input-label]
     [:div.mt-2
      [:input.rounded-md.shadow-sm.block.w-full.border-0.py-1.5.text-gray-900.focus:ring-2.focus:ring-blue-600
       (cond-> {:type         "number",
                :step         "1",
                :name         input-name,
                :required     input-required,
                :autocomplete "off"}
         value (assoc :value value))]]]))

(defmethod render :float
  [field _]
  (let [{:keys [input-name
                input-label
                input-required
                value]}
        field]
    [:div
     [:label.block.text-sm.font-medium.leading-6.text-gray-900 {:for input-name}
      input-label]
     [:div.mt-2
      [:input.rounded-md.shadow-sm.block.w-full.border-0.py-1.5.text-gray-900.focus:ring-2.focus:ring-blue-600
       (cond-> {:type         "number",
                :step         "0.001",
                :name         input-name,
                :required     input-required,
                :autocomplete "off"}
         value (assoc :value value))]]]))

(defmethod render :instant
  [field ctx]
  (let [{:keys [input-name
                input-label
                input-required
                value]}
        field
        time-zone (get-user-time-zone ctx)
        time-zone (if (some? time-zone) time-zone "US/Eastern")
        formatted-time (if value
                         (format-date-time-local value time-zone)
                         (format-date-time-local (t/now) time-zone))]
    [:div
     [:label.block.text-sm.font-medium.leading-6.text-gray-900 {:for input-name}
      input-label]
     [:div.mt-2
      [:input.rounded-md.shadow-sm.block.w-full.border-0.py-1.5.text-gray-900.focus:ring-2.focus:ring-blue-600
       {:type     "datetime-local",
        :name     input-name,
        :required input-required,
        :value    formatted-time}]]]))

(defmethod render :single-relationship
  [field ctx]
  (let [{:keys [input-name
                input-label
                input-required
                related-entity-str
                value]}
        field
        label-key (keyword related-entity-str "label")
        id-key :xt/id
        entity-schema (get schema/schema (keyword related-entity-str))
        options (->> (all-for-user-query {:entity-type-str related-entity-str,
                                          :schema entity-schema}
                                         ctx)
                     (map (fn [e] {:id (id-key e), :label (label-key e)})))]
    [:div
     [:label.block.text-sm.font-medium.leading-6.text-gray-900 {:for input-name}
      input-label]
     [:select.rounded-md.shadow-sm.block.w-full.border-0.py-1.5.text-gray-900.focus:ring-2.focus:ring-blue-600
      {:name     input-name,
       :required input-required}
      (for [{:keys [id label]} options]
        [:option {:value id, :selected (= (str id) (str value))} label])]]))

(defmethod render :many-relationship
  [field ctx]
  (let [{:keys [input-name
                input-label
                input-required
                related-entity-str
                value]}
        field
        label-key (keyword related-entity-str "label")
        id-key :xt/id
        entity-schema (get schema/schema (keyword related-entity-str))
        options (->> (all-for-user-query {:entity-type-str related-entity-str,
                                          :schema entity-schema}
                                         ctx)
                     (map (fn [e] {:id (id-key e), :label (label-key e)})))
        value-set (when value (set (map str value)))]
    [:div
     [:label.block.text-sm.font-medium.leading-6.text-gray-900 {:for input-name}
      input-label]
     [:select.rounded-md.shadow-sm.block.w-full.border-0.py-1.5.text-gray-900.focus:ring-2.focus:ring-blue-600
      {:name     input-name,
       :multiple true,
       :required input-required}
      (->> options
           (mapv (fn [{:keys [id label]}]
                   [:option
                    {:value    id,
                     :selected (and value-set (contains? value-set (str id)))}
                    label])))]]))

(defmethod render :enum
  [field _]
  (let [{:keys [enum-options
                input-name
                input-label
                input-required
                value]}
        field]
    [:div
     [:label.block.text-sm.font-medium.leading-6.text-gray-900 {:for input-name}
      input-label]
     [:div.mt-2
      [:select.rounded-md.shadow-sm.block.w-full.border-0.py-1.5.text-gray-900.focus:ring-2.focus:ring-blue-600
       {:name     input-name,
        :required input-required}
       (->> enum-options
            (mapv (fn [opt]
                    [:option
                     {:value    (name opt),
                      :selected (= (keyword opt) value)}
                     (name opt)])))]]]))

(defmethod render :default
  [field _]
  [:div "Unsupported field type: " (pr-str field)])
