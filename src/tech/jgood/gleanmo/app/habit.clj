(ns tech.jgood.gleanmo.app.habit
  (:require
   [tech.jgood.gleanmo.crud.routes :as crud]
   [tech.jgood.gleanmo.schema      :refer [schema]]))

(def crud-routes
  (crud/gen-routes {:entity-key :habit,
                    :entity-str "habit",
                    :plural-str "habits",
                    :schema     schema}))


