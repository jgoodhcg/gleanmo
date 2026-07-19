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

;; Specific body areas, normalized from the Airtable pain log's area list.
(def body-area-enum
  [:enum
   :head :face :jaw :eye :nostril :neck :upper-trap :shoulder :pec :chest
   :sternum :ribs :thoracic :lumbar :si-joint :tailbone :abdomen :stomach
   :oblique :hip :hip-flexor :groin :adductor :glute :quad :knee :shin :calf
   :top-of-foot :bottom-of-foot :toes :bicep :elbow :forearm :wrist :hand
   :finger :thumb :leg :other])

(def side-enum
  [:enum :left :right :both])

(def qualifier-enum
  [:enum
   ;; temporal / course
   :constant :intermittent :worsening :improving :radiating
   ;; sensation descriptors (normalized from Airtable pain types)
   :sharp :dull :throbbing :tight :stiff :sore :ache :pinch :squeeze :kink
   :burn :stabbing :sensitive :tingling :numb :cramp :pressure :swollen
   :trembling :tense :flutter :bloating :doms
   :other])

(def symptom-episode
  (-> [:map {:closed true}
       [:xt/id :symptom-episode/id]
       [::sm/type [:enum :symptom-episode]]
       [::sm/deleted-at {:optional true} :instant]
       [::sm/created-at :instant]
       [:user/id :user/id]
       [:symptom-episode/label {:optional true} :string]
       [:symptom-episode/beginning :instant]
       [:symptom-episode/end {:optional true} :instant]
       [:symptom-episode/overall-severity {:optional true} severity-enum]
       [:symptom-episode/overall-impact {:optional true} impact-enum]
       [:symptom-episode/notes {:optional true} :string]
       [:airtable/ported-at {:optional true} :instant]]
      (concat sm/legacy-meta)
      vec))

(def symptom-log
  (-> [:map {:closed true}
       [:xt/id :symptom-log/id]
       [::sm/type [:enum :symptom-log]]
       [::sm/deleted-at {:optional true} :instant]
       [::sm/created-at :instant]
       [:user/id :user/id]
       [:symptom-log/episode-id
        {:optional true :crud/label "Episode" :crud/inline-create true}
        :symptom-episode/id]
       [:symptom-log/timestamp :instant]
       [:symptom-log/type {:crud/priority 1} symptom-type-enum]
       [:symptom-log/severity {:crud/priority 2} severity-enum]
       ;; Numeric rating, 0.5-10 (Airtable pain scale used 0.5-8)
       [:symptom-log/severity-score {:optional true} :number]
       [:symptom-log/location {:optional true} body-location-enum]
       [:symptom-log/areas {:optional true :crud/priority 3} [:set body-area-enum]]
       [:symptom-log/side {:optional true :crud/priority 4} side-enum]
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
       [:symptom-log/qualifiers {:optional true} [:set qualifier-enum]]
       [:airtable/original-rating {:optional true} :string]
       [:airtable/original-areas {:optional true} :string]
       [:airtable/original-type {:optional true} :string]
       [:airtable/id {:optional true} :string]
       [:airtable/created-time {:optional true} :instant]
       [:airtable/ported-at {:optional true} :instant]]
      (concat sm/legacy-meta)
      vec))
