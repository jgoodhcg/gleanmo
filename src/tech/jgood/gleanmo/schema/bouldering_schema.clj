(ns tech.jgood.gleanmo.schema.bouldering-schema
  (:require
   [tech.jgood.gleanmo.schema.meta :as sm]))

(def perceived-exertion-enum
  [:enum :easy :moderate :hard :limit])

(def grade-enum
  [:enum :v0 :v1 :v2 :v3 :v4 :v5 :v6 :v7 :v8 :v9 :v10 :v11 :v12 :v13 :project])

(def attempt-tag-enum
  [:enum :dab :intentional-practice-dab :peel :bail :off-start :reversed
   :foot-slip])

;; Sessions are synthesized during Airtable migration (one per day of tries),
;; so they carry only airtable/ported-at — no source record backs them.
(def boulder-session
  [:map {:closed true}
   [:xt/id :boulder-session/id]
   [::sm/type [:enum :boulder-session]]
   [::sm/deleted-at {:optional true} :instant]
   [::sm/created-at :instant]
   [:user/id :user/id]
   [:boulder-session/label {:optional true} :string]
   [:boulder-session/beginning :instant]
   [:boulder-session/end {:optional true} :instant]
   ;; Sparse open-interval flag derived in db/mutations.clj — see
   ;; :exercise-session/running.
   [:boulder-session/running {:optional true :hide true} :boolean]
   [:boulder-session/gym {:crud/priority 1 :crud/suggest-existing true} :string]
   [:boulder-session/perceived-exertion {:optional true :crud/priority 2}
    perceived-exertion-enum]
   [:boulder-session/notes {:optional true :crud/priority 3} :string]
   [:airtable/ported-at {:optional true} :instant]])

;; A specific problem at a gym (route/boulder). Mirrors the Airtable
;; "bouldering problems" table: circuit difficulty label, hold color, wall.
;; Pictures are not ported — Airtable attachment URLs expire.
(def boulder-problem
  [:map {:closed true}
   [:xt/id :boulder-problem/id]
   [::sm/type [:enum :boulder-problem]]
   [::sm/deleted-at {:optional true} :instant]
   [::sm/created-at :instant]
   [:user/id :user/id]
   ;; Gym vocabulary stays :string rather than an enum — gyms, circuits, hold
   ;; colours and walls all change without warning, and an enum would need a
   ;; schema edit every time. :crud/suggest-existing gives the form a datalist
   ;; of values already used so they can be picked rather than retyped, while
   ;; still accepting anything new.
   [:boulder-problem/gym {:crud/priority 1 :crud/suggest-existing true} :string]
   ;; Circuit label as the gym presents it, e.g. "pink v0-v2"
   [:boulder-problem/difficulty
    {:crud/priority 2 :crud/suggest-existing true} :string]
   [:boulder-problem/hold-color
    {:optional true :crud/priority 3 :crud/suggest-existing true} :string]
   [:boulder-problem/wall
    {:optional true :crud/priority 4 :crud/suggest-existing true} :string]
   ;; Known/estimated V-grade (Airtable guessed-grade)
   [:boulder-problem/grade {:optional true} grade-enum]
   [:boulder-problem/label {:optional true} :string]
   ;; No longer on the wall at the gym (Airtable called this Archived).
   ;; Deliberately not named archived — that has app-wide hide semantics.
   [:boulder-problem/inactive {:optional true} :boolean]
   ;; When it came off the wall, so the retired list can order by what was
   ;; taken down most recently rather than by what was created most recently.
   ;; Absent on problems imported from Airtable (which never recorded it) and
   ;; on anything retired before this field existed; those fall back to
   ;; created-at. Cleared on restore.
   [:boulder-problem/inactive-at {:optional true} :instant]
   [:boulder-problem/notes {:optional true} :string]
   [:airtable/id {:optional true} :string]
   [:airtable/created-time {:optional true} :instant]
   [:airtable/ported-at {:optional true} :instant]
   [:airtable/original-problem-number {:optional true} :int]])

;; An attempt is an interval: the gym flow starts a timer when climbing
;; begins and closes it when the result is logged. Migration derives
;; beginning as Airtable timestamp minus stopwatch duration, end as the
;; timestamp itself.
(def boulder-attempt
  [:map {:closed true}
   [:xt/id :boulder-attempt/id]
   [::sm/type [:enum :boulder-attempt]]
   [::sm/deleted-at {:optional true} :instant]
   [::sm/created-at :instant]
   [:user/id :user/id]
   [:boulder-attempt/beginning :instant]
   [:boulder-attempt/end {:optional true} :instant]
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
   [:boulder-attempt/laps {:optional true} :int]
   ;; Retry count when a single log covers multiple quick tries
   [:boulder-attempt/attempts {:optional true} :int]
   [:boulder-attempt/tags {:optional true} [:set attempt-tag-enum]]
   ;; Subjective read on the attempt, e.g. "better", "warmup", "gassed"
   [:boulder-attempt/feel {:optional true :crud/suggest-existing true} :string]
   [:boulder-attempt/notes {:optional true} :string]
   [:airtable/id {:optional true} :string]
   [:airtable/created-time {:optional true} :instant]
   [:airtable/ported-at {:optional true} :instant]])
