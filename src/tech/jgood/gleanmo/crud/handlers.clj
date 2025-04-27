(ns tech.jgood.gleanmo.crud.handlers
  (:require [clojure.string :as str]
            [tech.jgood.gleanmo.app.shared :refer [get-user-time-zone]]
            [tech.jgood.gleanmo.crud.forms.converters :refer [convert-field-value]]
            [tech.jgood.gleanmo.crud.schema-utils :as schema-utils]
            [tech.jgood.gleanmo.db.mutations :as mutations]
            [tech.jgood.gleanmo.db.queries :as db]
            [xtdb.api :as xt]))

;; Helper function moved from forms.clj
(defn form->schema
  "Convert form params to schema-compatible data structure"
  [form-fields schema ctx]
  (let [fields (schema-utils/extract-schema-fields schema)]
    (-> form-fields
        (dissoc :__anti-forgery-token)
        (->> (reduce (fn [acc [k v]]
                       (let [k          (keyword k)
                             field-info (schema-utils/get-field-info schema k)
                             optional?  (get-in field-info [:opts :optional])
                             type       (:type field-info)
                             {:keys [input-type]}
                             (schema-utils/determine-input-type type)]
                         ;; Skip empty values for optional fields, otherwise
                         ;; convert
                         (if (and optional? (or (nil? v) (str/blank? v)))
                           acc ; Skip this field
                           (let [converted-value
                                 (convert-field-value input-type v ctx)]
                             (assoc acc k converted-value)))))
                     ;; Start with defaults for missing boolean fields
                     (reduce (fn [acc field]
                               (let [[field-key opts type] (if (map? (second field))
                                                            [(first field) (second field) (nth field 2)]
                                                            [(first field) {} (second field)])
                                     {:keys [input-type]} (schema-utils/determine-input-type type)
                                     optional? (:optional opts)]
                                 ;; Add default false value for boolean fields that aren't present in form data and aren't optional
                                 (if (and (= input-type :boolean) 
                                          (not (contains? form-fields (name field-key)))
                                          (not optional?))
                                   (assoc acc field-key false)
                                   acc)))
                             {}
                             fields))))))

(defn create-entity!
  "Handle entity creation from form submission"
  [{:keys [schema entity-key entity-str]}
   {:keys [session biff/db params], :as ctx}]
  (let [user-id        (:uid session)
        user           (xt/entity db user-id)
        data           (form->schema params schema ctx)
        ;; Add user ID to data
        data-with-user (assoc data :user/id (:xt/id user))
        ;; Check if time zone is being updated
        time-zone      (-> params
                           (get (str entity-str "/time-zone")))
        user-time-zone (get-user-time-zone ctx)
        new-tz         (and time-zone (not= user-time-zone time-zone))]

    ;; Use the mutations namespace to create the entity
    (mutations/create-entity!
     ctx
     {:entity-key entity-key,
      :data       data-with-user})

    ;; Optionally update user time zone
    (when new-tz
      (mutations/update-entity!
       ctx
       {:entity-key :user,
        :entity-id  user-id,
        :data       {:user/time-zone time-zone}}))

    ;; Return redirect response
    {:status  303,
     :headers {"location" (str "/app/crud/forms/" entity-str "/new")}}))

(defn update-entity!
  "Handle entity update from form submission"
  [{:keys [schema entity-key entity-str]}
   {:keys [session params path-params biff/db] :as ctx}]
  (let [user-id        (:uid session)
        entity-id      (java.util.UUID/fromString (:id path-params))
        ;; Get current entity data to compare with
        current-entity (xt/entity db entity-id)
        
        ;; Process optional boolean fields that might have been unchecked
        updated-params (reduce (fn [acc field]
                                 (let [[field-key opts type] (if (map? (second field))
                                                              [(first field) (second field) (nth field 2)]
                                                              [(first field) {} (second field)])
                                       {:keys [input-type]} (schema-utils/determine-input-type type)
                                       field-name (schema-utils/ns-keyword->input-name field-key)
                                       optional? (:optional opts)]
                                   ;; For optional boolean fields that exist in current entity
                                   ;; but are missing from params (unchecked), explicitly set them to false
                                   (if (and (= input-type :boolean)
                                            optional?
                                            (contains? current-entity field-key)
                                            (get current-entity field-key) ;; Currently true
                                            (not (contains? params field-name)))
                                     (assoc acc field-name "false") ;; Explicitly set to false
                                     acc)))
                               params
                               (schema-utils/extract-schema-fields schema))
        
        form-data      (form->schema updated-params schema ctx)
        time-zone      (-> params
                           (get (str entity-str "/time-zone")))
        user-time-zone (get-user-time-zone ctx)
        new-tz         (and time-zone (not= user-time-zone time-zone))]
    
    ;; Update the entity
    (mutations/update-entity!
     ctx
     {:entity-key entity-key,
      :entity-id  entity-id,
      :data       form-data})
    
    ;; Optionally update user time zone
    (when new-tz
      (mutations/update-entity!
       ctx
       {:entity-key :user,
        :entity-id  user-id,
        :data       {:user/time-zone time-zone}}))
    
    ;; Return redirect response
    {:status  303,
     :headers {"location"
               (str "/app/crud/forms/" entity-str "/edit/" entity-id)}}))

(defn delete-entity!
  "Soft-delete an entity by setting its deleted-at timestamp"
  [{:keys [entity-key entity-str]} {:keys [biff/db path-params], :as ctx}]
  (let [entity-id (java.util.UUID/fromString (:id path-params))
        user-id   (-> ctx
                      :session
                      :uid)
        entity    (db/get-entity-for-user db
                                          entity-id
                                          user-id
                                          entity-key)]
    ;; Perform soft delete by setting deleted-at timestamp if entity exists
    (if entity
      (do
        ;; Use the mutations namespace to delete the entity
        (mutations/soft-delete-entity!
         ctx
         {:entity-key entity-key,
          :entity-id  entity-id})

        {:status  303,
         :headers {"location" (str "/app/crud/" entity-str)}})
      ;; Entity not found or doesn't belong to this user
      {:status  303,
       :headers {"location" (str "/app/crud/" entity-str)}})))
