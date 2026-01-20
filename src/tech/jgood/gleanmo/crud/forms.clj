(ns tech.jgood.gleanmo.crud.forms
  (:require
   [clojure.string :as str]
   [com.biffweb :as biff]
   [tech.jgood.gleanmo.app.shared :refer [side-bar]]
   [tech.jgood.gleanmo.crud.forms.inputs :as inputs]
   [tech.jgood.gleanmo.db.queries :as db]
   [tech.jgood.gleanmo.schema.utils :as schema-utils]
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
  [schema ctx schema-map]
  (let [fields (prepare-form-fields schema)
        pre-populated-values (:pre-populated-values ctx)
        ctx-with-schema (assoc ctx :schema-map schema-map)]
    (for [field fields
          :let  [field-input-name    (:input-name field)
                 pre-populated-value (get pre-populated-values field-input-name)
                 field-with-value    (if pre-populated-value
                                       (assoc field :value pre-populated-value)
                                       field)]]
      (inputs/render field-with-value ctx-with-schema))))

(defn new-form
  "Render a new entity form"
  [{:keys [schema schema-map entity-str]}
   {:keys [params], :as ctx}]
  (let [form-id (str entity-str "-new-form")
        ;; Extract pre-population values from query params. Use param keys
        ;; directly as they match field input names
        pre-populated-values
        (into {}
              (for [[k v] params
                    :when (not= (name k) "redirect")]
                (let [param-key (cond
                                  (keyword? k)
                                  (schema-utils/ns-keyword->input-name k)
                                  (string? k) k
                                  :else (name k))]
                  [param-key v])))]
    (ui/page
     {}
     [:div
      (side-bar
       ctx
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
          (doall (schema->form schema
                               (assoc ctx
                                      :pre-populated-values pre-populated-values)
                               schema-map))
             ;; Hidden field for redirect if provided
          (when-let [redirect (:redirect params)]
            [:input {:type "hidden", :name "redirect", :value redirect}])
          [:button.form-button-primary {:type "submit"}
           "Create"]])]
       [:div.mt-2
        [:a.link.text-sm
         {:href (str "/app/crud/" entity-str "?view=list")}
         "View recent"]])])))

(defn schema->form-with-values
  "Similar to schema->form but includes entity values in input fields"
  [schema entity ctx schema-map]
  (let [fields (prepare-form-fields schema)
        ctx-with-schema (assoc ctx :schema-map schema-map)]
    (for [field fields
          :let  [field-key (:field-key field)
                 value     (get entity field-key)]]
      (inputs/render (assoc field :value value) ctx-with-schema))))

(defn edit-form
  "Render an edit form for an existing entity"
  [{:keys [schema schema-map entity-str entity-key]}
   {:keys [session biff/db path-params params], :as ctx}]
  (let [user-id   (:uid session)
        entity-id (java.util.UUID/fromString (:id path-params))
        entity    (db/get-entity-for-user db entity-id user-id entity-key)
        form-id   (str entity-str "-edit-form")]
    (ui/page
     {}
     [:div
      (let
       [content
        (if (nil? entity)
          [:div.form-section.w-full.md:w-96
           [:h2.form-header (str (str/capitalize entity-str) " Not Found")]
           [:p.form-subheader
            "We couldn't find this item, or you don't have access to it."]
           [:a.form-button-secondary {:href (str "/app/crud/" entity-str)}
            "Back to list"]]
          [:div.w-full.md:w-96.space-y-8
           (biff/form
            {:hx-post   (str "/app/crud/" entity-str "/" entity-id),
             :hx-swap   "outerHTML",
             :hx-select (str "#" form-id),
             :id        form-id}
            [:div
             [:h1.form-header (str "Edit " (str/capitalize entity-str))]
             [:p.form-subheader (str "Edit this " entity-str)]]
            [:div.grid.grid-cols-1.gap-y-6
             (doall
              (schema->form-with-values schema entity ctx schema-map))
             (when-let [redirect (:redirect params)]
               [:input
                {:type "hidden", :name "redirect", :value redirect}])
             [:div.flex.justify-between.mt-4
              [:a.form-button-secondary
               {:href (str "/app/crud/" entity-str)} "Cancel"]
              [:button.form-button-primary {:type "submit"}
               "Save Changes"]]])
           [:div.mt-4.text-right
            (biff/form
             {:action
              (str "/app/crud/" entity-str "/" entity-id "/delete"),
              :method "post",
              :onsubmit
              "return confirm('Are you sure you want to delete this item? This action cannot be undone.');"}
             [:button.link.text-secondary
              {:type "submit", :aria-label "Delete this item"}
              "Delete"])]])]

        (side-bar
         ctx
         content))])))
