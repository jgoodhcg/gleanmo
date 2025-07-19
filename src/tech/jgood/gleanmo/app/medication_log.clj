(ns tech.jgood.gleanmo.app.medication-log
  (:require
   [tech.jgood.gleanmo.crud.routes :as crud]
   [tech.jgood.gleanmo.schema :refer [schema]]))

(def crud-routes
  (crud/gen-routes {:entity-key :medication-log,
                    :entity-str "medication-log",
                    :plural-str "medication logs",
                    :schema     schema}))