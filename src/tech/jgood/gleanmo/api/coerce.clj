(ns tech.jgood.gleanmo.api.coerce
  "JSON in, JSON out, driven by each entity's malli schema.

   Several registry types are `:fn` predicates (`:instant`, `:local-date`), so
   malli's JSON transformer cannot decode them; the conversion here reads the
   declared field type instead. Values that do not fit their field come back as
   an error message rather than an exception, so a batch can report every bad
   field at once."
  (:require
   [clojure.string :as str]
   [tech.jgood.gleanmo.schema :as schema-registry]
   [tech.jgood.gleanmo.schema.meta :as sm]
   [tech.jgood.gleanmo.schema.utils :as schema-utils])
  (:import
   [java.time Instant LocalDate OffsetDateTime]))

;; ---------------------------------------------------------------------------
;; Field metadata
;; ---------------------------------------------------------------------------

(defn fields
  "`{field-key {:type t :opts opts}}` for every field of `entity-type`."
  [entity-type]
  (into {}
        (map (fn [entry]
               (let [{:keys [field-key opts type]} (schema-utils/parse-field
                                                    entry)]
                 [field-key {:type type, :opts opts}])))
        (schema-utils/extract-schema-fields
         (get schema-registry/schema entity-type))))

(defn own-field?
  "Whether `field-key` is one of the entity's own attributes (`task/*` for a
   task), as opposed to identity, ownership, meta, or lineage keys."
  [entity-type field-key]
  (= (name entity-type) (namespace field-key)))

(defn writable-field?
  "Whether an API client may set `field-key`. Hidden fields are derived or
   deprecated, so the write path owns them, exactly as it does for forms."
  [entity-type field-key]
  (boolean
   (when-let [{:keys [opts]} (get (fields entity-type) field-key)]
     (and (own-field? entity-type field-key)
          (not (:hide opts))))))

