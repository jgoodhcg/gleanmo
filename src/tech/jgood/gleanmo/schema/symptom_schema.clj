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
   :pain
   :chronic-pain
   :other])

(def severity-enum
  [:enum :mild :moderate :severe])

(def impact-enum
  [:enum :low :medium :high])

(def body-location-enum
  [:enum :head-face :throat-neck :chest :abdomen :pelvis :limbs :generalized :other])

(def symptom-episode
  (-> [:map {:closed true}
       [:xt/id :symptom-episode/id]
       [::sm/type [:enum :symptom-episode]]
       [::sm/deleted-at {:optional true} :instant]
       [::sm/created-at :instant]
       [:user/id :user/id]
       [:symptom-episode/beginning :instant]
       [:symptom-episode/end {:optional true} :instant]
       [:symptom-episode/overall-severity {:optional true} severity-enum]
       [:symptom-episode/overall-impact {:optional true} impact-enum]
       [:symptom-episode/notes {:optional true} :string]]
      (concat sm/legacy-meta)
      vec))

(def symptom-log
  (-> [:map {:closed true}
       [:xt/id :symptom-log/id]
       [::sm/type [:enum :symptom-log]]
       [::sm/deleted-at {:optional true} :instant]
       [::sm/created-at :instant]
       [:user/id :user/id]
       [:symptom-log/episode-id {:optional true} :symptom-episode/id]
       [:symptom-log/timestamp :instant]
       [:symptom-log/type {:crud/priority 1} symptom-type-enum]
       [:symptom-log/severity {:crud/priority 2} severity-enum]
       [:symptom-log/severity-score {:optional true} :int]
       [:symptom-log/location {:optional true} body-location-enum]
       [:symptom-log/location-notes {:optional true} :string]
       [:symptom-log/trigger {:optional true} :string]
       [:symptom-log/treatment {:optional true} :string]
       [:symptom-log/temp {:optional true} :double]
       [:symptom-log/temp-unit {:optional true} [:enum :celsius :fahrenheit]]
       [:symptom-log/heart-rate {:optional true} :int]
       [:symptom-log/bp-systolic {:optional true} :int]
       [:symptom-log/bp-diastolic {:optional true} :int]
       [:symptom-log/spo2 {:optional true} :int]
       [:symptom-log/notes {:optional true} :string]
       [:symptom-log/qualifiers {:optional true} [:set :keyword]]
       [:airtable/id {:optional true} :string]
       [:airtable/created-time {:optional true} :instant]
       [:airtable/ported-at {:optional true} :instant]]
      (concat sm/legacy-meta)
      vec))
