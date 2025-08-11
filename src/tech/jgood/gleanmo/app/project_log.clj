(ns tech.jgood.gleanmo.app.project-log
  (:require
   [tech.jgood.gleanmo.crud.routes :as crud]
   [tech.jgood.gleanmo.schema :refer [schema]]))

(def crud-routes
  (crud/gen-routes {:entity-key :project-log
                    :entity-str "project-log"
                    :plural-str "project logs"
                    :schema     schema}))