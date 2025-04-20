(ns tech.jgood.gleanmo.crud.forms
  (:require [clojure.string :as str]
            [com.biffweb :as biff :refer [q]]
            [clojure.pprint :refer [pprint]]
            [potpuri.core :as pot]
            [tech.jgood.gleanmo.app.shared :refer
             [side-bar get-user-time-zone]]
            [tech.jgood.gleanmo.crud.forms.inputs :as inputs]
            [tech.jgood.gleanmo.crud.forms.converters :refer [convert-field-value]]
            [tech.jgood.gleanmo.crud.operations :as operations]
            [tech.jgood.gleanmo.crud.schema-utils :as schema-utils]
            [tech.jgood.gleanmo.db.mutations :as mutations]
            [tech.jgood.gleanmo.ui :as ui]
            [tech.jgood.gleanmo.schema.meta :as sm]
            [tick.core :as t]
            [xtdb.api :as xt])
  (:import [java.time LocalDateTime ZonedDateTime]))

(defn prepare-form-fields
  "Extract and prepare fields from a schema, filtering out system fields.
   Returns a sequence of prepared field maps."
  [schema]
  (let [raw-fields (schema-utils/extract-schema-fields schema)]
    (->> raw-fields
         (map schema-utils/prepare-field)
         ;; remove fields that aren't necessary for forms
         (remove schema-utils/should-remove-system-or-user-field?))))

(defn schema->form
  [schema ctx]
  (let [fields (prepare-form-fields schema)]
    (for [field fields] (inputs/render field ctx))))

(defn new-form
  [{:keys [entity-key schema plural-str entity-str]}
   {:keys [session biff/db params], :as ctx}]
  (let [user-id (:uid session)
        {:user/keys [email]} (xt/entity db user-id)
        form-id (str entity-str "-new-form")]
    (ui/page {}
             [:div
              (side-bar (pot/map-of email)
                        [:div.w-full.md:w-96.space-y-8
                         (biff/form
                          {:hx-post   (str "/app/crud/" entity-str),
                           :hx-swap   "outerHTML",
                           :hx-select (str "#" form-id),
                           :id        form-id}
                          [:div
                           [:h2.text-base.font-semibold.leading-7.text-gray-900
                            (str "New " (str/capitalize entity-str))]
                           [:p.mt-1.text-sm.leading-6.text-gray-600
                            (str "Create a new " entity-str)]]
                          [:div.grid.grid-cols-1.gap-y-6
                           (doall (schema->form schema ctx))
                           [:button {:type "submit"} "Create"]])])])))


(defn form->schema
  [form-fields schema ctx]
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
                   {}))))

(defn create-entity!
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

(defn schema->form-with-values
  "Similar to schema->form but includes entity values in input fields"
  [schema entity ctx]
  (let [fields (prepare-form-fields schema)]
    (for [field fields
          :let  [field-key (:field-key field)
                 value     (get entity field-key)]]
      (inputs/render (assoc field :value value) ctx))))

(defn edit-form
  [{:keys [schema entity-str]} {:keys [session biff/db path-params], :as ctx}]
  (let [user-id   (:uid session)
        {:user/keys [email]} (xt/entity db user-id)
        entity-id (java.util.UUID/fromString (:id path-params))
        entity    (xt/entity db entity-id)
        form-id   (str entity-str "-edit-form")]
    (ui/page
     {}
     [:div
      (side-bar
       (pot/map-of email)
       [:div.w-full.md:w-96.space-y-8
        (biff/form
         {:hx-post   (str "/app/crud/" entity-str "/" entity-id),
          :hx-swap   "outerHTML",
          :hx-select (str "#" form-id),
          :id        form-id}
         [:div
          [:h1.text-xl.font-bold.mb-4
           (str "Edit " (str/capitalize entity-str))]
          [:p.mt-1.text-sm.leading-6.text-gray-600
           (str "Edit this " entity-str)]]
         [:div.grid.grid-cols-1.gap-y-6
          (doall (schema->form-with-values schema entity ctx))
          [:div.flex.justify-between.mt-4
           [:a.inline-flex.items-center.px-4.py-2.bg-gray-200.text-gray-800.rounded-md.hover:bg-gray-300
            {:href (str "/app/crud/" entity-str)} "Cancel"]
           [:button.inline-flex.items-center.px-4.py-2.bg-blue-600.text-white.rounded-md.hover:bg-blue-700
            {:type "submit"} "Save Changes"]]])])])))

(defn update-entity!
  [{:keys [schema entity-key entity-str], :as args}
   {:keys [session biff/db params path-params], :as ctx}]
  (let [user-id        (:uid session)
        entity-id      (java.util.UUID/fromString (:id path-params))
        entity         (xt/entity db entity-id)
        form-data      (form->schema params schema ctx)
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
        entity    (operations/get-entity-for-user db
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

