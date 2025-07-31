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

(defn upsert-user-settings!
  "Upsert user settings with defaults merged in.
   Creates new settings if none exist, updates existing ones otherwise."
  [ctx user-id settings-map]
  (let [{:keys [biff/db]} ctx
        default-settings {:user/id                      user-id
                          :user-settings/show-sensitive false
                          ;; Add more default settings here as they grow
                          }
        merged-settings (merge default-settings settings-map)
        ;; Check if user settings already exist
        existing-settings (first (biff/q db
                                    {:find  '(pull ?e [*]),
                                     :where ['[?e :user/id user-id]
                                             ['?e ::sm/type :user-settings]
                                             '(not [?e ::sm/deleted-at])],
                                     :in    ['user-id]}
                                    user-id))]
    (if existing-settings
      ;; Update existing settings
      (let [tx-doc (merge {:db/op       :update
                           :db/doc-type :user-settings
                           :xt/id       (:xt/id existing-settings)}
                          merged-settings)]
        (biff/submit-tx ctx [tx-doc]))
      ;; Create new settings
      (let [tx-doc (merge {:db/op          :create
                           :db/doc-type    :user-settings
                           :xt/id          (random-uuid)
                           ::sm/type       :user-settings
                           ::sm/created-at (t/now)}
                          merged-settings)]
        (biff/submit-tx ctx [tx-doc])))
    user-id))
