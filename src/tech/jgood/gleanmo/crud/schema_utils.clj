(ns tech.jgood.gleanmo.crud.schema-utils
  (:require
   [clojure.string :as str]))

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

(defn ns-keyword->input-name [k]
  (-> k str (str/replace ":" "")))

(defn add-input-name-label [{:keys [field-key opts] :as field}]
  (let [n (ns-keyword->input-name field-key)
        l (-> field-key name (str/split #"-")
              (->> (map str/capitalize))
              (->> (str/join " ")))
        required (not (:optional opts))]
    (merge field {:input-name   n
                  :input-label  l
                  :input-required required})))

(defn prepare-field [field]
  (-> field
      parse-field
      add-descriptors
      add-input-name-label))

(defn should-remove-system-or-user-field?
  "Predicate to identify fields that should be excluded from forms:
   - System fields (:xt/id)
   - User ID fields (:user/id)
   - Schema namespace fields (tech.jgood.gleanmo.schema/*)
   - Airtable namespace fields (airtable/*)
   - Fields with {:hide true} option"
  [{:keys [field-key opts]}]
  (let [n (namespace field-key)]
    (boolean
     (or
      ;; System and namespace-based exclusions
      (= :xt/id field-key)
      (= :user/id field-key)
      (= "tech.jgood.gleanmo.schema" n)
      (= "tech.jgood.gleanmo.schema.meta" n)
      (= "airtable" n)
      ;; Hide option exclusion
      (:hide opts)))))

(defn extract-relationship-fields
  "Extracts relationship fields from a schema.
   Returns a sequence of processed fields that are relationships."
  [schema & {:keys [remove-system-fields] :or {remove-system-fields false}}]
  (cond->> schema
    :always              extract-schema-fields
    :always              (map prepare-field)
    :always              (filter (fn [{:keys [input-type]}]
                                   (or (= input-type :single-relationship)
                                       (= input-type :many-relationship))))
    remove-system-fields (remove should-remove-system-or-user-field?)))

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
