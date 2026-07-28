(ns tech.jgood.gleanmo.crud.inline
  "Inline related-entity creation: create a missing related entity from inside
   the form that needs it, without a page bounce and without losing form state.

   The two handlers here are mounted for every entity by
   `tech.jgood.gleanmo.crud.routes/gen-routes`, so any relationship field that
   opts in with `:crud/inline-create true` gets the behavior for free.

   See roadmap/inline-entity-creation.md."
  (:require
   [cheshire.core :as cheshire]
   [clojure.string :as str]
   [malli.core :as malli]
   [malli.error :as malli-error]
   [malli.registry :as malli-registry]
   [xtdb.api :as xt]
   [tech.jgood.gleanmo.crud.forms.inputs :as inputs]
   [tech.jgood.gleanmo.crud.handlers :as handlers]
   [tech.jgood.gleanmo.db.mutations :as mutations]
   [tech.jgood.gleanmo.db.queries :as db]
   [tech.jgood.gleanmo.schema :as schema-registry]
   [tech.jgood.gleanmo.schema.utils :as schema-utils]
   [tech.jgood.gleanmo.ui :as ui]))

(def ^:private fallback-malli-opts
  "Entity-validation registry for when the request context has no usable
   `:biff/malli-opts` — it holds a *var* there, and is absent entirely in unit
   tests. Built from the schema module directly rather than from
   `tech.jgood.gleanmo/malli-opts`, which would be a circular dependency."
  (delay {:registry (malli-registry/composite-registry
                     (malli/default-schemas)
                     schema-registry/schema)}))

(defn- malli-opts
  "Malli options carrying the entity registry, tolerating the var that Biff
   stores in the system map."
  [ctx]
  (let [o (:biff/malli-opts ctx)
        o (if (var? o) @o o)]
    (or o @fallback-malli-opts)))

(defn- schema-properties
  "Malli map properties for an entity schema, e.g. {:closed true ...}."
  [entity-schema]
  (when (map? (second entity-schema))
    (second entity-schema)))

(defn quick-create-fields
  "Field keys shown in the quick-create tier for `entity-key`.

   Uses `:crud/quick-create-fields` from the schema's map properties when
   declared; otherwise defaults to the entity's own label field when it has a
   required one. Returns nil when neither applies, which means the mini-form
   opens at the full tier instead (e.g. `book`, whose label is optional and
   whose `title` is required)."
  [entity-schema entity-key]
  (let [declared (:crud/quick-create-fields (schema-properties entity-schema))
        label-key (keyword (name entity-key) "label")
        label-field (schema-utils/schema-field entity-schema label-key)
        {:keys [opts]} (when label-field (schema-utils/parse-field label-field))]
    (cond
      (seq declared) (vec declared)
      (and label-field (not (:optional opts))) [label-key]
      :else nil)))

