(ns tech.jgood.gleanmo.crud.forms.inputs
  (:require
   [cheshire.core :as cheshire]
   [clojure.string :as str]
   [tech.jgood.gleanmo.app.shared :refer
    [format-date-time-local
     get-user-time-zone]]
   [tech.jgood.gleanmo.db.queries :as queries :refer [all-for-user-query]]
   [tech.jgood.gleanmo.db.relation-labels :as rel-labels]
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

(defn field-dom-id
  "DOM-safe id derived from a field's input name, which is a namespaced
   keyword string like \"exercise-line/exercise-id\" and so cannot be used as
   an id in a CSS selector without escaping."
  [prefix input-name]
  (str prefix "-" (str/replace (str input-name) #"[^A-Za-z0-9_-]" "-")))

(defn- suggested-values
  "Values the user has already used for this field, for its datalist."
  [{:keys [field-key]} {:keys [biff/db session]}]
  (when (and db field-key)
    (queries/distinct-field-values db (:uid session)
                                   (keyword (namespace field-key))
                                   field-key)))

(defn- number-eq
  "Numeric equality that ignores long/double representation — a stored 5.0
   matches a scale's 5. False for non-numbers, so a legacy keyword value can
   never match a scale point."
  [a b]
  (and (number? a) (number? b) (== a b)))

(defn- scale-options
  "Option tuples [value-string display-text selected?] for a `:crud/scale`
   field, in scale order.

   A numeric value that isn't on the scale (older record, seed data) gets its
   own leading option so opening and saving never silently rewrites it."
  [scale value]
  (let [on-scale? (some (fn [[n _]] (number-eq n value)) scale)
        base      (for [[n label] scale]
                    [(str n) (str n " — " label) (number-eq n value)])]
    (if (and (number? value) (not on-scale?))
      (vec (cons [(str value) (str value " — (off scale)") true] base))
      (vec base))))

(defmethod render :string
  [field ctx]
  (let [{:keys [input-name
                input-label
                input-required
                opts
                value]}
        field
        time-zone (get-user-time-zone ctx)]
    (cond
      ;; Open vocabulary: the schema keeps `:string` so any value is allowed,
      ;; but the field offers what has actually been used before. A native
      ;; datalist rather than Choices.js precisely because this is *not* a
      ;; select — the user must be able to type a value that doesn't exist
      ;; yet (a new gym, a new wall) without the widget fighting them.
      (:crud/suggest-existing opts)
      (let [list-id     (field-dom-id "suggest" input-name)
            suggestions (suggested-values field ctx)]
        [:div
         [:label.form-label {:for input-name} input-label]
         [:div.mt-2
          [:input.form-input
           (cond-> {:type                "text"
                    :id                  input-name
                    :name                input-name
                    :required            input-required
                    :autocomplete        "off"
                    :list                list-id
                    :data-original-value (str value)}
             value (assoc :value value))]
          (when (seq suggestions)
            (into [:datalist {:id list-id}]
                  (for [v suggestions] [:option {:value v}])))]])

      (str/includes? input-name "label")
      [:div
       [:label.form-label
        {:for input-name} input-label]
       [:div.mt-2
        [:input.form-input
         (cond-> {:type                "text",
                  :id                  input-name,
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
           {:id                  input-name,
            :name                input-name,
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
         (cond-> {:id                  input-name,
                  :name                input-name,
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
               :id                  input-name,
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
                opts
                value]}
        field
        scale (:crud/scale opts)]
    (if (seq scale)
      ;; Labeled scale: the number is what gets stored and analyzed, the label
      ;; is the cue for picking it. A select rather than a free number input
      ;; because these are the only values worth recording — see the schema
      ;; conventions in AGENTS.md for when a rating earns a number at all.
      (let [options  (scale-options scale value)
            selected (some (fn [[v _ selected?]] (when selected? v)) options)
            ;; A field converted from an enum in place (allowed only before an
            ;; entity's data is ported — see AGENTS.md) can still meet
            ;; documents holding the old keyword. Those submit blank, which the
            ;; handler skips for an optional field, so the stored value
            ;; survives until a scale point is actually chosen.
            legacy   (when (and (some? value) (not (number? value)))
                       (if (keyword? value) (name value) (str value)))]
        [:div
         [:label.form-label {:for input-name}
          input-label]
         [:div.mt-2
          (into
           [:select.form-select
            {:id                  input-name,
             :name                input-name,
             :required            input-required,
             :autocomplete        "off",
             :data-original-value (or selected "")}
            (when (or (not input-required) legacy)
              [:option {:value "", :selected (nil? selected)}
               (if legacy
                 (str "-- " legacy " (legacy — pick a value) --")
                 "-- Select --")])]
           (for [[v text selected?] options]
             [:option {:value v, :selected selected?} text]))]])
      [:div
       [:label.form-label {:for input-name}
        input-label]
       [:div.mt-2
        [:input.form-input
         (cond-> {:type                "number",
                  :step                "any",
                  :id                  input-name,
                  :name                input-name,
                  :required            input-required,
                  :autocomplete        "off",
                  :data-original-value (str value)}
           value (assoc :value value))]]])))

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
                :id                  input-name,
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
                :id                  input-name,
                :name                input-name,
                :required            input-required,
                :autocomplete        "off",
                :data-original-value (str value)}
         value (assoc :value value))]]]))

(defmethod render :double
  [field ctx]
  ((get-method render :float) field ctx))

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
                :id                  input-name,
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
                :id                  input-name,
                :name                input-name,
                :required            input-required,
                :data-original-value (str formatted-time)}
         formatted-time (assoc :value formatted-time))]]]))

