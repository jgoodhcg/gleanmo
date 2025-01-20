(ns tech.jgood.gleanmo.schema
  (:require [tick.core :as t]
            [tech.jgood.gleanmo.schema.meta :as sm]
            [tech.jgood.gleanmo.schema.location-schema :as ls]
            [tech.jgood.gleanmo.schema.user-schema :as us]
            [tech.jgood.gleanmo.schema.habit-schema :as hs]
            [tech.jgood.gleanmo.schema.ical-url-schema :as is]
            [tech.jgood.gleanmo.schema.meditation-schema :as ms]
            [tech.jgood.gleanmo.schema.exercise-schema :as es]))

;; all glenamo/type attributes are the schema key
(def schema
  {:instant [:fn t/instant?]
   :user/id :uuid
   :habit/id :uuid
   :habit-log/id :uuid
   :exercise/id :uuid
   :exercise-log/id :uuid
   :exercise-session/id :uuid
   :location/id :uuid
   :meditation/id :uuid
   :meditation-log/id :uuid
   :ical-url/id :uuid
   :user us/user
   :habit hs/habit
   :habit-log hs/habit-log
   :location ls/location
   :meditation ms/meditation
   :meditation-log ms/meditation-log
   :ical-url is/ical-url
   :exercise es/exercise
   :exercise-session es/exercise-session
   :exercise-log es/exercise-log
   :exercise-set es/exercise-set
   :cruddy (-> [:map {:closed true}
                [:xt/id :uuid]
                [::sm/type [:enum :cruddy]]
                [::sm/deleted-at {:optional true} :boolean]
                [::sm/created-at :instant]
                [:cruddy/label :string]
                [:cruddy/num :number]
                [:cruddy/bool :boolean]
                [:cruddy/integer :int]
                [:cruddy/single-relation :habit/id]
                [:cruddy/set-relation [:set :habit/id]]
                [:cruddy/enum [:enum :a :b :c]]
                [:cruddy/timestamp :instant]
                [:cruddy/float {:optional true} :float]]
               (concat sm/legacy-meta)
               vec)})

(def module
  {:schema schema})
