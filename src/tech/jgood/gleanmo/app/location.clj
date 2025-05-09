(ns tech.jgood.gleanmo.app.location
  (:require
   [tech.jgood.gleanmo.crud.routes :as crud]
   [tech.jgood.gleanmo.schema      :refer [schema]]))

(def crud-routes
  (crud/gen-routes {:entity-key :location,
                    :entity-str "location",
                    :plural-str "locations",
                    :schema     schema}))

