(ns tech.jgood.gleanmo.crud
  (:require
   [clojure.pprint :as p]
   [com.biffweb :as biff]
   [potpuri.core :as pot]
   [tech.jgood.gleanmo.app.shared :refer [side-bar]]
   [xtdb.api :as xt]
   [tech.jgood.gleanmo.ui :as ui]))

(defn example-route [{:keys [session biff/db]}]
  (let [user-id              (:uid session)
        {:user/keys [email]} (xt/entity db user-id)]
    (ui/page
     {}
     [:div
      (side-bar (pot/map-of email)
                [:div.flex.flex-col.md:flex-row.justify-center
                 [:h1.text-3xl.font-bold "Cruddy!"]])])))

(defn gen-routes [entity-key schema]
  (let [schema          (entity-key schema)
        entity-name-str (name entity-key)]
    [(str "/crud/" entity-name-str) {}
     ["" {:get example-route}]]))
