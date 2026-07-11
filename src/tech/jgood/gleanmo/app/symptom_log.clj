(ns tech.jgood.gleanmo.app.symptom-log
  (:require
   [tech.jgood.gleanmo.crud.routes :as crud]
   [tech.jgood.gleanmo.schema :refer [schema]]
   [tech.jgood.gleanmo.schema.symptom-schema :as symptom-schema]
   [tech.jgood.gleanmo.viz.routes :as viz-routes]))

(def crud-routes
  (crud/gen-routes {:entity-key :symptom-log,
                    :entity-str "symptom-log",
                    :plural-str "symptom logs",
                    :schema     schema}))

(def viz-routes
  (viz-routes/gen-routes {:entity-key :symptom-log
                          :entity-schema symptom-schema/symptom-log
                          :entity-str "symptom-log"
                          :plural-str "symptom-logs"}))
