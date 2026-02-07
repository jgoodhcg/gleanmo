(ns tech.jgood.gleanmo.schema.task-schema
  (:require
   [tech.jgood.gleanmo.schema.meta :as sm]))

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
    [:task/effort {:optional true, :crud/priority 8}
     [:enum :low :medium :high]]
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
    [:task/last-state-change-at {:optional true, :hide true} :instant]]))
