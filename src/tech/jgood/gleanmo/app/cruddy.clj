(ns tech.jgood.gleanmo.app.cruddy
  (:require
   [tech.jgood.gleanmo.crud.routes :as crud]
   [tech.jgood.gleanmo.schema :refer [schema]]))

(def crud-routes
  (crud/gen-routes {:entity-key :cruddy
                    :plural-str "cruddies"
                    :schema     schema}))
