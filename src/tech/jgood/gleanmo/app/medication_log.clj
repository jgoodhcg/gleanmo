(ns tech.jgood.gleanmo.app.medication-log
  (:require
   [tech.jgood.gleanmo.crud.routes :as crud]
   [tech.jgood.gleanmo.schema :refer [schema]]
   [tech.jgood.gleanmo.schema.medication-schema :as med-schema]
   [tech.jgood.gleanmo.viz.routes :as viz-routes]))

(def crud-routes
  (crud/gen-routes {:entity-key :medication-log,
                    :entity-str "medication-log",
                    :plural-str "medication logs",
                    :schema     schema}))

;; Generate visualization routes
(def viz-routes
  (viz-routes/gen-routes {:entity-key :medication-log
                          :entity-schema med-schema/medication-log
                          :entity-str "medication-log"
                          :plural-str "medication-logs"}))