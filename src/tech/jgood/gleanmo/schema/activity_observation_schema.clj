(ns tech.jgood.gleanmo.schema.activity-observation-schema
  "Tracker-reported activity observations, separate from exercise sessions.
   Import and UI support are pending; registering this schema creates no data."
  (:require
   [tech.jgood.gleanmo.schema.meta :as sm]))

(def activity-observation
  "A tracker observation: a timed session, daily aggregate, or unknown scope.
   Original measurements remain in source lineage rather than generic fields."
  [:map {:closed true}
   [:xt/id :activity-observation/id]
   [::sm/type [:enum :activity-observation]]
   [::sm/deleted-at {:optional true} :instant]
   [::sm/created-at :instant]
   [:user/id :user/id]
   [:activity-observation/label :string]
   [:activity-observation/source {:optional true} :string]
   [:activity-observation/scope [:enum :session :daily :unknown]]
   ;; Date-only observations follow nutrition-day's specific precedent.
   [:activity-observation/day {:optional true} :local-date]
   ;; Timed activities follow exercise-session/exercise-set.
   [:activity-observation/beginning {:optional true} :instant]
   [:activity-observation/end {:optional true} :instant]
   ;; True when end was calculated from beginning + upstream minutes.
   [:activity-observation/auto-ended {:optional true} :boolean]
   [:activity-observation/exercise-session-id
    {:optional true} :exercise-session/id]
   ;; Import identity and lineage, following existing Cronometer schemas.
   [:cronometer/exercise-id {:optional true} :int]
   [:cronometer/external-id {:optional true} :string]
   [:cronometer/raw {:optional true :hide true} :string]
   [:cronometer/day {:optional true} :string]
   [:cronometer/time {:optional true} :string]
   [:cronometer/offset {:optional true} :int]
   [:cronometer/synced-at {:optional true} :instant]])
