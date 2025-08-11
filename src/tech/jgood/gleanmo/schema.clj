(ns tech.jgood.gleanmo.schema
  (:require [tick.core :as t]
            [tech.jgood.gleanmo.schema.meta :as sm]
            [tech.jgood.gleanmo.schema.location-schema :as ls]
            [tech.jgood.gleanmo.schema.user-schema :as us]
            [tech.jgood.gleanmo.schema.habit-schema :as hs]
            [tech.jgood.gleanmo.schema.ical-url-schema :as is]
            [tech.jgood.gleanmo.schema.meditation-schema :as ms]
            [tech.jgood.gleanmo.schema.exercise-schema :as es]
            [tech.jgood.gleanmo.schema.bm-schema :as bs]
            [tech.jgood.gleanmo.schema.medication-schema :as meds]
            [tech.jgood.gleanmo.schema.project-schema :as ps]
            [tech.jgood.gleanmo.schema.cruddy :as cs]))

;; all glenamo/type attributes are the schema key
(def schema
  {:instant             [:fn t/instant?]
   :number              [:fn number?]
   :float               [:fn float?]
   :user/id             :uuid
   :habit/id            :uuid
   :habit-log/id        :uuid
   :exercise/id         :uuid
   :exercise-log/id     :uuid
   :exercise-session/id :uuid
   :location/id         :uuid
   :meditation/id       :uuid
   :meditation-log/id   :uuid
   :bm-log/id           :uuid
   :medication/id       :uuid
   :medication-log/id   :uuid
   :project/id          :uuid
   :project-log/id      :uuid
   :ical-url/id         :uuid
   :user                us/user
   :habit               hs/habit
   :habit-log           hs/habit-log
   :location            ls/location
   :meditation          ms/meditation
   :meditation-log      ms/meditation-log
   :bm-log              bs/bm-log
   :medication          meds/medication
   :medication-log      meds/medication-log
   :project             ps/project
   :project-log         ps/project-log
   :ical-url            is/ical-url
   :exercise            es/exercise
   :exercise-session    es/exercise-session
   :exercise-log        es/exercise-log
   :exercise-set        es/exercise-set
   :cruddy              cs/cruddy})

(def module
  {:schema schema})
