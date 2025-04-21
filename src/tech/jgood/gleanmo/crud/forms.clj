(ns tech.jgood.gleanmo.crud.forms
  (:require [clojure.string :as str]
            [potpuri.core :as pot]
            [com.biffweb :as biff]
            [tech.jgood.gleanmo.app.shared :refer
             [side-bar get-user-time-zone]]
            [tech.jgood.gleanmo.crud.forms.inputs :as inputs]
            [tech.jgood.gleanmo.crud.schema-utils :as schema-utils]
            [tech.jgood.gleanmo.ui :as ui]
            [xtdb.api :as xt]))

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
  "Convert a schema to form fields"
  [schema ctx]
  (let [fields (prepare-form-fields schema)]
    (for [field fields] (inputs/render field ctx))))

(defn new-form
  "Render a new entity form"
  [{:keys [schema entity-str]}
   {:keys [session biff/db], :as ctx}]
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

(defn schema->form-with-values
  "Similar to schema->form but includes entity values in input fields"
  [schema entity ctx]
  (let [fields (prepare-form-fields schema)]
    (for [field fields
          :let  [field-key (:field-key field)
                 value     (get entity field-key)]]
      (inputs/render (assoc field :value value) ctx))))

(defn edit-form
  "Render an edit form for an existing entity"
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
