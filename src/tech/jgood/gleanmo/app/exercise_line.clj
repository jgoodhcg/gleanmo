(ns tech.jgood.gleanmo.app.exercise-line
  (:require
   [tech.jgood.gleanmo.crud.routes :as crud]
   [tech.jgood.gleanmo.schema      :refer [schema]]))

(def crud-routes
  (crud/gen-routes {:entity-key :exercise-line,
                    :entity-str "exercise-line",
                    :plural-str "exercise lines",
                    :schema     schema}))
