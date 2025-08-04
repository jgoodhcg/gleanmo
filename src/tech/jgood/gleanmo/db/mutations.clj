(ns tech.jgood.gleanmo.db.mutations
  (:require
   [com.biffweb :as biff]
   [tech.jgood.gleanmo.schema.meta :as sm]
   [tick.core :as t]))

(defn create-entity!
  "Create a new entity in the database."
  [ctx {:keys [entity-key data]}]
  (let [doc (merge {:xt/id          (random-uuid),
                    ::sm/type       entity-key,
                    ::sm/created-at (t/now)}
                   data)]
    (biff/submit-tx ctx
                    [(merge {:db/doc-type entity-key,
                             :xt/id       (:xt/id doc)}
                            doc)])
    (:xt/id doc)))

(defn update-entity!
  "Update an existing entity in the database."
  [ctx {:keys [entity-key entity-id data]}]
  (let [tx-op   {:db/op       :update,
                 :db/doc-type entity-key,
                 ::sm/type    entity-key,
                 :xt/id       entity-id}
        tx-data (merge tx-op data)]
    (biff/submit-tx ctx [tx-data])
    entity-id))

(defn soft-delete-entity!
  "Soft-delete an entity by setting its deleted-at timestamp."
  [ctx {:keys [entity-key entity-id]}]
  (let [tx-op {:db/op          :update,
               :db/doc-type    entity-key,
               :xt/id          entity-id,
               ::sm/deleted-at (t/now)}]
    (biff/submit-tx ctx [tx-op])
    entity-id))

(defn update-user!
  "Update user entity with provided data."
  [ctx user-id user-data]
  (let [tx-data (merge {:db/op       :update
                        :db/doc-type :user
                        :xt/id       user-id}
                       user-data)]
    (biff/submit-tx ctx [tx-data])
    user-id))

