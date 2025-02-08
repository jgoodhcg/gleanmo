(ns tech.jgood.gleanmo.crud.fields
  (:require [clojure.string :as str]
            [potpuri.core :as pot]
            [tech.jgood.gleanmo.crud.operations :refer [all-for-user-query]]))

(defn parse-field-key [{:keys [field-key]}]
  (let [n (-> field-key str rest str/join (str/replace "/" "-"))
        l (-> field-key name (str/split #"-")
              (->> (map str/capitalize))
              (->> (str/join " ")))]
    (pot/map-of n l)))

(defn field-type-descriptor
  "Returns a descriptor with an :input-type key for dispatching.
   Assumes enum fields are defined as [:enum ...] and relationships are inferred from keywords."
  [{:keys [field-key type]}]
  (if (and (vector? type) (= :enum (first type)))
    {:field-key   field-key
     :input-type  :enum
     :enum-options (vec (rest type))}
    (let [has-id-in-set (and (vector? type)
                             (= :set (first type))
                             (let [elem (second type)]
                               (and (keyword? elem) (= "id" (name elem)))))
          is-keyword    (keyword? type)
          is-id         (and is-keyword (= "id" (name type)))
          related-str   (cond
                          is-id          (-> type namespace)
                          has-id-in-set  (-> type second namespace))
          input-type    (cond
                          has-id-in-set :many-relationship
                          is-id         :single-relationship
                          :else         type)]
      {:field-key          field-key
       :input-type         input-type
       :related-entity-str related-str})))

(defmulti field-input
  (fn [field ctx]
    (:input-type (field-type-descriptor field))))

(defmethod field-input :string [field ctx]
  (let [{:keys [n l]} (parse-field-key field)]
    (if (str/includes? n "label")
      [:div
       [:label.block.text-sm.font-medium.leading-6.text-gray-900 {:for n} l]
       [:div.mt-2
        [:input.rounded-md.shadow-sm.block.w-full.border-0.py-1.5.text-gray-900.focus:ring-2.focus:ring-blue-600
         {:type "text" :name n :autocomplete "off"}]]]
      [:div
       [:label.block.text-sm.font-medium.leading-6.text-gray-900 {:for n} l]
       [:div.mt-2
        [:textarea.rounded-md.shadow-sm.block.w-full.border-0.py-1.5.text-gray-900.focus:ring-2.focus:ring-blue-600
         {:name n :rows 3 :placeholder "..." :autocomplete "off"}]]])))

(defmethod field-input :boolean [field _]
  (let [{:keys [n l]} (parse-field-key field)]
    [:div.flex.items-center
     [:input.rounded.shadow-sm.mr-2.text-indigo-600.focus:ring-blue-500.focus:border-indigo-500
      {:type "checkbox" :name n :autocomplete "off"}]
     [:label.text-sm.font-medium.leading-6.text-gray-900 {:for n} l]]))

(defmethod field-input :number [field _]
  (let [{:keys [n l]} (parse-field-key field)]
    [:div
     [:label.block.text-sm.font-medium.leading-6.text-gray-900 {:for n} l]
     [:div.mt-2
      [:input.rounded-md.shadow-sm.block.w-full.border-0.py-1.5.text-gray-900.focus:ring-2.focus:ring-blue-600
       {:type "number" :step "any" :name n :autocomplete "off"}]]]))

(defmethod field-input :int [field _]
  (let [{:keys [n l]} (parse-field-key field)]
    [:div
     [:label.block.text-sm.font-medium.leading-6.text-gray-900 {:for n} l]
     [:div.mt-2
      [:input.rounded-md.shadow-sm.block.w-full.border-0.py-1.5.text-gray-900.focus:ring-2.focus:ring-blue-600
       {:type "number" :step "1" :name n :autocomplete "off"}]]]))

(defmethod field-input :float [field _]
  (let [{:keys [n l]} (parse-field-key field)]
    [:div
     [:label.block.text-sm.font-medium.leading-6.text-gray-900 {:for n} l]
     [:div.mt-2
      [:input.rounded-md.shadow-sm.block.w-full.border-0.py-1.5.text-gray-900.focus:ring-2.focus:ring-blue-600
       {:type "number" :step "0.001" :name n :autocomplete "off"}]]]))

(defmethod field-input :instant [field _]
  (let [{:keys [n l]} (parse-field-key field)
        current-time (:current-time field)]  ; assumes the current time is passed in the field map
    [:div
     [:label.block.text-sm.font-medium.leading-6.text-gray-900 {:for n} l]
     [:div.mt-2
      [:input.rounded-md.shadow-sm.block.w-full.border-0.py-1.5.text-gray-900.focus:ring-2.focus:ring-blue-600
       {:type "datetime-local"
        :name n
        :required true
        :value current-time}]]]))

(defmethod field-input :single-relationship [field ctx]
  (let [{:keys [n l]} (parse-field-key field)
        related-entity-str (:related-entity-str (field-type-descriptor field))
        label-key (keyword related-entity-str "label")
        id-key (keyword related-entity-str "id")
        options (->> (all-for-user-query {:entity-type-str related-entity-str} ctx)
                     (map (fn [e] {:id (id-key e) :label (label-key e)})))]
    [:div
     [:label.block.text-sm.font-medium.leading-6.text-gray-900 {:for n} l]
     [:select.rounded-md.shadow-sm.block.w-full.border-0.py-1.5.text-gray-900.focus:ring-2.focus:ring-blue-600
      {:name n :required true}
      (for [{:keys [id label]} options]
        [:option {:value id} label])]]))

(defmethod field-input :many-relationship [field ctx]
  (let [{:keys [n l]} (parse-field-key field)
        related-entity-str (:related-entity-str (field-type-descriptor field))
        label-key (keyword related-entity-str "label")
        id-key (keyword related-entity-str "id")
        options (->> (all-for-user-query {:entity-type-str related-entity-str} ctx)
                     (map (fn [e] {:id (id-key e) :label (label-key e)})))]
    [:div
     [:label.block.text-sm.font-medium.leading-6.text-gray-900 {:for n} l]
     [:select.rounded-md.shadow-sm.block.w-full.border-0.py-1.5.text-gray-900.focus:ring-2.focus:ring-blue-600
      {:name n :multiple true}
      (for [{:keys [id label]} options]
        [:option {:value id} label])]]))

(defmethod field-input :enum [field _]
  (let [{:keys [enum-options]} (field-type-descriptor field)
        {:keys [n l]}          (parse-field-key field)]
    [:div
     [:label.block.text-sm.font-medium.leading-6.text-gray-900 {:for n} l]
     [:div.mt-2
      [:select.rounded-md.shadow-sm.block.w-full.border-0.py-1.5.text-gray-900.focus:ring-2.focus:ring-blue-600
       {:name n :required true}
       (for [opt enum-options]
         [:option {:value (name opt)} (name opt)])]]]))

(defmethod field-input :default [field _]
  [:div "Unsupported field type: " (pr-str (field-type-descriptor field))])
