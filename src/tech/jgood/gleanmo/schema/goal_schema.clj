(ns tech.jgood.gleanmo.schema.goal-schema
  (:require
   [tech.jgood.gleanmo.schema.meta :as sm]))

;; A goal stores only its definition. Progress, completion, forecasts, and
;; chart series are always computed from the source records at read time, so
;; correcting a log (or its finished flag) corrects every goal that counts it.
;;
;; Which source/measure/aggregation combinations are valid, which relation
;; filter applies, and the unit are defined by the measurement registry in
;; `tech.jgood.gleanmo.goals.registry`; cross-field rules are enforced at the
;; write boundary by `tech.jgood.gleanmo.schema.rules`. The enums here only
;; bound the vocabulary.
(def goal
  [:map {:closed true}
   [:xt/id :goal/id]
   [::sm/type [:enum :goal]]
   [::sm/deleted-at {:optional true} :instant]
   [::sm/created-at :instant]
   [:user/id :user/id]
   [:goal/label {:crud/priority 1, :crud/label "Goal"} :string]
   [:goal/source {:crud/priority 2, :crud/label "Source"}
    [:enum :project-log :reading-log :meditation-log :habit-log
     :exercise-session :boulder-session :boulder-attempt :exercise-line]]
   [:goal/measure {:crud/priority 3, :crud/label "Measure"}
    [:enum :duration :records :reps :attempts :weight :book-completion]]
   [:goal/aggregation {:crud/priority 4, :crud/label "Aggregation"}
    [:enum :total :best :completion]]
   ;; Canonical unit of the measurement: seconds, count, or kg.
   [:goal/target {:optional true, :crud/priority 5, :crud/label "Target"} :number]
   [:goal/timing {:crud/priority 6, :crud/label "Timing"}
    [:enum :dated :weekly :open-ended]]
   ;; Inclusive local dates in the goal's own time zone. starts-on is the
   ;; explicit counting baseline: nothing before it counts.
   [:goal/starts-on {:crud/priority 7, :crud/label "Starts on"} :local-date]
   [:goal/ends-on {:optional true, :crud/priority 8, :crud/label "Ends on"}
    :local-date]
   [:goal/time-zone {:crud/label "Time zone"} :string]
   [:goal/threshold-step {:optional true, :crud/label "Threshold step"} :number]
   ;; Book-completion chart preferences.
   [:goal/progress-measure {:optional true, :crud/label "Chart measure"}
    [:enum :pages :chapters :audio]]
   [:goal/even-pace-enabled {:optional true, :crud/label "Even-pace guide"}
    :boolean]
   [:goal/archived {:optional true} :boolean]
   ;; Relation filters. Absent means every eligible record of the source; an
   ;; explicitly empty set is rejected rather than silently meaning "all".
   [:goal/project-ids {:optional true, :crud/label "Projects"} [:set :project/id]]
   [:goal/book-ids {:optional true, :crud/label "Books"} [:set :book/id]]
   [:goal/meditation-ids {:optional true, :crud/label "Meditations"}
    [:set :meditation/id]]
   [:goal/habit-ids {:optional true, :crud/label "Habits"} [:set :habit/id]]
   [:goal/exercise-ids {:optional true, :crud/label "Exercises"}
    [:set :exercise/id]]])
