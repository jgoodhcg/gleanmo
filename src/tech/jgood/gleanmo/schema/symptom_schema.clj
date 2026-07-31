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

;; Fibonacci-spaced severity. Labels are the Airtable pain scale's verbatim
;; single-select options; the numbers are not. Airtable numbered them
;; 0.5/1/2/3/5/8 — 1/2/3/5/8 with a 0.5 bolted on the bottom — so porting
;; shifts every label one position up the sequence to 1/2/3/5/8/13. Ratios
;; are near-identical either way (2, 1.5, 1.67, 1.6, 1.63), so the interval
;; spacing that makes the number worth storing survives the shift.
;; `:airtable/original-rating` keeps each imported record's source label, so
;; the old numbering stays auditable per record.
(def severity-scale
  [[1  "Barely there"]
   [2  "I feel it"]
   [3  "It's mild"]
   [5  "It's impactful"]
   [8  "It hurts"]
   [13 "I can't move"]])

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
  [:map {:closed true}
   [:xt/id :symptom-episode/id]
   [::sm/type [:enum :symptom-episode]]
   [::sm/deleted-at {:optional true} :instant]
   [::sm/created-at :instant]
   [:user/id :user/id]
   [:symptom-episode/label {:optional true} :string]
   [:symptom-episode/beginning :instant]
   [:symptom-episode/end {:optional true} :instant]
   [:symptom-episode/overall-impact {:optional true} impact-enum]
   [:symptom-episode/notes {:optional true} :string]
   [:airtable/ported-at {:optional true} :instant]])

(def symptom-log
  [:map {:closed true}
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
   ;; Optional because 58 of the 1,131 Airtable pain rows carry no rating. The
   ;; `severity` enum this replaced was required, so the ingester defaulted
   ;; those rows to :mild — inventing a severity that was never recorded.
   ;; Absent is the honest encoding.
   [:symptom-log/severity-score
    {:optional true
     :crud/priority 2
     :crud/label "Severity"
     :crud/description "How bad it is right now."
     :crud/scale severity-scale}
    :number]
   [:symptom-log/location {:optional true} body-location-enum]
   [:symptom-log/areas {:optional true :crud/priority 3} [:set body-area-enum]]
   [:symptom-log/side {:optional true :crud/priority 4} side-enum]
   [:symptom-log/location-notes {:optional true} :string]
   [:symptom-log/trigger {:optional true :crud/suggest-existing true} :string]
   [:symptom-log/treatment {:optional true :crud/suggest-existing true} :string]
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
   [:airtable/ported-at {:optional true} :instant]])
