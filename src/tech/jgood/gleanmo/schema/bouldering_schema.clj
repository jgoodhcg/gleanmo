(ns tech.jgood.gleanmo.schema.bouldering-schema
  (:require
   [tech.jgood.gleanmo.schema.meta :as sm]))

(def rpe-enum
  [:enum :easy :moderate :hard :limit])

(def grade-enum
  [:enum :v0 :v1 :v2 :v3 :v4 :v5 :v6 :v7 :v8 :v9 :v10 :v11 :v12 :v13 :project])

(def attempt-tag-enum
  [:enum :dab :intentional-practice-dab :peel :bail :off-start :reversed
   :foot-slip])

(def boulder-session
  (-> [:map {:closed true}
       [:xt/id :boulder-session/id]
       [::sm/type [:enum :boulder-session]]
       [::sm/deleted-at {:optional true} :instant]
       [::sm/created-at :instant]
       [:user/id :user/id]
       [:boulder-session/label {:optional true} :string]
       [:boulder-session/beginning :instant]
       [:boulder-session/end {:optional true} :instant]
       [:boulder-session/gym {:crud/priority 1} :string]
       [:boulder-session/rpe {:optional true :crud/priority 2} rpe-enum]
       [:boulder-session/notes {:optional true :crud/priority 3} :string]
       [:airtable/id {:optional true} :string]
       [:airtable/created-time {:optional true} :instant]
       [:airtable/ported-at {:optional true} :instant]]
      (concat sm/legacy-meta)
      vec))

;; A specific problem at a gym (route/boulder). Mirrors the Airtable
;; "bouldering problems" table: circuit difficulty label, hold color, wall.
(def boulder-problem
  (-> [:map {:closed true}
       [:xt/id :boulder-problem/id]
       [::sm/type [:enum :boulder-problem]]
       [::sm/deleted-at {:optional true} :instant]
       [::sm/created-at :instant]
       [:user/id :user/id]
       [:boulder-problem/gym {:crud/priority 1} :string]
       ;; Circuit label as the gym presents it, e.g. "pink v0-v2"
       [:boulder-problem/difficulty {:crud/priority 2} :string]
       [:boulder-problem/hold-color {:optional true :crud/priority 3} :string]
       [:boulder-problem/wall {:optional true :crud/priority 4} :string]
       ;; Known/estimated V-grade when the circuit label isn't specific
       [:boulder-problem/grade {:optional true} grade-enum]
       [:boulder-problem/label {:optional true} :string]
       [:boulder-problem/archived {:optional true} :boolean]
       [:boulder-problem/notes {:optional true} :string]
       [:airtable/id {:optional true} :string]
       [:airtable/created-time {:optional true} :instant]
       [:airtable/ported-at {:optional true} :instant]
       [:airtable/original-problem-number {:optional true} :int]]
      (concat sm/legacy-meta)
      vec))

(def boulder-attempt
  (-> [:map {:closed true}
       [:xt/id :boulder-attempt/id]
       [::sm/type [:enum :boulder-attempt]]
       [::sm/deleted-at {:optional true} :instant]
       [::sm/created-at :instant]
       [:user/id :user/id]
       [:boulder-attempt/timestamp :instant]
       [:boulder-attempt/session-id
        {:optional true :crud/label "Session" :crud/inline-create true}
        :boulder-session/id]
       [:boulder-attempt/problem-id
        {:optional true :crud/priority 1 :crud/label "Problem"
         :crud/inline-create true}
        :boulder-problem/id]
       [:boulder-attempt/sent {:crud/priority 2} :boolean]
       [:boulder-attempt/flash {:optional true :crud/priority 3} :boolean]
       [:boulder-attempt/top {:optional true :crud/priority 4} :boolean]
       ;; Watch-stopwatch time for the attempt
       [:boulder-attempt/duration-seconds {:optional true} :int]
       [:boulder-attempt/laps {:optional true} :int]
       ;; Retry count when a single log covers multiple quick tries
       [:boulder-attempt/retries {:optional true} :int]
       [:boulder-attempt/tags {:optional true} [:set attempt-tag-enum]]
       ;; Subjective read on the attempt, e.g. "better", "warmup", "gassed"
       [:boulder-attempt/feel {:optional true} :string]
       [:boulder-attempt/notes {:optional true} :string]
       [:airtable/id {:optional true} :string]
       [:airtable/created-time {:optional true} :instant]
       [:airtable/ported-at {:optional true} :instant]]
      (concat sm/legacy-meta)
      vec))
