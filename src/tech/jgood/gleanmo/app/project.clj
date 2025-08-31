(ns tech.jgood.gleanmo.app.project
  (:require
   [tech.jgood.gleanmo.crud.routes :as crud]
   [tech.jgood.gleanmo.schema      :refer [schema]]))

(def crud-routes
  (crud/gen-routes {:entity-key :project,
                    :entity-str "project",
                    :plural-str "projects",
                    :schema     schema}))