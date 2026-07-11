(ns tech.jgood.gleanmo.schema.mood-schema
  (:require
   [tech.jgood.gleanmo.schema.meta :as sm]))

;; Circumplex-model dimensions: enum positions map to 1-5 for analysis.
(def valence-enum
  [:enum :very-unpleasant :unpleasant :neutral :pleasant :very-pleasant])

(def arousal-enum
  [:enum :very-low :low :moderate :high :very-high])

(def stress-enum
  [:enum :very-low :low :moderate :high :very-high])

(def tag-enum
  [:enum :social :work :health :family :travel :other])

(def mood-log
  (-> [:map {:closed true}
       [:xt/id :mood-log/id]
       [::sm/type [:enum :mood-log]]
       [::sm/deleted-at {:optional true} :instant]
       [::sm/created-at :instant]
       [:user/id :user/id]
       [:mood-log/timestamp :instant]
       [:mood-log/valence
        {:crud/priority 1
         :crud/label "Mood (valence)"
         :crud/description "How pleasant vs. unpleasant you feel overall."}
        valence-enum]
       [:mood-log/arousal
        {:optional true
         :crud/priority 2
         :crud/label "Energy (arousal)"
         :crud/description
         "How activated/energized you feel — independent of whether the mood is good or bad."}
        arousal-enum]
       [:mood-log/stress
        {:optional true
         :crud/priority 3
         :crud/description "Current sense of stress or anxiety."}
        stress-enum]
       [:mood-log/tags {:optional true :crud/priority 4} [:set tag-enum]]
       [:mood-log/notes {:optional true :crud/priority 5} :string]
       [:airtable/id {:optional true} :string]
       [:airtable/created-time {:optional true} :instant]
       [:airtable/ported-at {:optional true} :instant]]
      (concat sm/legacy-meta)
      vec))