(defn- relation-options
  "Fetch and label the user's entities of related-entity-str for a select."
  [related-entity-str ctx]
  (let [schema-map (or (:schema-map ctx) schema-registry/schema)
        entity-schema (schema-utils/entity-schema schema-map (keyword related-entity-str))
        time-zone (get-user-time-zone ctx)]
    (->> (all-for-user-query {:entity-type-str related-entity-str
                              :schema         entity-schema}
                             ctx)
         (map (fn [e]
                {:id    (:xt/id e)
                 :label (rel-labels/entity->label e (:xt/id e) time-zone)})))))

(defn- inline-create-affordance
  "\"+ New <entity>\" button that swaps a mini-form in below the select,
   replacing the old bounce-link that lost form state. The mini-form is
   fetched into the mount div; on success the whole field container is
   re-rendered with the new entity selected. See
   roadmap/inline-entity-creation.md."
  [field]
  (let [{:keys [opts related-entity-str input-name]} field]
    (when (:crud/inline-create opts)
      (let [mount-id (field-dom-id "inline-mount" input-name)]
        [:div.mt-1
         [:button.link.text-sm.cursor-pointer
          {:type       "button"
           :hx-get     (str "/app/crud/inline/" related-entity-str "/new")
           :hx-vals    (cheshire/generate-string
                        {:parent (namespace (keyword input-name))
                         :field  input-name})
           :hx-target  (str "#" mount-id)
           :hx-swap    "innerHTML"
           ;; hx-select is inheritable, and the enclosing CRUD form sets it to
           ;; the form's own id (crud/forms.clj). Without unsetting it htmx
           ;; would look for that id inside this fragment, find nothing, and
           ;; swap in an empty string.
           :hx-select  "unset"}
          (str "+ New " (str/replace related-entity-str "-" " "))]
         [:div {:id mount-id, :data-inline-mount true}]]))))

(defn single-relationship-body
  "Label + select + inline-create affordance for a single-relationship field.

   Split out from the `render` method so the inline-create success response can
   re-render exactly the same markup with the new entity selected, rather than
   hand-rolling a second version of it that could drift."
  [field ctx]
  (let [{:keys [input-name
                input-label
                input-required
                related-entity-str
                value]}
        field
        options (relation-options related-entity-str ctx)
        option-elems (into []
                           (concat
                            (when-not input-required
                              [[:option {:value "" :selected (nil? value)} ""]])
                            (for [{:keys [id label]} options]
                              [:option {:value id, :selected (= (str id) (str value))} label])))]
    [:<>
     ;; Custom screens (e.g. the workout picker) supply their own heading and
     ;; pass no input-label, so don't render an empty one for them.
     (when (seq (str input-label))
       [:label.form-label {:for input-name} input-label])
     (into
      [:select
       (cond-> {:id                  input-name
                :name                input-name
                :required            input-required
                :class               "form-select"
                :data-enhance        "choices"
                :data-placeholder    (or input-label "Select…")
                :data-original-value (str value)}
         (not input-required)
         (assoc :data-allow-clear "true"))]
      option-elems)
     (inline-create-affordance field)]))

(defn inline-create-trigger
  "Standalone \"+ New <entity>\" button and mini-form mount, for a surface that
   has no select to refill — an empty state, say. With no `field-name` the
   create handler has nothing to swap into and answers `HX-Refresh`, which is
   what an empty screen wants anyway: reload and show the real form."
  [related-entity-str]
  (inline-create-affordance {:opts               {:crud/inline-create true}
                             :related-entity-str related-entity-str
                             :input-name         ""}))

(defn inline-create-select
  "Select + inline-create trigger for a custom screen whose picker is not a
   generated CRUD relationship field (the workout exercise picker, say).

   `field-name` is the plain `name` the surrounding form expects. Because it
   carries no namespace, the POST resolves no CRUD parent field and the
   handler falls back to re-rendering this same select."
  [{:keys [field-name related-entity-str required? value]} ctx]
  [:div {:id (field-dom-id "rel-field" field-name)}
   (single-relationship-body
    {:input-name         field-name
     :input-required     (boolean required?)
     :related-entity-str related-entity-str
     :value              value
     :opts               {:crud/inline-create true}}
    ctx)])

(defmethod render :single-relationship
  [field ctx]
  ;; The container id is the inline-create swap target. Swapping its
  ;; *innerHTML* (rather than the select's outerHTML) matters: main.js
  ;; re-initializes Choices via `htmx:afterSettle` using
  ;; `root.querySelectorAll`, which does not match the root element itself.
  [:div {:id (field-dom-id "rel-field" (:input-name field))}
   (single-relationship-body field ctx)])

(defmethod render :many-relationship
  [field ctx]
  (let [{:keys [input-name
                input-label
                input-required
                related-entity-str
                value]}
        field
        options (relation-options related-entity-str ctx)
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
       {:id                  input-name
        :name                input-name
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
        {:id                  input-name,
         :name                input-name,
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

(defmethod render :set-enum
  [field _]
  (let [{:keys [enum-options
                input-name
                input-label
                input-required
                value]}
        field
        value-set (when value (set (map #(if (keyword? %) (name %) (str %)) value)))
        original-val-str (if (seq value)
                           (str/join "," (sort (map #(if (keyword? %) (name %) (str %)) value)))
                           "")]
    [:div
     [:label.form-label {:for input-name}
      input-label]
     [:div.mt-2
      (into
       [:select.form-select
        {:id                  input-name
         :name                input-name
         :multiple            true
         :required            input-required
         :data-enhance        "choices"
         :data-remove-item    "true"
         :data-original-value original-val-str}]
       (for [opt enum-options]
         [:option
          {:value    (name opt)
           :selected (and value-set (contains? value-set (name opt)))}
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
        {:id                  input-name,
         :name                input-name,
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
