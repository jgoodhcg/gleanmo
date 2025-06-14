(ns tech.jgood.gleanmo.crud.forms
  (:require
   [clojure.string        :as str]
   [com.biffweb           :as biff]
   [potpuri.core          :as pot]
   [tech.jgood.gleanmo.app.shared :refer
    [side-bar]]
   [tech.jgood.gleanmo.crud.forms.inputs :as inputs]
   [tech.jgood.gleanmo.crud.schema-utils :as schema-utils]
   [tech.jgood.gleanmo.db.queries :as db]
   [tech.jgood.gleanmo.ui :as ui]))

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
        {:user/keys [email]} (db/get-entity-by-id db user-id)
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
                           [:h2.form-header
                            (str "New " (str/capitalize entity-str))]
                           [:p.form-subheader
                            (str "Create a new " entity-str)]]
                          [:div.grid.grid-cols-1.gap-y-6
                           (doall (schema->form schema ctx))
                           [:button.form-button-primary {:type "submit"} "Create"]])])])))

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
  [{:keys [schema entity-str entity-key]} {:keys [session biff/db path-params], :as ctx}]
  (let [user-id   (:uid session)
        {:user/keys [email]} (db/get-entity-by-id db user-id)
        entity-id (java.util.UUID/fromString (:id path-params))
        entity    (db/get-entity-for-user db entity-id user-id entity-key)
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
          [:h1.form-header
           (str "Edit " (str/capitalize entity-str))]
          [:p.form-subheader
           (str "Edit this " entity-str)]]
         [:div.grid.grid-cols-1.gap-y-6
          (doall (schema->form-with-values schema entity ctx))
          [:div.flex.justify-between.mt-4
           [:a.form-button-secondary
            {:href (str "/app/crud/" entity-str)} "Cancel"]
           [:button.form-button-primary
            {:type "submit"} "Save Changes"]]])])])))
