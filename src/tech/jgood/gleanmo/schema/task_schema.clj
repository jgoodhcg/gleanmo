(ns tech.jgood.gleanmo.schema.task-schema
  (:require
   [tech.jgood.gleanmo.schema.meta :as sm]))

;; Fibonacci story points. Gaps widen with size because estimates get less
;; precise as tasks get bigger, and the middle stays finely resolved (2/3/5)
;; where most tasks land — powers of two would jump 2->4->8 straight past it.
;; Unlike symptom severity this scale is designed rather than inherited, so it
;; starts at a clean 1: "trivial" is the floor of the scale, not a fraction
;; below it.
(def effort-scale
  [[1  "Trivial"]
   [2  "Quick"]
   [3  "Small"]
   [5  "Medium"]
   [8  "Large"]
   [13 "Huge"]])

(def task
  (->
   [:map {:closed true}
    [:xt/id :task/id]
    [::sm/type [:enum :task]]
    [::sm/created-at :instant]
    [::sm/deleted-at {:optional true} :instant]
    [:user/id :user/id]

     ;; Core
    [:task/label {:crud/priority 1} :string]
    [:task/notes {:optional true, :crud/priority 2} :string]
    [:task/state {:crud/priority 3}
     [:enum :inbox :now :later :waiting :done :canceled]]
    [:task/sensitive {:optional true} :boolean]

     ;; Dates
    [:task/due-on {:optional true, :crud/priority 4} :local-date]
    [:task/snooze-until {:optional true, :crud/priority 5} [:maybe :local-date]]
    [:task/done-at {:optional true, :hide true} :instant]

     ;; Daily focus
    [:task/focus-date {:optional true, :crud/priority 6, :crud/label "Focus date"}
     :local-date]
    [:task/focus-order {:optional true, :crud/priority 7} :int]

     ;; Attributes (fixed enums)
    [:task/effort-score
     {:optional true, :crud/priority 8, :crud/label "Effort",
      :crud/description "Rough size of the task.",
      :crud/scale effort-scale}
     :number]
    [:task/mode {:optional true, :crud/priority 9}
     [:enum :solo :social]]
    [:task/domain {:optional true, :crud/priority 10}
     [:enum :work :personal :home :health :admin]]

     ;; Relationships
    [:task/project-id {:optional true, :crud/priority 11, :crud/label "Project"}
     :project/id]

     ;; Signal tracking (system-managed, hidden from forms)
    [:task/snooze-count {:optional true, :hide true} :int]
    [:task/state-change-count {:optional true, :hide true} :int]
    [:task/last-state-change-at {:optional true, :hide true} :instant]

     ;; DEPRECATED - superseded by :task/effort-score. Nothing reads this
     ;; field; it stays only because the map is :closed true, so removing it
     ;; would make every task already carrying one of these values fail
     ;; validation on its next write. :hide true keeps forms from adding more.
    [:task/effort {:optional true, :hide true}
     [:enum :low :medium :high]]]))
