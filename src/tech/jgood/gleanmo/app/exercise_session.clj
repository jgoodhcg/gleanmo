(ns tech.jgood.gleanmo.app.exercise-session
  (:require
   [tech.jgood.gleanmo.crud.routes :as crud]
   [tech.jgood.gleanmo.schema :refer [schema]]
   [tech.jgood.gleanmo.schema.exercise-schema :as exercise-schema]
   [tech.jgood.gleanmo.viz.routes :as viz-routes]))

(def crud-routes
  (crud/gen-routes {:entity-key :exercise-session,
                    :entity-str "exercise-session",
                    :plural-str "exercise sessions",
                    :schema     schema}))

(def viz-routes
  (viz-routes/gen-routes {:entity-key :exercise-session
                          :entity-schema exercise-schema/exercise-session
                          :entity-str "exercise-session"
                          :plural-str "exercise-sessions"}))
