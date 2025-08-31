(ns tech.jgood.gleanmo.app.project-log
  (:require
   [tech.jgood.gleanmo.crud.routes :as crud]
   [tech.jgood.gleanmo.viz.routes :as viz-routes]
   [tech.jgood.gleanmo.timer.routes :as timer-routes]
   [tech.jgood.gleanmo.schema      :refer [schema]]
   [tech.jgood.gleanmo.schema.project-schema :as ps]))

(def crud-routes
  (crud/gen-routes {:entity-key :project-log,
                    :entity-str "project-log",
                    :plural-str "project-logs",
                    :schema     schema}))

(def viz-routes
  (viz-routes/gen-routes {:entity-key :project-log
                          :entity-schema ps/project-log
                          :entity-str "project-log"
                          :plural-str "project-logs"}))

(def timer-routes
  (timer-routes/gen-routes {:entity-key    :project-log
                            :entity-str    "project-log"
                            :entity-schema ps/project-log
                            :schema-map    schema}))
