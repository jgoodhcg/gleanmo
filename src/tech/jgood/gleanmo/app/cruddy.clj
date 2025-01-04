(ns tech.jgood.gleanmo.app.cruddy
  (:require [tech.jgood.gleanmo.schema :refer [schema]]
            [tech.jgood.gleanmo.crud :as crud]
            [tech.jgood.gleanmo.app.shared :refer [side-bar]]
            [potpuri.core :as pot]
            [tech.jgood.gleanmo.ui :as ui]
            [xtdb.api :as xt]
            ))

(defn root [{:keys [session biff/db]}]
  (let [user-id              (:uid session)
        {:user/keys [email]} (xt/entity db user-id)]
    (ui/page
     {}
     [:div
      (side-bar (pot/map-of email)
                [:div.flex.flex-col.md:flex-row.justify-center
                 [:h1.text-3xl.font-bold "Cruddy!"]])])))

(def routes ["/crud" {}
             ["" {:get root}]])
