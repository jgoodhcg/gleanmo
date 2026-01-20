(ns tech.jgood.gleanmo.crud.forms.inputs
  (:require
   [clojure.string :as str]
   [tech.jgood.gleanmo.app.shared :refer
    [format-date-time-local
     get-user-time-zone]]
   [tech.jgood.gleanmo.db.queries :refer [all-for-user-query]]
   [tech.jgood.gleanmo.schema :as schema-registry]
   [tech.jgood.gleanmo.schema.utils :as schema-utils]
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
         (cond-> {:type                "text",
                  :name                input-name,
                  :required            input-required,
                  :autocomplete        "off",
                  :data-original-value (str value)}
           value (assoc :value value))]]]

      (str/includes? input-name "time-zone")
      (let [effective-value (or value time-zone)]
        [:div
         [:label.form-label
          {:for input-name} input-label]
         [:div.mt-2
          [:select.form-select
           {:name                input-name,
            :required            input-required,
            :autocomplete        "off",
            :data-original-value (str effective-value)}
           (for [zoneId (sort (ZoneId/getAvailableZoneIds))]
             [:option
              {:value    zoneId,
               :selected (= zoneId effective-value)}
              zoneId])]]])
      :else
      [:div
       [:label.form-label
        {:for input-name} input-label]
       [:div.mt-2
        [:textarea.form-textarea
         (cond-> {:name                input-name,
                  :rows                3,
                  :required            input-required,
                  :placeholder         "...",
                  :autocomplete        "off",
                  :data-original-value (str value)}
           value (assoc :value value))]]])))

(defmethod render :boolean
  [field _]
  (let [{:keys [input-name
                input-label
                value]}
        field
        ;; Default to false when value is nil OR missing
        default-value (if (contains? field :value) value false)]
    [:div.flex.items-center
     [:input.mr-2
      (cond-> {:type                "checkbox",
               :name                input-name,
               :autocomplete        "off",
               :data-original-value (str default-value)}
        default-value (assoc :checked "checked"))]
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
       (cond-> {:type                "number",
                :step                "any",
                :name                input-name,
                :required            input-required,
                :autocomplete        "off",
                :data-original-value (str value)}
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
       (cond-> {:type                "number",
                :step                "1",
                :name                input-name,
                :required            input-required,
                :autocomplete        "off",
                :data-original-value (str value)}
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
       (cond-> {:type                "number",
                :step                "0.001",
                :name                input-name,
                :required            input-required,
                :autocomplete        "off",
                :data-original-value (str value)}
         value (assoc :value value))]]]))

(defmethod render :local-date
  [field _]
  (let [{:keys [input-name
                input-label
                input-required
                value]}
        field
        ;; Format tick date to YYYY-MM-DD string for HTML date input
        formatted-date (when value
                         (if (string? value)
                           value
                           (str value)))]
    [:div
     [:label.form-label {:for input-name}
      input-label]
     [:div.mt-2
      [:input.form-input
       (cond-> {:type                "date",
                :name                input-name,
                :required            input-required,
                :data-original-value (str formatted-date)}
         formatted-date (assoc :value formatted-date))]]]))

