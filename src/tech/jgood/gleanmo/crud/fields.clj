(ns tech.jgood.gleanmo.crud.fields
  (:require
   [clojure.string :as str]
   [tech.jgood.gleanmo.app.shared :refer [format-date-time-local get-user-time-zone]]
   [tech.jgood.gleanmo.crud.operations :refer [all-for-user-query]]
   [tick.core :as t])
  (:import
   [java.time ZoneId]))

(defn parse-field 
  "Extracts field key, options, and type from a schema entry.
   Handles entries with and without option maps."
  [[key-or-opts & _ :as entry]]
  (let [has-opts (map? (second entry))
        k        (if has-opts key-or-opts (first entry))
        opts     (if has-opts (second entry) {})
        type     (if has-opts (nth entry 2) (second entry))]
    {:field-key k
     :opts      opts
     :type      type}))

(defn extract-schema-fields
  "Extracts fields from a schema, skipping the schema identifier and options if present"
  [schema]
  (let [has-opts (map? (second schema))]
    (if has-opts
      (drop 2 schema)  ; Skip schema type and options map
      (rest schema)))) ; Just skip schema type

(defn get-field-info
  "Returns a map with :type and :opts for a given field key from a schema"
  [schema k]
  (some (fn [[field-key & rest]]
          (when (= field-key k)
            (if (map? (first rest))
              {:type (second rest)
               :opts (first rest)}  ; When an options map is present
              {:type (first rest)
               :opts {}}))) ; When no options map is present
        (extract-schema-fields schema)))

