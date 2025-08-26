(ns tech.jgood.gleanmo.schema.calendar-event-schema
  (:require
   [tech.jgood.gleanmo.schema.meta :as sm]))

(def calendar-event
  "Schema for calendar events - compatible with iCal/RFC5545 standard"
  [:map {:closed true}
   [:xt/id :calendar-event/id]
   [::sm/type [:enum :calendar-event]]
   [::sm/deleted-at {:optional true} :instant]
   [::sm/created-at :instant]
   [:user/id :user/id]
   [:calendar-event/label :string]
   [:calendar-event/source [:enum :gleanmo]]
   [:calendar-event/summary {:optional true} :string]
   [:calendar-event/beginning {:optional true} :instant]
   [:calendar-event/end {:optional true} :instant]
   [:calendar-event/description {:optional true} :string]
   [:calendar-event/time-zone {:optional true} :string]
   [:calendar-event/all-day {:optional true} :boolean]
   [:calendar-event/sensitive {:optional true} :boolean]
   [:calendar-event/color-neon {:optional true}
    [:enum :blue :cyan :green :violet :red :orange]]])
