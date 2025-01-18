(ns tech.jgood.gleanmo.schema.exercise-schema
  (:require [tech.jgood.gleanmo.schema.meta :as sm]))

(def exercise
  [:map {:closed true}
   [:xt/id :exercise/id]
   [:sm/type [:enum :habit-log]]
   [:sm/deleted-at {:optional true} :instant]
   [:sm/created-at :instant]
   [:user/id :user/id]
   [:exercise/label :string]
   [:exercise/source {:optional true} :string]
   [:exercise/notes {:optional true} :string]
   [:airtable/exercise-log {:optional true} :string]
   [:airtable/log-count {:optional true} :instant]
   [:airtable/id {:optional true} :string]
   [:airtable/ported {:optional true} :boolean]
   [:airtable/created-time {:optional true} :instant]])

(def exercise-session
  [:map {:closed true}
   [:xt/id :exercise-session/id]
   [:sm/type [:enum :exercise-session]]
   [:sm/deleted-at {:optional true} :instant]
   [:sm/created-at :instant]
   [:user/id :user/id]
   [:exercise-session/beginning :instant]
   [:exercise-session/end {:optional true} :instant]
   [:exercise-session/notes {:optional true} :string]])

(def exercise-log
  [:map {:closed true}
   [:xt/id :uuid]
   [:sm/type [:enum :exercise-log]]
   [:sm/deleted-at {:optional true} :instant]
   [:sm/created-at :instant]
   [:user/id :user/id]
   [:exercise-session/id :exercise-session/id]
   [:exercise-log.interval/beginning :instant]
   [:exercise-log.interval/end {:optional true} :instant]
   [:exercise-log.interval/global-median-end {:optional true} :boolean]
   [:exercise-log/notes {:optional true} :string]
   [:airtable/ported {:optional true} :boolean]
   [:airtable/missing-duration {:optional true} :number]])

(def exercise-set
  [:map {:closed true}
   [:xt/id :uuid]
   [:sm/type [:enum :exercise-set]]
   [:sm/deleted-at {:optional true} :instant]
   [:sm/created-at :instant]
   [:user/id :user/id]
   [:exercise/id :exercise/id]
   [:exercise-log/id :exercise-log/id]
   [:exercise-set.interval/beginning :instant]
   [:exercise-set.interval/end {:optional true} :instant]
   [:exercise-set/distance {:optional true} :number]
   [:exercise-set/distance-unit {:optional true} :string]
   [:exercise-set/weight-unit {:optional true} :string]
   [:exercise-set/reps {:optional true} :int]
   [:exercise-set/weight-amount {:optional true} :number]
   [:exercise-set/notes {:optional true} :string]
   [:exercise-set.interval/global-median-end {:optional true} :boolean]
   [:airtable/ported {:optional true} :boolean]
   [:airtable/exercise-id {:optional true} :string]
   [:airtable/missing-duration {:optional true} :number]])
