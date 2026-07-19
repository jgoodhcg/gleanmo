(ns tech.jgood.gleanmo.app.boulder-problem
  (:require
   [tech.jgood.gleanmo.crud.routes :as crud]
   [tech.jgood.gleanmo.schema      :refer [schema]]))

(def crud-routes
  (crud/gen-routes {:entity-key :boulder-problem,
                    :entity-str "boulder-problem",
                    :plural-str "boulder problems",
                    :schema     schema}))
