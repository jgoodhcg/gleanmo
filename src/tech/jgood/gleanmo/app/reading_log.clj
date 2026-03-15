(ns tech.jgood.gleanmo.app.reading-log
  (:require
   [tech.jgood.gleanmo.crud.routes :as crud]
   [tech.jgood.gleanmo.schema :refer [schema]]
   [tech.jgood.gleanmo.schema.reading-schema :as rs]
   [tech.jgood.gleanmo.timer.routes :as timer-routes]
   [tech.jgood.gleanmo.viz.routes :as viz-routes]))

(def crud-routes
  (crud/gen-routes {:entity-key :reading-log,
                    :entity-str "reading-log",
                    :plural-str "reading logs",
                    :schema     schema}))

(def viz-routes
  (viz-routes/gen-routes {:entity-key    :reading-log
                          :entity-schema rs/reading-log
                          :entity-str    "reading-log"
                          :plural-str    "reading-logs"}))

(def timer-routes
  (timer-routes/gen-routes {:entity-key    :reading-log
                            :entity-str    "reading-log"
                            :entity-schema rs/reading-log
                            :schema-map    schema}))
