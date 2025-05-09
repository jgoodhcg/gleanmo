(ns tech.jgood.gleanmo.app.meditation
  (:require
   [tech.jgood.gleanmo.crud.routes :as crud]
   [tech.jgood.gleanmo.schema :refer [schema]]))

(def crud-routes
  (crud/gen-routes {:entity-key :meditation
                    :entity-str "meditation"
                    :plural-str "meditations"
                    :schema     schema}))

