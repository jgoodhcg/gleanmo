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
        field
        ;; Default to false when value is nil OR missing
        default-value (if (contains? field :value) value false)]
    [:div.flex.items-center
     [:input.mr-2
      (cond-> {:type         "checkbox",
               :name         input-name,
               :autocomplete "off"}
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
        schema-map (or (:schema-map ctx) schema-registry/schema)
        entity-schema (schema-utils/entity-schema schema-map (keyword related-entity-str))
        options (->> (all-for-user-query {:entity-type-str related-entity-str
                                          :schema         entity-schema}
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
        schema-map (or (:schema-map ctx) schema-registry/schema)
        entity-schema (schema-utils/entity-schema schema-map (keyword related-entity-str))
        options (->> (all-for-user-query {:entity-type-str related-entity-str
                                          :schema         entity-schema}
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
      (into
        [:select.form-select
         {:name     input-name,
          :required input-required}
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
        options (concat [:yes :no] enum-options)]
    [:div
     [:label.form-label {:for input-name}
      input-label]
     [:div.mt-2
      (into
        [:select.form-select
         {:name     input-name,
          :required input-required}]
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
