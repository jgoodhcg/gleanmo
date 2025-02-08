(ns tech.jgood.gleanmo.crud.form
  (:require
   [clojure.string :as str]
   [com.biffweb :as biff]
   [potpuri.core :as pot]
   [tech.jgood.gleanmo.app.shared :refer [side-bar]]
   [tech.jgood.gleanmo.crud.fields :refer [parse-field render-field-input]]
   [tech.jgood.gleanmo.ui :as ui]
   [xtdb.api :as xt]))

(defn schema->form [schema ctx]
  (let [has-opts (map? (second schema))
        fields   (if has-opts (drop 2 schema) (rest schema))
        fields   (->> fields
                      (map parse-field)
                      ;; remove fields that aren't necessary for new forms
                      (remove (fn [{:keys [field-key]}]
                                (let [n (namespace field-key)]
                                  (or
                                   (= :xt/id field-key)
                                   (= "tech.jgood.gleanmo.schema" n)
                                   (= "tech.jgood.gleanmo.schema.meta" n))))))]
    (for [field fields]
      (render-field-input field ctx))))

(defn new-form [{:keys [entity-key
                        schema
                        plural-str
                        entity-name-str]}
                {:keys [session biff/db params]
                 :as   ctx}]
  (let [user-id              (:uid session)
        {:user/keys [email]} (xt/entity db user-id)]
    (ui/page
     {}
     [:div
      (side-bar (pot/map-of email)
                [:div.w-full.md:w-96.space-y-8
                 (biff/form
                  {}
                  [:div
                   [:h2.text-base.font-semibold.leading-7.text-gray-900
                    (str "New " (str/capitalize entity-name-str))]
                   [:p.mt-1.text-sm.leading-6.text-gray-600
                    (str "Create a new " entity-name-str)]]
                  [:div.grid.grid-cols-1.gap-y-6
                   (doall (schema->form schema ctx))
                   [:button {:type "submit"} "Create"]])])])))