(defn relation-target
  "The entity type a relation field points at (`:project` for a field of type
   `:project/id` or `[:set :project/id]`), or nil."
  [field-type]
  (let [t (if (and (vector? field-type) (#{:set :maybe} (first field-type)))
            (second field-type)
            field-type)]
    (when (and (keyword? t) (= "id" (name t)) (namespace t))
      (keyword (namespace t)))))

;; ---------------------------------------------------------------------------
;; JSON -> stored values
;; ---------------------------------------------------------------------------

(defn- bad
  [expected v]
  {:error (str "expected " expected ", got " (pr-str v))})

(defn- parse-instant
  [s]
  (try (Instant/parse s)
       (catch Exception _
         (.toInstant (OffsetDateTime/parse s)))))

(defn- enum-members
  [t]
  (remove map? (rest t)))

(defn- ->keyword
  [v]
  (cond
    (keyword? v) v
    (string? v)  (keyword (str/replace v #"^:" ""))
    :else        nil))

(defn coerce-value
  "`{:value stored}` for JSON value `v` of a field declared as `t`, or
   `{:error message}`."
  [t v]
  (try
    (cond
      (vector? t)
      (case (first t)
        :maybe (if (nil? v) {:value nil} (coerce-value (second t) v))
        :enum  (let [k (->keyword v)]
                 (if (and k (some #{k} (enum-members t)))
                   {:value k}
                   (bad (str "one of " (str/join ", " (map name (enum-members t))))
                        v)))
        :set   (if (or (sequential? v) (set? v))
                 (let [results (map #(coerce-value (second t) %) v)]
                   (or (first (filter :error results))
                       {:value (into #{} (map :value) results)}))
                 (bad "an array" v))
        :or    (or (first (remove :error (map #(coerce-value % v) (rest t))))
                   (bad (pr-str t) v))
        (bad (str "a supported type (" (pr-str t) " is not)") v))

      (relation-target t) (if-let [u (and (string? v) (parse-uuid v))]
                            {:value u}
                            (bad "a uuid string" v))

      :else
      (case t
        :string          (if (string? v) {:value v} (bad "a string" v))
        :boolean         (if (boolean? v) {:value v} (bad "true or false" v))
        (:int :positive-int :nonnegative-int)
        (if (and (number? v) (== v (long v)))
          {:value (long v)}
          (bad "an integer" v))
        :number          (if (number? v) {:value v} (bad "a number" v))
        (:float :double) (if (number? v) {:value (double v)} (bad "a number" v))
        :instant         (if (string? v)
                           {:value (parse-instant v)}
                           (bad "an ISO-8601 instant string" v))
        :local-date      (if (string? v)
                           {:value (LocalDate/parse v)}
                           (bad "an ISO-8601 date string (YYYY-MM-DD)" v))
        :keyword         (if-let [k (->keyword v)] {:value k} (bad "a string" v))
        :uuid            (if-let [u (and (string? v) (parse-uuid v))]
                           {:value u}
                           (bad "a uuid string" v))
        (bad (str "a supported type (" (pr-str t) " is not)") v)))
    (catch Exception _
      (bad (pr-str t) v))))

(defn coerce-changes
  "Convert a client `set` map for `entity-type` into stored values.
   Returns `{:data data :errors {field-key message}}`. A JSON null means
   \"remove this field\" and becomes `:db/dissoc`; on a create, the caller
   drops those. Fields the client may not write are errors, not ignored."
  [entity-type changes]
  (let [specs (fields entity-type)]
    (reduce-kv
     (fn [acc k v]
       (let [field-key (keyword (if (keyword? k) (subs (str k) 1) (str k)))]
         (cond
           (not (writable-field? entity-type field-key))
           (assoc-in acc [:errors field-key]
                     (if (contains? specs field-key)
                       "not writable through the API"
                       (str "not a field of " (name entity-type))))

           (nil? v)
           (assoc-in acc [:data field-key] :db/dissoc)

           :else
           (let [{:keys [value error]} (coerce-value (:type (specs field-key))
                                                     v)]
             (if error
               (assoc-in acc [:errors field-key] error)
               (assoc-in acc [:data field-key] value))))))
     {:data {}, :errors {}}
     (or changes {}))))

;; ---------------------------------------------------------------------------
;; Stored values -> JSON
;; ---------------------------------------------------------------------------

(defn kw->str
  "`:task/state` -> \"task/state\", `:now` -> \"now\"."
  [k]
  (subs (str k) 1))

(defn ->json-value
  [v]
  (cond
    (nil? v)        nil
    (keyword? v)    (kw->str v)
    (set? v)        (mapv ->json-value (sort-by str v))
    (sequential? v) (mapv ->json-value v)
    (map? v)        (into {} (map (fn [[k x]] [(->json-value k) (->json-value x)])) v)
    (or (string? v) (number? v) (boolean? v)) v
    :else           (str v)))

(def ^:private dropped-keys
  #{:user/id ::sm/deleted-at})

(defn doc->json
  "An entity document as the API returns it: `id`, `type`, `created-at`, then
   the entity's own attributes under their full names (`task/label`)."
  [doc]
  (into (sorted-map)
        (keep (fn [[k v]]
                (cond
                  (= :xt/id k)             ["id" (str v)]
                  (= ::sm/type k)          ["type" (name v)]
                  (= ::sm/created-at k)    ["created-at" (str v)]
                  (dropped-keys k)         nil
                  ;; legacy meta (`tech.jgood.gleanmo.schema/*`)
                  (= "tech.jgood.gleanmo.schema" (namespace k)) nil
                  :else                    [(kw->str k) (->json-value v)])))
        doc))

(defn field->json
  "Describe one schema field for the `types` endpoint."
  [entity-type [field-key {:keys [type opts]}]]
  (let [base   (if (and (vector? type) (= :maybe (first type))) (second type) type)
        target (relation-target type)]
    (cond-> {:name     (kw->str field-key)
             :type     (cond
                         target                                    (if (and (vector? base) (= :set (first base)))
                                                                     "relation-set"
                                                                     "relation")
                         (and (vector? base) (= :enum (first base))) "enum"
                         (and (vector? base) (= :set (first base)))  "enum-set"
                         (vector? base)                              (pr-str base)
                         :else                                       (name base))
             :required (not (:optional opts))
             :writable (writable-field? entity-type field-key)}
      target
      (assoc :relation (name target))

      (and (vector? base) (= :enum (first base)))
      (assoc :values (mapv name (enum-members base)))

      (and (vector? base) (= :set (first base)) (vector? (second base)))
      (assoc :values (mapv name (enum-members (second base))))

      (:crud/scale opts)
      (assoc :scale (mapv (fn [[n label]] {:value n, :label label})
                          (:crud/scale opts)))

      (:crud/label opts)
      (assoc :label (:crud/label opts))

      (:crud/description opts)
      (assoc :description (:crud/description opts)))))

(defn type->json
  "Describe an entity type's own fields for the `types` endpoint."
  [entity-type]
  (->> (fields entity-type)
       (filter (fn [[k _]] (own-field? entity-type k)))
       (sort-by (fn [[k {:keys [opts]}]]
                  [(or (:crud/priority opts) 1000) (str k)]))
       (mapv #(field->json entity-type %))))
