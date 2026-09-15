(ns tech.jgood.gleanmo.crud.forms
  (:require
   [clojure.string :as str]
   [clojure.walk :as walk]
   [com.biffweb :as biff]
   [tech.jgood.gleanmo.app.layout :as layout]
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

(defn- submitted-render
  "Restore raw submitted values after rendering, including invalid scalar input."
  [rendered field ctx]
  (let [params (:crud/submitted ctx)
        input-name (:input-name field)
        key (:field-key field)
        present? (or (contains? params input-name) (contains? params key))
        raw (get params input-name (get params key))
        values (set (map str (if (coll? raw) raw [raw])))]
    (if-not params
      rendered
      (walk/postwalk
       (fn [node]
         (if (and (vector? node) (keyword? (first node)) (map? (second node)))
           (let [[tag attrs & children] node
                 tag-name (first (str/split (name tag) #"[.#]"))]
             (cond
               (= tag-name "option")
               (into [tag (assoc attrs :selected (contains? values (str (:value attrs))))] children)
               (and (= input-name (:name attrs)) (= tag-name "textarea") present?)
               [tag attrs (str raw)]
               (and (= input-name (:name attrs)) (= tag-name "input"))
               (into [tag (if (contains? #{"checkbox" "radio"} (:type attrs))
                            (assoc attrs :checked (and present? (contains? values (str (:value attrs)))))
                            (if present? (assoc attrs :value raw) attrs))] children)
               :else node))
           node))
       rendered))))

(defn render-field
  "Render an input, its help text, and any field errors, preserving submitted values."
  [field ctx]
  (let [rendered (submitted-render (inputs/render field ctx) field ctx)
        description (get-in field [:opts :crud/description])
        errors (get-in ctx [:crud/errors (:field-key field)])]
    (if (or description (seq errors))
      [:div
       rendered
       (when description [:p.form-help description])
       (for [message errors]
         [:p.mt-1.text-sm.text-red-400 {:role "alert"} message])]
      rendered)))

(defn schema->form
  "Convert a schema to form fields"
  [schema ctx schema-map]
  (let [fields (prepare-form-fields schema)
        pre-populated-values (when-not (:crud/submitted ctx) (:pre-populated-values ctx))
        ctx-with-schema (assoc ctx :schema-map schema-map)]
    (for [field fields
          :let  [field-input-name    (:input-name field)
                 pre-populated-value (get pre-populated-values field-input-name)
                 field-with-value    (if pre-populated-value
                                       (assoc field :value pre-populated-value)
                                       field)]]
      (render-field field-with-value ctx-with-schema))))

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
      (layout/page-shell
       ctx {:width :narrow}
       (layout/page-header
        {:title    (str "New " (str/capitalize entity-str))
         :subtitle (str "Create a new " entity-str)})
       [:div.space-y-8
        (biff/form
         {:hx-post   (str "/app/crud/" entity-str),
          :hx-swap   "outerHTML",
          :hx-select (str "#" form-id),
          :id        form-id}
         [:div.grid.grid-cols-1.gap-y-6
          (doall (schema->form schema
                               (assoc ctx
                                      :pre-populated-values pre-populated-values)
                               schema-map))
             ;; Hidden field for redirect if provided
          (when-let [redirect (:redirect params)]
            [:input {:type "hidden", :name "redirect", :value redirect}])
          ;; tabindex 0 opts the button into Safari's default Tab order
          [:button.form-button-primary {:type "submit", :tabindex "0"}
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
      (render-field (assoc field :value value) ctx-with-schema))))

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
          (layout/empty-state
           {:message "We couldn't find this item, or you don't have access to it."
            :action  [:a.form-button-secondary {:href (str "/app/crud/" entity-str)}
                      "Back to list"]})
          [:div.space-y-8
           (biff/form
            {:hx-post   (str "/app/crud/" entity-str "/" entity-id),
             :hx-swap   "outerHTML",
             :hx-select (str "#" form-id),
             :id        form-id}
            [:div.grid.grid-cols-1.gap-y-6
             (doall
              (schema->form-with-values schema entity ctx schema-map))
             (when-let [redirect (:redirect params)]
               [:input
                {:type "hidden", :name "redirect", :value redirect}])
             [:div.flex.justify-between.mt-4
              ;; tabindex 0 opts these into Safari's default Tab order
              [:a.form-button-secondary
               {:href     (or (:redirect params) (str "/app/crud/" entity-str)),
                :tabindex "0"}
               "Cancel"]
              [:button.form-button-primary {:type "submit", :tabindex "0"}
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

        (layout/page-shell
         ctx {:width :narrow}
         (layout/page-header
          {:title    (if entity
                       (str "Edit " (str/capitalize entity-str))
                       (str (str/capitalize entity-str) " Not Found"))
           :subtitle (when entity (str "Edit this " entity-str))})
         content))])))
