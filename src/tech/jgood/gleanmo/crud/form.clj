(ns tech.jgood.gleanmo.crud.form
  (:require
   [clojure.string :as str]
   [com.biffweb :as biff]
   [clojure.pprint :refer [pprint]]
   [potpuri.core :as pot]
   [tech.jgood.gleanmo.app.shared :refer [side-bar]]
   [tech.jgood.gleanmo.crud.fields :refer [field-input prepare-field]]
   [tech.jgood.gleanmo.ui :as ui]
   [xtdb.api :as xt]))

(defn schema->form [schema ctx]
  (let [has-opts   (map? (second schema))
        raw-fields (if has-opts (drop 2 schema) (rest schema))
        fields     (->> raw-fields
                      (map prepare-field)
                      ;; remove fields that aren't necessary for new forms
                      (remove (fn [{:keys [field-key]}]
                                (let [n (namespace field-key)]
                                  (or
                                   (= :xt/id field-key)
                                   (= "tech.jgood.gleanmo.schema" n)
                                   (= "tech.jgood.gleanmo.schema.meta" n))))))]
    (for [field fields]
      (field-input field ctx))))

(defn new-form [{:keys [entity-key
                        schema
                        plural-str
                        entity-str]}
                {:keys [session biff/db params]
                 :as   ctx}]
  (let [user-id              (:uid session)
        {:user/keys [email]} (xt/entity db user-id)
        form-id              (str entity-str "-new-form")]
    (ui/page
     {}
     [:div
      (side-bar (pot/map-of email)
                [:div.w-full.md:w-96.space-y-8
                 (biff/form
                  {:hx-post   (str "/app/crud/" entity-str)
                   :hx-swap   "outerHTML"
                   :hx-select (str "#" form-id)
                   :id        form-id}
                  [:div
                   [:h2.text-base.font-semibold.leading-7.text-gray-900
                    (str "New " (str/capitalize entity-str))]
                   [:p.mt-1.text-sm.leading-6.text-gray-600
                    (str "Create a new " entity-str)]]
                  [:div.grid.grid-cols-1.gap-y-6
                   (doall (schema->form schema ctx))
                   [:button {:type "submit"} "Create"]])])])))

(defn get-type [schema k]
  (some (fn [[field-key & rest]]
          (when (= field-key k)
            (if (map? (first rest))
              (second rest)  ; When an options map is present
              (first rest)))) ; When no options map is present
        (drop 2 schema)))

(defn form->schema [form-fields schema ctx]
  (->> form-fields
       (mapv (fn [[k v]]
               (let [k (-> k keyword)]
                 {:k k :v v :type (get-type schema k)})))
       pprint)
  )

(defn create-entity! [{:keys [schema]
                       :as args}
                      {:keys [session biff/db params]
                       :as ctx}]
  (let [user-id (:uid session)
        user    (xt/entity db user-id)
        entity  (-> args :entity-key name)]
    (pprint (pot/map-of user params schema))
    (form->schema params schema ctx)
    {:status  303
     :headers {"location" (str "/app/crud/new/" entity)}}))
