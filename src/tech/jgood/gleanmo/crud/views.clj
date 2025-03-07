(ns tech.jgood.gleanmo.crud.views
  (:require
   [clojure.pprint :refer [pprint]]
   [clojure.string :as str]
   [com.biffweb :as biff]
   [potpuri.core :as pot]
   [tech.jgood.gleanmo.app.shared :refer [side-bar
                                         get-user-time-zone
                                         link-button
                                         param-true?]]
   [tech.jgood.gleanmo.crud.operations :as ops]
   [tech.jgood.gleanmo.ui :as ui]
   [tech.jgood.gleanmo.schema.meta :as sm]
   [xtdb.api :as xt]))

(defn list-entities [{:keys [entity-key entity-str] :as args}
                     {:keys [session biff/db] :as ctx}]
  (let [user-id              (:uid session)
        {:user/keys [email]} (xt/entity db user-id)
        entity-type-str      (name entity-key)
        entities             (ops/all-for-user-query {:entity-type-str entity-type-str} ctx)]
    (ui/page
     {}
     [:div
      (side-bar (pot/map-of email)
                [:div.p-4 
                 [:h1.text-2xl.font-bold.mb-4 (str (-> entity-str str/capitalize) " List")]
                 [:div.mb-4
                  [:a.bg-blue-500.hover:bg-blue-700.text-white.font-bold.py-2.px-4.rounded
                   {:href (str "/app/crud/new/" entity-str)} 
                   (str "New " entity-str)]]
                 
                 (if (empty? entities)
                   [:div.text-lg "No items found"]
                   [:div
                    [:p.mb-2.text-gray-600 (str "Found " (count entities) " " entity-str(when (> (count entities) 1) "s"))]
                    [:pre.bg-gray-100.p-4.rounded.overflow-auto
                     (with-out-str
                       (doseq [entity entities]
                         (pprint entity)
                         (println "----------------")))]
                    ])])])))
