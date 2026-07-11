(ns tech.jgood.gleanmo.app.symptom-episode
  (:require
   [tech.jgood.gleanmo.crud.routes :as crud]
   [tech.jgood.gleanmo.schema      :refer [schema]]))

(def crud-routes
  (crud/gen-routes {:entity-key :symptom-episode,
                    :entity-str "symptom-episode",
                    :plural-str "symptom episodes",
                    :schema     schema}))