(defn- form-fields
  "Prepared field maps for the mini-form: the quick-create subset when one
   applies, otherwise every user-editable field (the full tier)."
  [entity-schema entity-key full?]
  (let [quick (when-not full? (quick-create-fields entity-schema entity-key))
        all   (->> (schema-utils/extract-schema-fields entity-schema)
                   (map schema-utils/prepare-field)
                   (remove schema-utils/should-remove-system-or-user-field?))]
    (if (seq quick)
      (filter #(contains? (set quick) (:field-key %)) all)
      all)))

(defn- parent-relationship-field
  "Find the prepared relationship field on the parent schema that the
   inline-create was launched from, so the response can re-render it."
  [schema-map parent-str field-name]
  (let [parent-schema (get schema-map (keyword parent-str))]
    (when parent-schema
      (->> (schema-utils/extract-relationship-fields parent-schema)
           (filter #(= (:input-name %) field-name))
           first))))

(defn- param
  "Read a request param by name, tolerating string or keyword keys — the same
   defensiveness `handlers/create-entity!` applies to `redirect`."
  [params k]
  (or (get params (keyword k)) (get params k)))

(defn blank-required-errors
  "Errors for required string fields submitted blank.

   Malli happily accepts \"\" for a required `:string`, and the full CRUD form
   only stops it with the HTML `required` attribute — which never fires for the
   mini-form, since htmx posts it from a plain button rather than a form
   submit. Without this check an inline create can write a label-less record."
  [entity-schema data]
  (into {}
        (for [entry (schema-utils/extract-schema-fields entity-schema)
              :let  [{:keys [field-key opts]} (schema-utils/parse-field entry)
                     v (get data field-key)]
              :when (and (not (:optional opts))
                         (string? v)
                         (str/blank? v))]
          [field-key ["must not be blank"]])))

(defn- fragment
  "200 with an HTML fragment body (no page wrapper, no sidebar)."
  ([ctx body] (fragment ctx body nil))
  ([ctx body extra-headers]
   {:status  200
    :headers (merge {"content-type" "text/html"} extra-headers)
    :body    (ui/fragment ctx body)}))

(defn mini-form-body
  "The mini-form itself. Deliberately not a `<form>` element: it renders
   *inside* the parent CRUD form, and nested forms are invalid HTML. The
   submit button gathers its values with `hx-include` instead."
  [{:keys [entity-str entity-key schema schema-map]} ctx
   {:keys [parent field-name label full? errors]}]
  (let [mount-id  (inputs/field-dom-id "inline-mount" field-name)
        target-id (inputs/field-dom-id "rel-field" field-name)
        fields    (form-fields schema entity-key full?)
        label-key (keyword (name entity-key) "label")
        ctx+      (assoc ctx :schema-map schema-map)]
    [:div.mt-2.rounded-lg.border.border-dark.bg-dark-surface.p-3
     {:id (str mount-id "-form")}
     [:div.flex.items-center.justify-between.mb-2
      [:h3.text-sm.font-semibold.text-white
       (str "New " (str/replace entity-str "-" " "))]
      [:button.link.text-xs.text-gray-400.cursor-pointer
       {:type "button"
        ;; Clearing the mount is a pure DOM operation; a round trip to the
        ;; server just to render "" would be wasteful.
        :onclick "this.closest('[data-inline-mount]').innerHTML=''"}
       "Cancel"]]
     (when (seq errors)
       [:ul.mb-2.text-xs.text-red-400
        (for [[k msgs] errors]
          [:li {:key (str k)} (str (name k) ": " (str/join ", " msgs))])])
     [:div.grid.grid-cols-1.gap-y-3
      (for [f fields
            :let [f (cond-> f
                      ;; Seed the label from the text the user typed into the
                      ;; select's search box, so the search-empty path costs
                      ;; zero extra typing.
                      (and label (= (:field-key f) label-key))
                      (assoc :value label))]]
        ^{:key (:input-name f)}
        [:div (inputs/render f ctx+)])]
     [:div.flex.items-center.gap-3.mt-3
      ;; hx-select "unset" on both: the enclosing CRUD form sets hx-select to
      ;; its own id and htmx inherits it, which would select nothing out of
      ;; these fragments and swap in an empty string.
      [:button.form-button-primary.text-sm.cursor-pointer
       {:type      "button"
        :hx-post   (str "/app/crud/inline/" entity-str)
        :hx-vals   (cheshire/generate-string
                    {:parent parent :field field-name :full (boolean full?)})
        :hx-include (str "#" mount-id "-form input, #" mount-id "-form select,"
                         " #" mount-id "-form textarea")
        :hx-target (str "#" target-id)
        :hx-swap   "innerHTML"
        :hx-select "unset"}
       "Create"]
      (when-not full?
        [:button.link.text-xs.text-gray-400.cursor-pointer
         {:type      "button"
          :hx-get    (str "/app/crud/inline/" entity-str "/new")
          :hx-vals   (cheshire/generate-string
                      {:parent parent :field field-name
                       :label (or label "") :full true})
          :hx-target (str "#" mount-id)
          :hx-swap   "innerHTML"
          :hx-select "unset"}
         "More fields"])]
     ;; Deliberately no __anti-forgery-token input here. htmx includes the
     ;; enclosing form's fields on non-GET requests, so the parent CRUD form
     ;; already supplies one; adding a second sends the param twice, which
     ;; Ring parses into a vector and anti-forgery then rejects. `ui/page`
     ;; also sets the token as an hx-headers X-CSRF-Token.
     ]))

(defn mini-form
  "GET handler: return the mini-form fragment for creating a related entity."
  [args {:keys [params] :as ctx}]
  (fragment
   ctx
   (mini-form-body args ctx
                   {:parent     (param params "parent")
                    :field-name (param params "field")
                    :label      (not-empty (param params "label"))
                    :full?      (= "true" (str (param params "full")))})))

(defn create!
  "POST handler: create the related entity, then re-render the parent form's
   relationship field with the new entity selected.

   On validation failure nothing is written and the mini-form comes back with
   per-field errors, so the user stays in place."
  [{:keys [entity-key schema schema-map] :as args}
   {:keys [session biff/db params] :as ctx}]
  (let [user-id     (:uid session)
        user        (db/get-entity-by-id db user-id)
        parent      (param params "parent")
        field-name  (param params "field")
        full?       (= "true" (str (param params "full")))
        data        (handlers/params->entity-data schema entity-key params
                                                  (:xt/id user) ctx)
        doc         (mutations/entity-doc entity-key data)
        opts        (malli-opts ctx)
        errors      (merge (when-not (malli/validate entity-key doc opts)
                             (malli-error/humanize
                              (malli/explain entity-key doc opts)))
                           (blank-required-errors schema data))]
    (if (seq errors)
      ;; The Create button targets the whole field container so a success can
      ;; re-render the select. A failure must not do that — it would blow the
      ;; select away — so retarget the swap to the mini-form's own mount.
      (fragment
       ctx
       (mini-form-body args ctx
                       {:parent     parent
                        :field-name field-name
                        :label      (param params (str (name entity-key) "/label"))
                        :full?      full?
                        :errors     errors})
       {"HX-Retarget" (str "#" (inputs/field-dom-id "inline-mount" field-name))})
      (let [new-id (mutations/create-entity! ctx {:entity-key entity-key
                                                  :data       data})
            field  (parent-relationship-field schema-map parent field-name)
            ;; The request's db snapshot predates the write, so re-rendering
            ;; the select against it would omit the very entity we just
            ;; created. Take a fresh snapshot for the response.
            ctx'   (cond-> (assoc ctx :schema-map schema-map)
                     (:biff.xtdb/node ctx)
                     (assoc :biff/db (xt/db (:biff.xtdb/node ctx))))]
        (if field
          (fragment ctx (inputs/single-relationship-body
                         (assoc field :value new-id)
                         ctx'))
          ;; Unknown parent field: the entity was still created, so fall back
          ;; to a full page load rather than swapping in something misleading.
          {:status  200
           :headers {"HX-Refresh" "true"}})))))
