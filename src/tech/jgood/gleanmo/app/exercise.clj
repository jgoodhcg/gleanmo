(ns tech.jgood.gleanmo.app.exercise
  (:require
   [tech.jgood.gleanmo.crud.routes :as crud]
   [tech.jgood.gleanmo.schema      :refer [schema]]))

(def crud-routes
  (crud/gen-routes {:entity-key :exercise,
                    :entity-str "exercise",
                    :plural-str "exercises",
                    :schema     schema}))
