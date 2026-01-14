(ns tech.jgood.gleanmo.schema.utils
  (:require
   [clojure.string :as str]
   [tech.jgood.gleanmo.schema :as schema-registry]))

(defn entity-schema
  "Fetch the Malli schema vector for the given entity key or throw."
  ([entity-key]
   (entity-schema schema-registry/schema entity-key))
  ([schema-map entity-key]
   (if-let [result (get schema-map entity-key)]
     result
     (throw (ex-info "Unknown entity schema" {:entity-key entity-key})))))

(defn schema-options
  "Return the options map (the second element) if present on a schema vector."
  [entity-schema]
  (let [maybe-opts (second entity-schema)]
    (when (map? maybe-opts)
      maybe-opts)))

(defn extract-schema-fields
  "Extracts fields from a schema, skipping the schema identifier and options if present."
  [entity-schema]
  (let [has-opts (map? (second entity-schema))]
    (if has-opts
      (drop 2 entity-schema)
      (rest entity-schema))))

(defn schema-field
  "Find a specific field entry by key inside the schema vector."
  [entity-schema field-key]
  (some (fn [[k & _ :as entry]]
          (when (= k field-key) entry))
        (extract-schema-fields entity-schema)))

(defn parse-field
  "Extract field key, options, and type from a schema entry."
  [[key-or-opts & _ :as entry]]
  (let [has-opts (map? (second entry))
        k        (if has-opts key-or-opts (first entry))
        opts     (if has-opts (second entry) {})
        type     (if has-opts (nth entry 2) (second entry))]
    {:field-key k,
     :opts      opts,
     :type      type}))

(defn field-type
  "Return the declared type keyword/vector for a schema field entry."
  [field-entry]
  (let [[_ maybe-opts maybe-type] field-entry]
    (if (map? maybe-opts)
      maybe-type
      maybe-opts)))

(defn determine-input-type
   "Determines the input type based on the field type definition.
    Handles enum fields, relationships, or-types, and primitive types."
  [type]
  (cond
    (and (vector? type) (= :maybe (first type)))
    (determine-input-type (second type))

    (and (vector? type) (= :enum (first type)))
    {:input-type         :enum,
     :enum-options       (vec (rest type)),
     :related-entity-str nil}

    (and (vector? type) (= :or (first type)))
    (let [options     (rest type)
          has-boolean (some #(= :boolean %) options)
          enum-option (some #(when (and (vector? %) (= :enum (first %))) %)
                            options)]
      (if (and has-boolean enum-option)
        {:input-type         :boolean-or-enum,
         :enum-options       (vec (rest enum-option)),
         :related-entity-str nil}
        {:input-type         (first (remove #(and (vector? %)
                                                  (= :enum (first %)))
                                            options)),
         :enum-options       nil,
         :related-entity-str nil}))

    (and (vector? type)
         (= :set (first type))
         (let [elem (second type)]
           (and (keyword? elem) (= "id" (name elem)))))
    {:input-type         :many-relationship,
     :enum-options       nil,
     :related-entity-str (-> type
                             second
                             namespace)}

    (and (keyword? type) (= "id" (name type)))
    {:input-type         :single-relationship,
     :enum-options       nil,
     :related-entity-str (-> type
                             namespace)}

    :else
    {:input-type         type,
     :enum-options       nil,
     :related-entity-str nil}))

(defn relationship-target-entity
  "Given a schema field entry for a relationship, return the target entity keyword."
  [field-entry]
  (let [t (field-type field-entry)]
    (when (and (keyword? t)
               (= "id" (name t)))
      (some-> (namespace t)
              keyword))))

(defn add-descriptors
  "Attach :input-type metadata to a parsed field."
  [{:keys [field-key type], :as field}]
  (let [type-info (determine-input-type type)]
    (merge field
           {:field-key field-key}
           type-info)))

(defn ns-keyword->input-name
  [k]
  (-> k
      str
      (str/replace ":" "")))

(defn add-input-name-label
  [{:keys [field-key opts], :as field}]
  (let [n        (ns-keyword->input-name field-key)
         l        (or
                    ;; Check for explicit crud/label override first
                    (:crud/label opts)
                    ;; Fall back to existing logic
                    (cond
                      (= field-key :meditation-log/type-id) "Meditation Type"
                      (and (str/ends-with? (name field-key) "/id")
                           (not= (name field-key) "id"))
                      (-> (namespace field-key)
                          (str/split #"-")
                          (->> (map str/capitalize))
                          (->> (str/join " ")))
                      :else
                      (-> field-key
                          name
                          (str/split #"-")
                          (->> (map str/capitalize))
                          (->> (str/join " ")))))
         required (not (:optional opts))]
    (merge field
           {:input-name     n,
            :input-label    l,
            :input-required required})))

(defn prepare-field
  [field]
  (-> field
      parse-field
      add-descriptors
      add-input-name-label))

(defn should-remove-system-or-user-field?
  "Predicate to identify fields that should be excluded from forms."
  [{:keys [field-key opts]}]
  (let [n (namespace field-key)
        field-name (name field-key)]
    (boolean
     (or (= :xt/id field-key)
         (= :user/id field-key)
         (= "tech.jgood.gleanmo.schema" n)
         (= "tech.jgood.gleanmo.schema.meta" n)
         (= "airtable" n)
         (.startsWith field-name "airtable-")
         (:hide opts)))))

(defn extract-relationship-fields
  "Extracts relationship fields from a schema."
  [entity-schema &
   {:keys [remove-system-fields], :or {remove-system-fields false}}]
  (cond->> entity-schema
    :always extract-schema-fields
    :always (map prepare-field)
    :always (filter (fn [{:keys [input-type]}]
                      (or (= input-type :single-relationship)
                          (= input-type :many-relationship))))
    remove-system-fields (remove should-remove-system-or-user-field?)))

(defn get-field-info
  "Returns a map with :type and :opts for a given field key from a schema."
  [entity-schema field-key]
  (some (fn [[k & rest]]
          (when (= k field-key)
            (if (map? (first rest))
              {:type (second rest), :opts (first rest)}
              {:type (first rest), :opts {}})))
        (extract-schema-fields entity-schema)))

(defn entity-base-name
  "Given an entity string like \"project-log\", return the base noun (\"project\")."
  [entity-str]
  (first (str/split entity-str #"-")))

(defn entity-field-key
  [entity-str suffix]
  (keyword entity-str suffix))

(defn entity-attr-key
  "Build a namespaced keyword using the entity key's name."
  [entity-key suffix]
  (keyword (name entity-key) suffix))

(defn ensure-interval-fields
  "Ensure schema contains beginning/end instants for the entity."
  [{:keys [entity-schema entity-str]}]
  (let [beginning-field (schema-field entity-schema
                                      (entity-field-key entity-str "beginning"))
        end-field       (schema-field entity-schema
                                      (entity-field-key entity-str "end"))]
    (when-not beginning-field
      (throw (ex-info "Timer entity missing :beginning field"
                      {:entity-str     entity-str,
                       :expected-field (entity-field-key entity-str
                                                         "beginning")})))
    (when-not end-field
      (throw (ex-info "Timer entity missing :end field"
                      {:entity-str     entity-str,
                       :expected-field (entity-field-key entity-str "end")})))
    {:beginning-field beginning-field,
     :end-field       end-field}))

(defn primary-rel-field
  "Read :timer/primary-rel off the schema, without yet applying fallbacks."
  [entity-schema]
  (let [opts (schema-options entity-schema)]
    (:timer/primary-rel opts)))


