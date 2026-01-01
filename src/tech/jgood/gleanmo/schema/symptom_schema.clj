(ns tech.jgood.gleanmo.schema.symptom-schema
  (:require
   [tech.jgood.gleanmo.schema.meta :as sm]))

(def symptom-type-enum
  [:enum
   :fever
   :chills
   :night-sweats
   :sore-throat
   :headache
   :nausea
   :cough
   :fatigue
   :myalgia
   :congestion
   :shortness-of-breath
   :other])

(def severity-enum
  [:enum :mild :moderate :severe])

(def location-enum
  [:enum :head-face :throat-neck :chest :abdomen :pelvis :limbs :generalized :other])

(def value-unit-enum
  [:enum :celsius :fahrenheit :bpm :mmhg :percent])

(def symptom-episode
  (-> [:map {:closed true}
       [:xt/id :symptom-episode/id]
       [::sm/type [:enum :symptom-episode]]
       [::sm/deleted-at {:optional true} :instant]
       [::sm/created-at :instant]
       [:user/id :user/id]

       ;; Timing & context
       [:symptom-episode/beginning :instant]
       [:symptom-episode/end {:optional true} :instant]
       [:symptom-episode/notes {:optional true} :string]
       [:symptom-episode/overall-severity {:optional true} severity-enum]
       [:symptom-episode/overall-impact {:optional true} [:enum :low :medium :high]]
       [:symptom-episode/location {:optional true} location-enum]
       [:symptom-episode/location-notes {:optional true} :string]

       ;; Vitals (all optional)
       [:symptom-episode/temp-value {:optional true} :double]
       [:symptom-episode/temp-unit {:optional true} [:enum :celsius :fahrenheit]]
       [:symptom-episode/heart-rate-bpm {:optional true} :int]
       [:symptom-episode/bp-systolic {:optional true} :int]
       [:symptom-episode/bp-diastolic {:optional true} :int]
       [:symptom-episode/spo2-percent {:optional true} :int]

       ;; Relationships
       [:symptom-episode/pain-log-id {:optional true} :pain-log/id]
       [:symptom-episode/medication-log-ids {:optional true} [:set :medication-log/id]]
       [:symptom-episode/bm-log-id {:optional true} :bm-log/id]
       [:symptom-episode/calendar-event-id {:optional true} :calendar-event/id]

       ;; Optional reverse link for convenience
       [:symptom-episode/log-ids {:optional true} [:set :symptom-log/id]]]
      (concat sm/legacy-meta)
      vec))

(def symptom-log
  (-> [:map {:closed true}
       [:xt/id :symptom-log/id]
       [::sm/type [:enum :symptom-log]]
       [::sm/deleted-at {:optional true} :instant]
       [::sm/created-at :instant]
       [:user/id :user/id]

       ;; Parent episode
       [:symptom-episode/id :symptom-episode/id]

       ;; Core symptom data
       [:symptom-log/type symptom-type-enum]
       [:symptom-log/severity {:optional true} severity-enum]
       [:symptom-log/severity-score {:optional true} :int] ;; optional 0–10
       [:symptom-log/beginning {:optional true} :instant]
       [:symptom-log/end {:optional true} :instant]
       [:symptom-log/location {:optional true} location-enum]
       [:symptom-log/location-notes {:optional true} :string]
       [:symptom-log/value {:optional true} :double]
       [:symptom-log/value-unit {:optional true} value-unit-enum]
       [:symptom-log/notes {:optional true} :string]
       [:symptom-log/qualifiers {:optional true} [:set :keyword]]

       ;; Cross-links
       [:symptom-log/pain-log-id {:optional true} :pain-log/id]

       ;; Lineage (only on logs)
       [:airtable/id {:optional true} :string]
       [:airtable/created-time {:optional true} :instant]
       [:airtable/ported-at {:optional true} :instant]]
      (concat sm/legacy-meta)
      vec))
