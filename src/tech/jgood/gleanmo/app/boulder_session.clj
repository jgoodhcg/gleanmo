(ns tech.jgood.gleanmo.app.boulder-session
  (:require
   [tech.jgood.gleanmo.crud.routes :as crud]
   [tech.jgood.gleanmo.schema :refer [schema]]
   [tech.jgood.gleanmo.schema.bouldering-schema :as bouldering-schema]
   [tech.jgood.gleanmo.viz.routes :as viz-routes]))

(def crud-routes
  (crud/gen-routes {:entity-key :boulder-session,
                    :entity-str "boulder-session",
                    :plural-str "boulder sessions",
                    :schema     schema}))

(def viz-routes
  (viz-routes/gen-routes {:entity-key :boulder-session
                          :entity-schema bouldering-schema/boulder-session
                          :entity-str "boulder-session"
                          :plural-str "boulder-sessions"}))
