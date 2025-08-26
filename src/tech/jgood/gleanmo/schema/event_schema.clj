(ns tech.jgood.gleanmo.schema.event-schema
  (:require [tech.jgood.gleanmo.schema.meta :as sm]))

(def event
  "Schema for calendar events - compatible with iCal/RFC5545 standard"
  [:map {:closed true}
   [:xt/id :event/id]
   [::sm/type [:enum :event]]
   [::sm/deleted-at {:optional true} :instant]
   [::sm/created-at :instant]
   [:user/id :user/id]
   
   ;; Core event properties
   [:event/label :string] ; Primary event title/name (required)
   [:event/summary {:optional true} :string] ; SUMMARY in iCal - for sync compatibility
   [:event/dtstart {:optional true} :instant] ; DTSTART in iCal - event start date/time
   [:event/dtend {:optional true} :instant] ; DTEND in iCal - event end date/time
   [:event/uid {:optional true} :string] ; UID in iCal - unique identifier for sync
   
   ;; Additional properties
   [:event/description {:optional true} :string] ; DESCRIPTION in iCal
   [:event/location {:optional true} :string] ; LOCATION in iCal
   [:event/time-zone {:optional true} :string] ; Timezone for the event
   [:event/all-day {:optional true} :boolean] ; All-day event flag
   
   ;; App-specific properties
   [:event/source {:optional true} [:enum :big-calendar :ical-sync :google-sync]] ; Event source
   [:event/category {:optional true} [:enum :vacation :travel :personal :work :other]]
   [:event/color {:optional true} :string] ; Hex color for display
   
   ;; Sync properties
   [:event/ical-source {:optional true} :ical-url/id] ; Reference to source iCal URL
   [:event/external-id {:optional true} :string] ; External calendar system ID
   [:event/last-synced {:optional true} :instant] ; Last sync timestamp
   ])