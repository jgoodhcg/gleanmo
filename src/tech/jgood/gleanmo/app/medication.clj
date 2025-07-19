(ns tech.jgood.gleanmo.app.medication
  (:require
   [tech.jgood.gleanmo.crud.routes :as crud]
   [tech.jgood.gleanmo.schema :refer [schema]]))

(def crud-routes
  (crud/gen-routes {:entity-key :medication,
                    :entity-str "medication",
                    :plural-str "medications",
                    :schema     schema}))