(ns tech.jgood.gleanmo.app.exercise-set
  (:require
   [tech.jgood.gleanmo.crud.routes :as crud]
   [tech.jgood.gleanmo.schema      :refer [schema]]))

(def crud-routes
  (crud/gen-routes {:entity-key :exercise-set,
                    :entity-str "exercise-set",
                    :plural-str "exercise sets",
                    :schema     schema}))
