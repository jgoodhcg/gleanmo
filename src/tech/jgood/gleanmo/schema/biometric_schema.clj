(ns tech.jgood.gleanmo.schema.biometric-schema
  "Biometric readings — see roadmap/056-cronometer-integration.md, Data Model.

   Provider-neutral: any source of readings translates into `biometric-log`.
   Each provider adds its own optional namespaced attributes (`:cronometer/*`
   today) for identity and lineage; none is required. No CRUD routes yet —
   imports write these documents and the app does not edit them."
  (:require
   [tech.jgood.gleanmo.schema.cronometer-schema :as crono]
   [tech.jgood.gleanmo.schema.meta :as sm]))

(def biometric-log
  ;; Flat typed fields per supported metric (see `cronometer.biometrics`). A
  ;; reading of an unmapped metric gets only `label` and raw. Field names match
  ;; `symptom-log`'s vitals so the values are comparable.
  (-> [:map {:closed true}
       [:xt/id :biometric-log/id]
       [::sm/type [:enum :biometric-log]]
       [::sm/deleted-at {:optional true} :instant]
       [::sm/created-at :instant]
       [:user/id :user/id]
       [:biometric-log/timestamp :instant]
       [:biometric-log/timestamp-basis {:optional true}
        [:enum :logged :wake-time :day-start]]
       ;; The provider's metric name
       [:biometric-log/label :string]
       ;; "Oura"; absent = entered by hand
       [:biometric-log/source {:optional true} :string]
       [:biometric-log/weight {:optional true} :number]
       [:biometric-log/weight-unit {:optional true} [:enum :lbs :kg :st]]
       [:biometric-log/body-fat {:optional true} :number]
       [:biometric-log/body-fat-unit {:optional true} [:enum :percent :kg :lbs]]
       [:biometric-log/bp-systolic {:optional true} :number]
       [:biometric-log/bp-systolic-unit {:optional true} [:enum :mmhg]]
       [:biometric-log/bp-diastolic {:optional true} :number]
       [:biometric-log/bp-diastolic-unit {:optional true} [:enum :mmhg]]
       [:biometric-log/blood-glucose {:optional true} :number]
       [:biometric-log/blood-glucose-unit {:optional true} [:enum :mg-dl :mmol-l]]
       [:biometric-log/heart-rate {:optional true} :number]
       [:biometric-log/heart-rate-unit {:optional true} [:enum :bpm]]
       [:biometric-log/hrv {:optional true} :number]
       [:biometric-log/hrv-unit {:optional true} [:enum :ms]]
       [:biometric-log/respiration-rate {:optional true} :number]
       [:biometric-log/respiration-rate-unit {:optional true} [:enum :brpm]]
       [:biometric-log/sleep {:optional true} :number]
       [:biometric-log/sleep-unit {:optional true} [:enum :hours]]
       [:biometric-log/sleep-score {:optional true} :number]
       [:biometric-log/sleep-score-unit {:optional true} [:enum :percent]]
       [:biometric-log/recovery {:optional true} :number]
       [:biometric-log/recovery-unit {:optional true} [:enum :percent]]
       [:biometric-log/temp-variation {:optional true} :number]
       [:biometric-log/temp-variation-unit {:optional true}
        [:enum :celsius :fahrenheit :kelvin]]
       [:cronometer/biometric-id {:optional true} :int]
       [:cronometer/metric-id {:optional true} :int]
       [:cronometer/unit-id {:optional true} :int]
       [:cronometer/external-id {:optional true} :string]]
      (into crono/lineage)))
