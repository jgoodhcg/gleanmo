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

;; A block is one timed chunk of work within a session: one set for straight
;; work, several back-to-back sets when supersetting. The timer lives here —
;; blocks exist so a set (or superset) has an *interval* rather than a bare
;; timestamp, enabling time-density analysis (reps per unit time, time under
;; tension) and making rest derivable as the gap between consecutive blocks.
;;
;; The auto-started/auto-ended flags mark edges the user didn't observe:
;; auto-started means the beginning was fabricated (set logged with no open
;; block — the end is still accurate, it's the log moment); auto-ended means
;; the end was fabricated (block force-closed when its session ended).
;; Analysis should exclude flagged edges from duration stats and, when an
;; estimate is needed, impute from recent accurate blocks of the same
;; exercise at read time — we deliberately don't snapshot an average onto
;; the record, since the imputation heuristic can improve later.
(def exercise-block
  (-> [:map {:closed true}
       [:xt/id :exercise-block/id]
       [::sm/type [:enum :exercise-block]]
       [::sm/deleted-at {:optional true} :instant]
       [::sm/created-at :instant]
       [:user/id :user/id]
       [:exercise-block/session-id
        {:crud/priority 1 :crud/label "Session" :crud/inline-create true}
        :exercise-session/id]
       [:exercise-block/beginning :instant]
       [:exercise-block/end {:optional true} :instant]
       [:exercise-block/auto-started {:optional true} :boolean]
       [:exercise-block/auto-ended {:optional true} :boolean]
       [:exercise-block/notes {:optional true} :string]
       [:airtable/id {:optional true} :string]
       [:airtable/created-time {:optional true} :instant]
       [:airtable/ported-at {:optional true} :instant]
       ;; Entity-specific airtable fields
       [:airtable/exercise-id {:optional true} :string]
       [:airtable/missing-duration {:optional true} :number]]
      (concat sm/legacy-meta)
      vec))

;; The classic "3 sets of 10" unit: reps of one exercise at a weight/distance.
(def exercise-set
  (-> [:map {:closed true}
       [:xt/id :exercise-set/id]
       [::sm/type [:enum :exercise-set]]
       [::sm/deleted-at {:optional true} :instant]
       [::sm/created-at :instant]
       [:user/id :user/id]
       [:exercise-set/block-id
        {:crud/priority 1 :crud/label "Block" :crud/inline-create true}
        :exercise-block/id]
       [:exercise-set/exercise-id
        {:crud/priority 2 :crud/label "Exercise"}
        :exercise/id]
       [:exercise-set/set-number {:optional true} :int]
       [:exercise-set/reps {:optional true :crud/priority 3} :int]
       [:exercise-set/weight {:optional true :crud/priority 4} :number]
       [:exercise-set/weight-unit {:optional true :crud/priority 5} [:enum :lbs :kg]]
       [:exercise-set/distance {:optional true} :number]
       [:exercise-set/distance-unit {:optional true} [:enum :miles :km :meters]]
       [:exercise-set/notes {:optional true} :string]
       [:airtable/id {:optional true} :string]
       [:airtable/created-time {:optional true} :instant]
       [:airtable/ported-at {:optional true} :instant]]
      (concat sm/legacy-meta)
      vec))
