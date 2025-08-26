(ns tech.jgood.gleanmo.app.calendar-event
  (:require
   [tech.jgood.gleanmo.crud.routes :as crud]
   [tech.jgood.gleanmo.schema      :refer [schema]]))

(def crud-routes
  (crud/gen-routes {:entity-key :calendar-event,
                    :entity-str "calendar-event",
                    :plural-str "calendar-events",
                    :schema     schema}))