(ns tech.jgood.gleanmo.app.ical-url
  (:require
   [tech.jgood.gleanmo.crud.routes :as crud]
   [tech.jgood.gleanmo.schema :refer [schema]]))

(def crud-routes
  (crud/gen-routes {:entity-key :ical-url
                    :entity-str "ical-url"
                    :plural-str "iCal URLs"
                    :schema     schema}))

