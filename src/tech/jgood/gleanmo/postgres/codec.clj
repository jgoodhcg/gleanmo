(ns tech.jgood.gleanmo.postgres.codec
  "Helpers for translating namespaced entity maps to JSON-friendly documents and back.

  The encoding preserves Clojure-specific data types (keywords, sets, UUIDs, instants,
  etc.) via lightweight type tags so we can round-trip without losing information when
  storing data in Postgres JSONB columns."
  (:require
   [clojure.string :as str])
  (:import
   [java.time Instant LocalDate LocalDateTime OffsetDateTime OffsetTime ZonedDateTime LocalTime]
   [java.util UUID]))

(def ^:private type-key "__gleanmo$type")
(def ^:private value-key "__gleanmo$value")

(def ^:private temporal-handlers
  [{:class Instant
    :tag   "instant"
    :parse #(Instant/parse %)}
   {:class LocalDate
    :tag   "local-date"
    :parse #(LocalDate/parse %)}
   {:class LocalDateTime
    :tag   "local-date-time"
    :parse #(LocalDateTime/parse %)}
   {:class OffsetDateTime
    :tag   "offset-date-time"
    :parse #(OffsetDateTime/parse %)}
   {:class OffsetTime
    :tag   "offset-time"
    :parse #(OffsetTime/parse %)}
   {:class ZonedDateTime
    :tag   "zoned-date-time"
    :parse #(ZonedDateTime/parse %)}
   {:class LocalTime
    :tag   "local-time"
    :parse #(LocalTime/parse %)}])

(declare encode-value)
(declare decode-value)

(defn- keyword->string [k]
  (let [ns (namespace k)
        n  (name k)]
    (if ns
      (str ns "/" n)
      n)))

(defn- string->keyword [s]
  (let [[ns name] (str/split s #"/" 2)]
    (if name
      (if (str/blank? ns)
        (keyword name)
        (keyword ns name))
      (keyword ns))))

(defn- wrap-value [tag value]
  {type-key tag
   value-key value})

(defn- find-temporal-tag [value]
  (some (fn [{:keys [class tag]}]
          (when (instance? class value)
            tag))
        temporal-handlers))

(defn- parse-tagged-value [tag value]
  (case tag
    "keyword" (string->keyword value)
    "uuid"    (UUID/fromString value)
    "set"     (into #{} (map decode-value) value)
    (or (some (fn [{handler-tag :tag parse :parse}]
                (when (= handler-tag tag)
                  (parse value)))
              temporal-handlers)
        (throw (ex-info "Unsupported tagged value."
                        {:tag tag
                         :value value})))))

(defn- encode-map [m]
  (into {}
        (map (fn [[k v]]
               (let [encoded-key (cond
                                   (keyword? k) (keyword->string k)
                                   (string? k) k
                                   :else (throw (ex-info "Unsupported map key type for JSON encoding."
                                                         {:key k :class (class k)})))]
                 [encoded-key (encode-value v)])))
        m))

(defn- encode-seq [coll]
  (mapv encode-value coll))

(defn encode-value [value]
  (cond
    (map? value)     (encode-map value)
    (vector? value)  (encode-seq value)
    (sequential? value) (encode-seq value)
    (set? value)     (wrap-value "set" (encode-seq value))
    (keyword? value) (wrap-value "keyword" (keyword->string value))
    (uuid? value)    (wrap-value "uuid" (str value))
    :else (if-let [tag (find-temporal-tag value)]
            (wrap-value tag (str value))
            value)))

(defn- tagged-map? [m]
  (and (= 2 (count m))
       (contains? m type-key)
       (contains? m value-key)
       (string? (get m type-key))))

(defn- decode-map [m]
  (if (tagged-map? m)
    (let [tag   (get m type-key)
          value (get m value-key)]
      (parse-tagged-value tag value))
    (into {}
          (map (fn [[k v]]
                 [(if (string? k) (string->keyword k) k)
                  (decode-value v)]))
          m)))

(defn decode-value [value]
  (cond
    (map? value)    (decode-map value)
    (vector? value) (mapv decode-value value)
    (sequential? value) (mapv decode-value value)
    :else value))

(defn entity->json-doc
  "Encode an entity map into a JSON-friendly document (keywords => strings, sets => vectors,
   and tagged values for Clojure-specific types)."
  [entity]
  (encode-map entity))

(defn json-doc->entity
  "Decode a JSON document (produced by `entity->json-doc`) back into the original entity map."
  [doc]
  (decode-map doc))
