(ns tech.jgood.gleanmo.app.bm-log
  (:require
   [tech.jgood.gleanmo.crud.routes :as crud]
   [tech.jgood.gleanmo.schema :refer [schema]]))

(def crud-routes
  (crud/gen-routes {:entity-key :bm-log,
                    :entity-str "bm-log",
                    :plural-str "bm logs",
                    :schema     schema}))