(defn add-input-name-label [{:keys [field-key opts] :as field}]
  (let [n (-> field-key str (str/replace ":" ""))
        l (-> field-key name (str/split #"-")
              (->> (map str/capitalize))
              (->> (str/join " ")))
        required (not (:optional opts))]
    (merge field {:input-name   n
                  :input-label  l
                  :input-required required})))

(defn determine-input-type
  "Determines the input type based on the field type definition.
   Handles enum fields, relationships, and primitive types."
  [type]
  (cond
    (and (vector? type) (= :enum (first type)))
    {:input-type :enum
     :enum-options (vec (rest type))
     :related-entity-str nil}
    
    (and (vector? type) 
         (= :set (first type))
         (let [elem (second type)]
           (and (keyword? elem) (= "id" (name elem)))))
    {:input-type :many-relationship
     :enum-options nil
     :related-entity-str (-> type second namespace)}
    
    (and (keyword? type) (= "id" (name type)))
    {:input-type :single-relationship
     :enum-options nil
     :related-entity-str (-> type namespace)}
    
    :else
    {:input-type type
     :enum-options nil
     :related-entity-str nil}))

(defn add-descriptors
  "Returns a descriptor with an :input-type key for dispatching.
   Assumes enum fields are defined as [:enum ...] and relationships are inferred from keywords."
  [{:keys [field-key type] :as field}]
  (let [type-info (determine-input-type type)]
    (merge field 
           {:field-key field-key}
           type-info)))

(defn prepare [field]
  (-> field
      parse-field
      add-descriptors
      add-input-name-label))

(defmulti input :input-type)

(defmethod input :string [field ctx]
  (let [{:keys [input-name
                input-label
                input-required]} field
        time-zone                 (get-user-time-zone ctx)]
    (cond
      (str/includes? input-name "label")
      [:div
       [:label.block.text-sm.font-medium.leading-6.text-gray-900 {:for input-name} input-label]
       [:div.mt-2
        [:input.rounded-md.shadow-sm.block.w-full.border-0.py-1.5.text-gray-900.focus:ring-2.focus:ring-blue-600
         {:type "text" 
          :name input-name 
          :required input-required
          :autocomplete "off"}]]]

      (str/includes? input-name "time-zone")
      [:div
       [:label.block.text-sm.font-medium.leading-6.text-gray-900 {:for input-name} input-label]
       [:div.mt-2
        [:select.rounded-md.shadow-sm.block.w-full.border-0.py-1.5.text-gray-900.focus:ring-2.focus:ring-blue-600
         {:name input-name 
          :required input-required 
          :autocomplete "off"}
         (->> (ZoneId/getAvailableZoneIds)
              sort
              (map (fn [zoneId]
                     [:option {:value    zoneId
                               :selected (= zoneId time-zone)} zoneId])))]]]
      :else
      [:div
       [:label.block.text-sm.font-medium.leading-6.text-gray-900 {:for input-name} input-label]
       [:div.mt-2
        [:textarea.rounded-md.shadow-sm.block.w-full.border-0.py-1.5.text-gray-900.focus:ring-2.focus:ring-blue-600
         {:name input-name 
          :rows 3 
          :required input-required
          :placeholder "..." 
          :autocomplete "off"}]]])))

(defmethod input :boolean [field _]
  (let [{:keys [input-name 
                input-label
                input-required]} field]
    [:div.flex.items-center
     [:input.rounded.shadow-sm.mr-2.text-indigo-600.focus:ring-blue-500.focus:border-indigo-500
      {:type "checkbox" 
       :name input-name 
       :required input-required
       :autocomplete "off"}]
     [:label.text-sm.font-medium.leading-6.text-gray-900 {:for input-name} input-label]]))

(defmethod input :number [field _]
  (let [{:keys [input-name 
                input-label
                input-required]} field]
    [:div
     [:label.block.text-sm.font-medium.leading-6.text-gray-900 {:for input-name} input-label]
     [:div.mt-2
      [:input.rounded-md.shadow-sm.block.w-full.border-0.py-1.5.text-gray-900.focus:ring-2.focus:ring-blue-600
       {:type "number" 
        :step "any" 
        :name input-name 
        :required input-required
        :autocomplete "off"}]]]))

(defmethod input :int [field _]
  (let [{:keys [input-name 
                input-label
                input-required]} field]
    [:div
     [:label.block.text-sm.font-medium.leading-6.text-gray-900 {:for input-name} input-label]
     [:div.mt-2
      [:input.rounded-md.shadow-sm.block.w-full.border-0.py-1.5.text-gray-900.focus:ring-2.focus:ring-blue-600
       {:type "number" 
        :step "1" 
        :name input-name 
        :required input-required
        :autocomplete "off"}]]]))

(defmethod input :float [field _]
  (let [{:keys [input-name 
                input-label
                input-required]} field]
    [:div
     [:label.block.text-sm.font-medium.leading-6.text-gray-900 {:for input-name} input-label]
     [:div.mt-2
      [:input.rounded-md.shadow-sm.block.w-full.border-0.py-1.5.text-gray-900.focus:ring-2.focus:ring-blue-600
       {:type "number" 
        :step "0.001" 
        :name input-name 
        :required input-required
        :autocomplete "off"}]]]))

(defmethod input :instant [field ctx]
  (let [{:keys [input-name
                input-label
                input-required]} field
        time-zone                 (get-user-time-zone ctx)
        time-zone                 (if (some? time-zone) time-zone "US/Eastern")
        current-time              (format-date-time-local (t/now) time-zone)]  ; assumes the current time is passed in the field map
    [:div
     [:label.block.text-sm.font-medium.leading-6.text-gray-900 {:for input-name} input-label]
     [:div.mt-2
      [:input.rounded-md.shadow-sm.block.w-full.border-0.py-1.5.text-gray-900.focus:ring-2.focus:ring-blue-600
       {:type     "datetime-local"
        :name     input-name
        :required input-required
        :value    current-time}]]]))

(defmethod input :single-relationship [field ctx]
  (let [{:keys [input-name
                input-label
                input-required
                related-entity-str]} field
        label-key                    (keyword related-entity-str "label")
        id-key                       :xt/id
        options                      (->> (all-for-user-query {:entity-type-str related-entity-str} ctx)
                                          (map (fn [e] {:id (id-key e) :label (label-key e)})))]
    [:div
     [:label.block.text-sm.font-medium.leading-6.text-gray-900 {:for input-name} input-label]
     [:select.rounded-md.shadow-sm.block.w-full.border-0.py-1.5.text-gray-900.focus:ring-2.focus:ring-blue-600
      {:name input-name 
       :required input-required}
      (for [{:keys [id label]} options]
        [:option {:value id} label])]]))

(defmethod input :many-relationship [field ctx]
  (let [{:keys [input-name
                input-label
                input-required
                related-entity-str]} field
        label-key                    (keyword related-entity-str "label")
        id-key                       :xt/id
        options                      (->> (all-for-user-query {:entity-type-str related-entity-str} ctx)
                                          (map (fn [e] {:id (id-key e) :label (label-key e)})))]
    [:div
     [:label.block.text-sm.font-medium.leading-6.text-gray-900 {:for input-name} input-label]
     [:select.rounded-md.shadow-sm.block.w-full.border-0.py-1.5.text-gray-900.focus:ring-2.focus:ring-blue-600
      {:name input-name 
       :multiple true
       :required input-required}
      (for [{:keys [id label]} options]
        [:option {:value id} label])]]))

(defmethod input :enum [field _]
  (let [{:keys [enum-options
                input-name
                input-label
                input-required]} field]
    [:div
     [:label.block.text-sm.font-medium.leading-6.text-gray-900 {:for input-name} input-label]
     [:div.mt-2
      [:select.rounded-md.shadow-sm.block.w-full.border-0.py-1.5.text-gray-900.focus:ring-2.focus:ring-blue-600
       {:name input-name 
        :required input-required}
       (for [opt enum-options]
         [:option {:value (name opt)} (name opt)])]]]))

(defmethod input :default [field _]
  [:div "Unsupported field type: " (pr-str field)])
