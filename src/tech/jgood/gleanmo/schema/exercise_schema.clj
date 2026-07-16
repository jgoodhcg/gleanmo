(ns tech.jgood.gleanmo.schema.exercise-schema
  (:require
   [tech.jgood.gleanmo.schema.meta :as sm]))

(def exercise
  (-> [:map {:closed true}
       [:xt/id :exercise/id]
       [::sm/type [:enum :exercise]]
       [::sm/deleted-at {:optional true} :instant]
       [::sm/created-at :instant]
       [:user/id :user/id]
       [:exercise/label :string]
       [:exercise/source {:optional true} :string]
       [:exercise/notes {:optional true} :string]
       [:airtable/id {:optional true} :string]
       [:airtable/created-time {:optional true} :instant]
       [:airtable/ported-at {:optional true} :instant]
       ;; Entity-specific airtable fields
       [:airtable/exercise-log {:optional true} :string]
       [:airtable/log-count {:optional true} :number]]
      (concat sm/legacy-meta)
      vec))

(def exercise-session
  (-> [:map {:closed true}
       [:xt/id :exercise-session/id]
       [::sm/type [:enum :exercise-session]]
       [::sm/deleted-at {:optional true} :instant]
       [::sm/created-at :instant]
       [:user/id :user/id]
       [:exercise-session/label {:optional true} :string]
       [:exercise-session/beginning :instant]
       [:exercise-session/end {:optional true} :instant]
       [:exercise-session/notes {:optional true} :string]]
      (concat sm/legacy-meta)
      vec))

;; Naming follows gym vocabulary: a session contains *sets* (timed intervals
;; of work — one exercise for straight work, several for a superset), and
;; each set contains one *line* per exercise performed (the reps/weight/
;; distance record). "Set" here is what an earlier iteration called a
;; "block"; it was renamed because to a lifter a superset is one set spanning
;; multiple exercises, not multiple sets inside a block.
;;
;; A set is an *interval* rather than a bare timestamp so time-density
;; analysis works (reps per unit time, time under tension) and rest is
;; derivable as the gap between consecutive sets.
;;
;; The auto-started/auto-ended flags mark edges the user didn't observe:
;; auto-started means the beginning was fabricated (line logged with no open
;; set — the end is still accurate, it's the log moment); auto-ended means
;; the end was fabricated (set force-closed when its session ended).
;; Analysis should exclude flagged edges from duration stats and, when an
;; estimate is needed, impute from recent accurate sets of the same
;; exercise at read time — we deliberately don't snapshot an average onto
;; the record, since the imputation heuristic can improve later.
(def exercise-set
  (-> [:map {:closed true}
       [:xt/id :exercise-set/id]
       [::sm/type [:enum :exercise-set]]
       [::sm/deleted-at {:optional true} :instant]
       [::sm/created-at :instant]
       [:user/id :user/id]
       [:exercise-set/session-id
        {:crud/priority 1 :crud/label "Session" :crud/inline-create true}
        :exercise-session/id]
       [:exercise-set/beginning :instant]
       [:exercise-set/end {:optional true} :instant]
       [:exercise-set/auto-started {:optional true} :boolean]
       [:exercise-set/auto-ended {:optional true} :boolean]
       [:exercise-set/notes {:optional true} :string]
       [:airtable/id {:optional true} :string]
       [:airtable/created-time {:optional true} :instant]
       [:airtable/ported-at {:optional true} :instant]
       ;; Entity-specific airtable fields
       [:airtable/exercise-id {:optional true} :string]
       [:airtable/missing-duration {:optional true} :number]]
      (concat sm/legacy-meta)
      vec))

;; One exercise's performance within a set: reps at a weight, or a distance.
;; A straight set has one line; a superset has one line per exercise.
(def exercise-line
  (-> [:map {:closed true}
       [:xt/id :exercise-line/id]
       [::sm/type [:enum :exercise-line]]
       [::sm/deleted-at {:optional true} :instant]
       [::sm/created-at :instant]
       [:user/id :user/id]
       [:exercise-line/set-id
        {:crud/priority 1 :crud/label "Set" :crud/inline-create true}
        :exercise-set/id]
       [:exercise-line/exercise-id
        {:crud/priority 2 :crud/label "Exercise"}
        :exercise/id]
       [:exercise-line/reps {:optional true :crud/priority 3} :int]
       [:exercise-line/weight {:optional true :crud/priority 4} :number]
       [:exercise-line/weight-unit {:optional true :crud/priority 5} [:enum :lbs :kg]]
       [:exercise-line/distance {:optional true} :number]
       [:exercise-line/distance-unit {:optional true} [:enum :miles :km :meters]]
       [:exercise-line/notes {:optional true} :string]
       [:airtable/id {:optional true} :string]
       [:airtable/created-time {:optional true} :instant]
       [:airtable/ported-at {:optional true} :instant]]
      (concat sm/legacy-meta)
      vec))
