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
       [:label.form-label
        {:for input-name} input-label]
       [:div.mt-2
        [:input.form-input
         (cond-> {:type         "text",
                  :name         input-name,
                  :required     input-required,
                  :autocomplete "off"}
           value (assoc :value value))]]]

      (str/includes? input-name "time-zone")
      [:div
       [:label.form-label
        {:for input-name} input-label]
       [:div.mt-2
        [:select.form-select
         {:name         input-name,
          :required     input-required,
          :autocomplete "off"}
         (for [zoneId (sort (ZoneId/getAvailableZoneIds))]
           [:option
            {:value    zoneId,
             :selected (or (= zoneId value) (= zoneId time-zone))}
            zoneId])]]]
      :else
      [:div
       [:label.form-label
        {:for input-name} input-label]
       [:div.mt-2
        [:textarea.form-textarea
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
     [:input.mr-2
      (cond-> {:type         "checkbox",
               :name         input-name,
               :autocomplete "off"}
        value (assoc :checked "checked"))]
     [:label.form-label {:for input-name}
      input-label]]))

(defmethod render :number
  [field _]
  (let [{:keys [input-name
                input-label
                input-required
                value]}
        field]
    [:div
     [:label.form-label {:for input-name}
      input-label]
     [:div.mt-2
      [:input.form-input
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
     [:label.form-label {:for input-name}
      input-label]
     [:div.mt-2
      [:input.form-input
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
     [:label.form-label {:for input-name}
      input-label]
     [:div.mt-2
      [:input.form-input
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
        ;; Only default to now if it's a beginning timestamp or value is
        ;; already set
        formatted-time (cond
                         ;; Use existing value if provided
                         value
                         (format-date-time-local value time-zone)

                         ;; Set default now value only for beginning fields
                         (and (string? input-name)
                              (or (str/includes? input-name "beginning")
                                  (str/includes? input-name "timestamp")))
                         (format-date-time-local (t/now) time-zone)

                         ;; Otherwise, leave empty
                         :else
                         nil)]
    [:div
     [:label.form-label {:for input-name}
      input-label]
     [:div.mt-2
      [:input.form-input
       (cond-> {:type     "datetime-local",
                :name     input-name,
                :required input-required}
         formatted-time (assoc :value formatted-time))]]]))

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
     [:label.form-label {:for input-name}
      input-label]
     [:select.form-select
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
     [:label.form-label {:for input-name}
      input-label]
     [:select.form-select
      {:name     input-name,
       :multiple true,
       :required input-required}
      (for [{:keys [id label]} options]
        [:option
         {:value    id,
          :selected (and value-set (contains? value-set (str id)))}
         label])]]))

(defmethod render :enum
  [field _]
  (let [{:keys [enum-options
                input-name
                input-label
                input-required
                value]}
        field]
    [:div
     [:label.form-label {:for input-name}
      input-label]
     [:div.mt-2
      [:select.form-select
       {:name     input-name,
        :required input-required}
       ;; Add empty option for optional fields
       (when-not input-required
         [:option {:value "", :selected (nil? value)} "-- Select --"])
       (for [opt enum-options]
         [:option
          {:value    (name opt),
           :selected (= (keyword opt) value)}
          (name opt)])]]]))

(defmethod render :default
  [field _]
  [:div "Unsupported field type: " (pr-str field)])
