(ns tech.jgood.gleanmo.postgres.client
  "Thin helper layer around next.jdbc for working with the Postgres `entities` table while
  we prototype the migration. Provides basic insert/select/delete operations that leverage
  HoneySQL for query construction and the JSON codec for round-tripping entity documents."
  (:require
   [cheshire.core :as json]
   [honey.sql :as sql]
   [honey.sql.helpers :as h]
   [next.jdbc :as jdbc]
   [next.jdbc.result-set :as rs]
   [tech.jgood.gleanmo.postgres.codec :as codec]
   [tech.jgood.gleanmo.schema.meta :as sm])
  (:import
   [java.sql Timestamp]
   [java.time Instant]
   [org.postgresql.util PGobject]))

(defn- as-jsonb [value]
  (doto (PGobject.)
    (.setType "jsonb")
    (.setValue (json/generate-string value))))

(defn- pgobject->json-string [value]
  (cond
    (nil? value) nil
    (instance? PGobject value) (.getValue ^PGobject value)
    (string? value) value
    :else (str value)))

(defn- decode-doc [pg-value]
  (some-> pg-value
          pgobject->json-string
          (json/parse-string)
          (codec/json-doc->entity)))

(defn- keyword->type-string [k]
  (let [n (name k)
        ns (namespace k)]
    (if ns
      (str ns "/" n)
      n)))

(defn- entity-type-string [entity]
  (if-let [entity-type (::sm/type entity)]
    (keyword->type-string entity-type)
    (throw (ex-info "Entity is missing ::sm/type" {:entity entity}))))

(defn- ensure-required-keys! [entity]
  (doseq [k [:xt/id :user/id]]
    (when-not (contains? entity k)
      (throw (ex-info "Entity missing required key."
                      {:key k
                       :entity entity})))))

(defn- instant->timestamp [value]
  (cond
    (nil? value) nil
    (instance? Timestamp value) value
    (instance? Instant value) (Timestamp/from ^Instant value)
    :else (throw (ex-info "Unsupported timestamp value."
                          {:value value
                           :class (class value)}))))

(defn- entity->row
  [entity {:keys [created-at updated-at]}]
  (ensure-required-keys! entity)
  (let [created (or (::sm/created-at entity)
                    created-at
                    (Instant/now))
        updated (or (::sm/updated-at entity)
                    updated-at
                    created)
        entity-doc (-> entity
                       (cond-> (nil? (::sm/created-at entity))
                         (assoc ::sm/created-at created))
                       (cond-> (nil? (::sm/updated-at entity))
                         (assoc ::sm/updated-at updated)))]
    {:entity_id   (:xt/id entity)
     :user_id     (:user/id entity)
     :entity_type (entity-type-string entity)
     :doc         (as-jsonb (codec/entity->json-doc entity-doc))
     :created_at  (instant->timestamp created)
     :updated_at  (instant->timestamp updated)
     :deleted_at  (instant->timestamp (::sm/deleted-at entity))}))

(def ^:private execute-opts
  {:builder-fn rs/as-unqualified-lower-maps})

(defn insert-entity!
  "Insert an entity document into Postgres and return the stored entity (decoded from JSONB).

  Accepts optional overrides map with `:created-at` / `:updated-at` to simplify deterministic testing."
  ([ds entity] (insert-entity! ds entity {}))
  ([ds entity overrides]
   (let [sql-map (-> (h/insert-into :entities)
                     (h/values [(entity->row entity overrides)])
                     (h/returning :entity_id :doc))
         [sql & params] (sql/format sql-map {:inline false})
         row (jdbc/execute-one! ds (into [sql] params) execute-opts)]
     (decode-doc (:doc row)))))

(defn fetch-entity
  "Fetch an entity by id (UUID) and decode it back into the original map shape."
  [ds entity-id]
  (let [sql-map (-> (h/select :doc)
                    (h/from :entities)
                    (h/where [:= :entity_id entity-id]))
        [sql & params] (sql/format sql-map {:inline false})
        row (jdbc/execute-one! ds (into [sql] params) execute-opts)]
    (some-> row :doc decode-doc)))

(defn delete-entity!
  "Remove an entity by id. Returns the number of affected rows."
  [ds entity-id]
  (let [sql-map (-> (h/delete-from :entities)
                    (h/where [:= :entity_id entity-id]))
        [sql & params] (sql/format sql-map {:inline false})
        result (jdbc/execute! ds (into [sql] params))]
    (get-in result [0 :update-count] 0)))
