(ns tech.jgood.gleanmo.crud.handlers
  (:require [clojure.string :as str]
            [tech.jgood.gleanmo.app.shared :refer [get-user-time-zone]]
            [tech.jgood.gleanmo.crud.forms.converters :refer [convert-field-value]]
            [tech.jgood.gleanmo.schema.utils :as schema-utils]
            [tech.jgood.gleanmo.db.mutations :as mutations]
            [tech.jgood.gleanmo.db.queries :as db]))

;; Helper function moved from forms.clj
(defn form->schema
  "Convert form params to schema-compatible data structure"
  [form-fields schema ctx]
  (let [fields (schema-utils/extract-schema-fields schema)]
    (-> form-fields
        (dissoc :__anti-forgery-token)
        (->> (reduce (fn [acc [k v]]
                       (let [k          (keyword k)
                             field-info (schema-utils/get-field-info schema k)]
                         ;; Skip fields that don't exist in the schema
                         (if (nil? field-info)
                           acc
                           (let [optional?  (get-in field-info [:opts :optional])
                                 #_{:clj-kondo/ignore [:shadowed-var]}
                                 type       (:type field-info)
                                 {:keys [input-type]}
                                 (schema-utils/determine-input-type type)]
                             ;; Skip empty values for optional fields, otherwise
                             ;; convert
                             (if (and optional? (or (nil? v) (str/blank? v)))
                               acc ; Skip this field
                               (let [converted-value
                                     (convert-field-value input-type v ctx)]
                                 (assoc acc k converted-value)))))))
                     ;; Start with defaults for missing boolean fields
                     (reduce (fn [acc field]
                               #_{:clj-kondo/ignore [:shadowed-var]}
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
   {:keys [session biff/db params headers], :as ctx}]
  (let [user-id        (:uid session)
        user           (db/get-entity-by-id db user-id)
        redirect-url   (or (get params "redirect") (get params :redirect))
        schema-params  (dissoc params :__anti-forgery-token :redirect "redirect")
        data           (form->schema schema-params schema ctx)
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

    ;; Return redirect response (use custom redirect if provided)
    (let [default-redirect (str "/app/crud/form/" entity-str "/new")
          final-redirect (or redirect-url default-redirect)
          is-htmx?       (some? (get headers "hx-request"))]
      (if is-htmx?
        {:status  200
         :headers {"HX-Redirect" final-redirect}}
        {:status  303,
         :headers {"location" final-redirect}}))))

(defn update-entity!
  "Handle entity update from form submission"
  [{:keys [schema entity-key entity-str]}
   {:keys [session params path-params biff/db headers] :as ctx}]
  (let [user-id        (:uid session)
        entity-id      (java.util.UUID/fromString (:id path-params))
        redirect-url   (or (get params "redirect") (get params :redirect))
        ;; Get current entity data to compare with
        current-entity (db/get-entity-for-user db entity-id user-id entity-key)

        ;; Process optional boolean fields that might have been unchecked
        updated-params (reduce (fn [acc field]
                                 #_{:clj-kondo/ignore [:shadowed-var]}
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
    (let [default-redirect (str "/app/crud/form/" entity-str "/edit/" entity-id)
          final-redirect   (or redirect-url default-redirect)
          is-htmx?         (some? (get headers "hx-request"))]
      (if is-htmx?
        {:status  200
         :headers {"HX-Redirect" final-redirect}}
        {:status  303,
         :headers {"location" final-redirect}}))))

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
