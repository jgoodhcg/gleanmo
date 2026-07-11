(ns tech.jgood.gleanmo.db.relation-labels
  (:require
   [clojure.string :as str]
   [tech.jgood.gleanmo.db.queries :as queries]
   [tech.jgood.gleanmo.schema.meta :as sm]
   [tick.core :as t]))

(defn- entity-instant
  "First timestamp-ish value on the entity (beginning or timestamp)."
  [entity etype]
  (when etype
    (or (get entity (keyword etype "beginning"))
        (get entity (keyword etype "timestamp")))))

(defn- format-instant
  [instant zone-id]
  (->> (t/in (t/instant instant) (t/zone (or zone-id "UTC")))
       (t/format (t/formatter "yyyy-MM-dd HH:mm"))))

(defn entity->label
  "Return the display label for an entity: its :<type>/label when present,
   else its beginning/timestamp formatted in zone-id, else a short id."
  ([entity entity-id] (entity->label entity entity-id nil))
  ([entity entity-id zone-id]
   (when entity
     (let [etype     (some-> entity
                             ::sm/type
                             name)
           label-key (when etype (keyword etype "label"))
           label     (when label-key (get entity label-key))
           id-str    (str entity-id)]
       (or (when-not (str/blank? label) label)
           (some-> (entity-instant entity etype)
                   (format-instant zone-id))
           (subs id-str 0 (min 8 (count id-str)))
           "Item")))))

(defn relationship-label
  "Label a related entity, reading from a request-scoped cache when present."
  [ctx entity-id]
  (when entity-id
    (let [cache (::rel-cache ctx)]
      (if (and cache (contains? @cache entity-id))
        (entity->label (get @cache entity-id) entity-id)
        (let [entity (queries/get-entity-by-id (:biff/db ctx) entity-id)]
          (when cache (swap! cache assoc entity-id entity))
          (entity->label entity entity-id))))))

(defn collect-relation-ids
  "Collect candidate related-entity ids from entity maps."
  [items]
  (->> items
       (mapcat (fn [entity]
                 (mapcat (fn [[k v]]
                           (cond
                             (and (uuid? v)
                                  (not= k :xt/id)
                                  (not= k :user/id))
                             [v]

                             (and (set? v) (seq v) (every? uuid? v))
                             (seq v)))
                         entity)))
       distinct))

(defn prewarm-relation-cache!
  "Batch-fetch related entities referenced by items into the request cache."
  [ctx items]
  (when-let [cache (::rel-cache ctx)]
    (let [ids     (collect-relation-ids items)
          fetched (queries/fetch-entities-by-ids (:biff/db ctx) ids)
          by-id   (into {} (map (juxt :xt/id identity)) fetched)]
      (swap! cache merge (into {} (map (fn [id] [id (get by-id id)])) ids)))))

(defn resolve-relationship-labels
  "Resolve relationship fields to labels grouped by related entity name."
  [ctx entity relationship-fields]
  (reduce
   (fn [acc {:keys [field-key input-type related-entity-str]}]
     (let [field-value (get entity field-key)]
       (if field-value
         (let [labels (case input-type
                        :single-relationship
                        [(relationship-label ctx field-value)]

                        :many-relationship
                        (keep #(relationship-label ctx %) field-value)

                        [])
               group-name (str/capitalize related-entity-str)]
           (if (seq labels)
             (update acc group-name (fn [existing] (concat (or existing []) labels)))
             acc))
         acc)))
   {}
   relationship-fields))
