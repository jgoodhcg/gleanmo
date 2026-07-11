(ns tech.jgood.gleanmo.app.mood-log
  (:require
   [tech.jgood.gleanmo.crud.routes :as crud]
   [tech.jgood.gleanmo.schema :refer [schema]]
   [tech.jgood.gleanmo.schema.mood-schema :as mood-schema]
   [tech.jgood.gleanmo.viz.routes :as viz-routes]))

(def crud-routes
  (crud/gen-routes {:entity-key :mood-log,
                    :entity-str "mood-log",
                    :plural-str "mood logs",
                    :schema     schema}))

(def viz-routes
  (viz-routes/gen-routes {:entity-key :mood-log
                          :entity-schema mood-schema/mood-log
                          :entity-str "mood-log"
                          :plural-str "mood-logs"}))
