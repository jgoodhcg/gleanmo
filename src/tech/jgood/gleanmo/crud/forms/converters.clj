(ns tech.jgood.gleanmo.crud.forms.converters
  (:require
   [tech.jgood.gleanmo.app.shared :refer [get-user-time-zone str->instant!]])
  (:import
   [java.time ZoneId]))

(defmulti convert-field-value
  "Convert a form field value to the appropriate type for the schema.
   Dispatches on the field type."
  (fn [type _value _ctx] type))

(defmethod convert-field-value :string [_ value _] value)

(defmethod convert-field-value :int
  [_ value _]
  (try (Integer/parseInt value)
       (catch Exception e
         (throw (ex-info (str "Could not convert '" value
                              "' to int: " (.getMessage e))
                         {:value value, :type :int})))))

(defmethod convert-field-value :float
  [_ value _]
  (try (Float/parseFloat value)
       (catch Exception e
         (throw (ex-info (str "Could not convert '" value
                              "' to float: "        (.getMessage e))
                         {:value value, :type :float})))))

(defmethod convert-field-value :number
  [_ value _]
  (try (Double/parseDouble value)
       (catch Exception e
         (throw (ex-info (str "Could not convert '" value
                              "' to number: "       (.getMessage e))
                         {:value value, :type :number})))))

(defmethod convert-field-value :boolean
  [_ value _]
  ;; In HTML forms, checkboxes only send a value when checked
  ;; When a checkbox is unchecked, the field is completely missing from the form data
  ;; We could handle this with hidden fields, but it's more straightforward to handle it here
  (if (or (= value "on") (= value "true") (= value true))
    true
    false))

(defmethod convert-field-value :local-date
  [_ value _]
  (when (not-empty value)
    (try (java.time.LocalDate/parse value)
         (catch Exception e
           (throw (ex-info (str "Could not parse date '" value "': " (.getMessage e))
                           {:value value, :type :local-date}))))))

(defmethod convert-field-value :instant
  [_ value ctx]
  (when (not-empty value)
    (let [time-zone (get-user-time-zone ctx)
          zone-id   (ZoneId/of (or time-zone "UTC"))]
      (try (str->instant! value zone-id)
           (catch IllegalArgumentException e
             (throw (ex-info (.getMessage e)
                             {:value value, :type :instant})))))))

(defmethod convert-field-value :single-relationship
  [_ value _]
  (try (java.util.UUID/fromString value)
       (catch IllegalArgumentException e
         (throw (ex-info (str "Could not convert '" value
                              "' to UUID: "         (.getMessage e))
                         {:value value, :type :single-relationship})))))

(defmethod convert-field-value :many-relationship
  [_ values _]
  (cond (string? values) (when (not-empty values)
                           #{(try (java.util.UUID/fromString values)
                                  (catch IllegalArgumentException e
                                    (throw (ex-info
                                            (str "Could not convert '" values
                                                 "' to UUID: " (.getMessage e))
                                            {:value values,
                                             :type  :many-relationship}))))})
        :else            (into #{}
                               (map (fn [v]
                                      (try (java.util.UUID/fromString v)
                                           (catch IllegalArgumentException e
                                             (throw
                                              (ex-info
                                               (str "Could not convert '" v
                                                    "' to UUID: " (.getMessage
                                                                   e))
                                               {:value v,
                                                :type :many-relationship})))))
                                    values))))

(defmethod convert-field-value :enum
  [_ value _]
  (when (not-empty value) (keyword value)))

(defmethod convert-field-value :boolean-or-enum
  [_ value _]
  (when (not-empty value)
    (case value
      "yes" true
      "no" false
      (keyword value))))

(defmethod convert-field-value :default
  [type value _]
  (throw (ex-info (str "Unknown field type for conversion: " type)
                  {:value value, :type type})))
