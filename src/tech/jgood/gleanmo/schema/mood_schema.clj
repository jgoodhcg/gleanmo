(ns tech.jgood.gleanmo.schema.mood-schema
  (:require
   [tech.jgood.gleanmo.schema.meta :as sm]))

;; Circumplex-model dimensions (Russell 1980). Valence and arousal are the two
;; bipolar axes of a plane, so they are stored as signed coordinates centred on
;; neutral: quadrants fall out as sign pairs and distance from the origin is
;; intensity. That geometry needs evenly spaced steps, so these stay linear
;; rather than taking the Fibonacci spacing used for magnitude ratings.
;;
;; Published instruments (SAM, the Affect Grid, Warriner et al.) run 1-9
;; unipolar; -2..2 is a linear transform of that, so comparing against norms is
;; a rescale. Five points is deliberate — one self-report a few times a day does
;; not support nine, and the Plutchik mapping in dev/repl/airtable/mood.clj
;; places all 32 labels on this resolution.
(def valence-scale
  [[-2 "Very unpleasant"]
   [-1 "Unpleasant"]
   [0  "Neutral"]
   [1  "Pleasant"]
   [2  "Very pleasant"]])

(def arousal-scale
  [[-2 "Very low"]
   [-1 "Low"]
   [0  "Moderate"]
   [1  "High"]
   [2  "Very high"]])

;; Stress is not a third circumplex axis — it is an independent magnitude
;; rating, so it takes the Fibonacci spacing used elsewhere for perceived
;; intensity. Zero is an absence anchor below that run, not a scale point (a
;; geometric scale cannot contain zero); it exists because a mood entry gets
;; logged whether or not there is stress to report.
(def stress-scale
  [[0 "None"]
   [1 "Low"]
   [2 "Moderate"]
   [3 "High"]
   [5 "Very high"]
   [8 "Overwhelming"]])

(def tag-enum
  [:enum :social :work :health :family :travel :other])

(def mood-log
  [:map {:closed true}
   [:xt/id :mood-log/id]
   [::sm/type [:enum :mood-log]]
   [::sm/deleted-at {:optional true} :instant]
   [::sm/created-at :instant]
   [:user/id :user/id]
   [:mood-log/timestamp :instant]
   [:mood-log/valence
    {:crud/priority 1
     :crud/label "Mood (valence)"
     :crud/description "How pleasant vs. unpleasant you feel overall."
     :crud/scale valence-scale}
    :number]
   [:mood-log/arousal
    {:optional true
     :crud/priority 2
     :crud/label "Energy (arousal)"
     :crud/description
     "How activated/energized you feel — independent of whether the mood is good or bad."
     :crud/scale arousal-scale}
    :number]
   [:mood-log/stress
    {:optional true
     :crud/priority 3
     :crud/description "Current sense of stress or anxiety."
     :crud/scale stress-scale}
    :number]
   [:mood-log/tags {:optional true :crud/priority 4} [:set tag-enum]]
   [:mood-log/notes {:optional true :crud/priority 5} :string]
   [:airtable/id {:optional true} :string]
   [:airtable/created-time {:optional true} :instant]
   [:airtable/ported-at {:optional true} :instant]
   ;; Raw Airtable mood label (Plutchik wheel word + emoji) that the
   ;; valence/arousal pair was derived from.
   [:airtable/original-mood {:optional true} :string]])