(defmethod render :instant
  [field ctx]
  (let [{:keys [input-name
                input-label
                input-required
                value]}
        field
        ;; Determine timezone with proper fallback hierarchy:
        ;; 1. Query parameter timezone (for pre-population)
        ;; 2. Entity timezone (for editing existing entities)
        ;; 3. User's saved timezone setting
        ;; 4. Default fallback
        query-tz (get-in ctx [:params "timezone"])
        entity-tz (when-let [entity (:entity ctx)]
                    (or (get entity (keyword (str (:entity-str ctx) "/time-zone")))
                        (:time-zone entity)))
        user-tz (get-user-time-zone ctx)
        time-zone (or query-tz entity-tz user-tz "UTC")
        ;; Only default to now if it's a beginning timestamp or value is
        ;; already set
        formatted-time (cond
                         ;; Use existing value if provided (could be from query params or entity)
                         value
                         (if (string? value)
                           ;; Parse string instant and format for user's timezone
                           (format-date-time-local (t/instant value) time-zone)
                           ;; Already an instant
                           (format-date-time-local value time-zone))

                         ;; Set default now value only for beginning fields
                         (and (string? input-name)
                              (or (str/includes? input-name "beginning")
                                  (str/includes? input-name "timestamp")))
                         (format-date-time-local (t/in (t/now) (ZoneId/of time-zone)) time-zone)

                         ;; Otherwise, leave empty
                         :else
                         nil)]
    [:div
     [:label.form-label {:for input-name}
      input-label]
     [:div.mt-2
      [:input.form-input
       (cond-> {:type                "datetime-local",
                :name                input-name,
                :required            input-required,
                :data-original-value (str formatted-time)}
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
        schema-map (or (:schema-map ctx) schema-registry/schema)
        entity-schema (schema-utils/entity-schema schema-map (keyword related-entity-str))
        options (->> (all-for-user-query {:entity-type-str related-entity-str
                                          :schema         entity-schema}
                                         ctx)
                     (map (fn [e] {:id (id-key e), :label (label-key e)})))
        option-elems (into []
                           (concat
                            (when-not input-required
                              [[:option {:value "" :selected (nil? value)} ""]])
                            (for [{:keys [id label]} options]
                              [:option {:value id, :selected (= (str id) (str value))} label])))]
    [:div
     [:label.form-label {:for input-name}
      input-label]
     (into
      [:select.form-select
       (cond-> {:name                input-name
                :required            input-required
                :data-enhance        "choices"
                :data-placeholder    input-label
                :data-original-value (str value)}
         (not input-required)
         (assoc :data-allow-clear "true"))]
      option-elems)]))

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
        schema-map (or (:schema-map ctx) schema-registry/schema)
        entity-schema (schema-utils/entity-schema schema-map (keyword related-entity-str))
        options (->> (all-for-user-query {:entity-type-str related-entity-str
                                          :schema         entity-schema}
                                         ctx)
                     (map (fn [e] {:id (id-key e), :label (label-key e)})))
        value-set (when value (set (map str value)))
        ;; Sort values to ensure consistent ordering for comparison
        original-val-str (if (seq value)
                           (str/join "," (sort (map str value)))
                           "")
        option-elems (into []
                           (for [{:keys [id label]} options]
                             [:option
                              {:value    id
                               :selected (and value-set (contains? value-set (str id)))}
                              label]))]
    [:div
     [:label.form-label {:for input-name}
      input-label]
     (into
      [:select.form-select
       {:name                input-name
        :multiple            true
        :required            input-required
        :data-enhance        "choices"
        :data-remove-item    "true"
        :data-original-value original-val-str}]
      option-elems)]))

(defmethod render :enum
  [field _]
  (let [{:keys [enum-options
                input-name
                input-label
                input-required
                value]}
        field
        ;; Calculate effective default for data-original-value
        ;; If required and no value, browser selects first option
        default-val-str (if (some? value)
                          (if (keyword? value) (name value) (str value))
                          (if input-required
                            (name (first enum-options))
                            ""))]
    [:div
     [:label.form-label {:for input-name}
      input-label]
     [:div.mt-2
      (into
       [:select.form-select
        {:name                input-name,
         :required            input-required,
         :data-original-value default-val-str}
         ;; Add empty option for optional fields
        (when-not input-required
          [:option {:value "", :selected (nil? value)} "-- Select --"])]
       (for [opt enum-options]
         [:option
          {:value    (name opt),
           :selected (= (keyword opt) value)}
          (name opt)]))]]))

(defmethod render :boolean-or-enum
  [field _]
  (let [{:keys [enum-options
                input-name
                input-label
                input-required
                value]}
        field
        ;; Default to :no when value is nil OR missing
        default-value (if (contains? field :value) value :no)
        options (concat [:yes :no] enum-options)
        original-val-str (if (keyword? default-value)
                           (name default-value)
                           (str default-value))]
    [:div
     [:label.form-label {:for input-name}
      input-label]
     [:div.mt-2
      (into
       [:select.form-select
        {:name                input-name,
         :required            input-required,
         :data-original-value original-val-str}]
       (for [opt options]
         [:option
          (cond-> {:value (name opt)}
            (cond
              (boolean? default-value) (= (if default-value :yes :no) opt)
              (keyword? default-value) (= default-value opt)
              :else false)
            (assoc :selected true))
          (name opt)]))]]))

(defmethod render :default
  [field _]
  [:div "Unsupported field type: " (pr-str field)])
