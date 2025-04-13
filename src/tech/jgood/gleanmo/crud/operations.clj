(ns tech.jgood.gleanmo.crud.operations
  (:require
   [clojure.pprint :refer [pprint]]
   [potpuri.core :as pot]
   [clojure.string :as str]
   [tech.jgood.gleanmo.app.shared :refer [param-true?]]
   [tech.jgood.gleanmo.crud.schema-utils :as schema-utils]
   [tech.jgood.gleanmo.db.queries :as db]
   [tech.jgood.gleanmo.schema.meta :as sm]
   [tick.core :as t]))

;; This function now delegates to the DB layer
(defn get-entity-for-user
  "Get a single entity by ID that belongs to a specific user.
   Returns the first result or nil if not found."
  [db entity-id user-id entity-type]
  (db/get-entity-for-user db entity-id user-id entity-type))

(defn all-for-user-query
  [{:keys [entity-type-str schema filter-references]}
   {:keys [biff/db session params]}]
  (let [user-id             (:uid session)
        sensitive           (some-> params
                                    :sensitive
                                    param-true?)
        archived            (some-> params
                                    :archived
                                    param-true?)
        entity-type         (keyword entity-type-str)

        ;; Get relationship fields from schema, removing system fields
        relationship-fields (when (and schema filter-references)
                              (schema-utils/extract-relationship-fields
                               schema
                               :remove-system-fields
                               true))]

    ;; Delegate to the DB layer
    (db/all-entities-for-user
     db
     user-id
     entity-type
     :filter-sensitive    sensitive
     :filter-archived     archived
     :filter-references   filter-references
     :relationship-fields relationship-fields)))
