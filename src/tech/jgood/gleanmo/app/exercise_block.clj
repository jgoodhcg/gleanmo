(ns tech.jgood.gleanmo.app.exercise-block
  (:require
   [tech.jgood.gleanmo.crud.routes :as crud]
   [tech.jgood.gleanmo.schema      :refer [schema]]))

(def crud-routes
  (crud/gen-routes {:entity-key :exercise-block,
                    :entity-str "exercise-block",
                    :plural-str "exercise blocks",
                    :schema     schema}))
