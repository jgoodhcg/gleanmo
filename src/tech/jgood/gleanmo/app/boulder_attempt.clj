(ns tech.jgood.gleanmo.app.boulder-attempt
  (:require
   [tech.jgood.gleanmo.crud.routes :as crud]
   [tech.jgood.gleanmo.schema      :refer [schema]]))

(def crud-routes
  (crud/gen-routes {:entity-key :boulder-attempt,
                    :entity-str "boulder-attempt",
                    :plural-str "boulder attempts",
                    :schema     schema}))
