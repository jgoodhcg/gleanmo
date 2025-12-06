(ns tech.jgood.gleanmo.schema.project-schema
  (:require [tech.jgood.gleanmo.schema.meta :as sm]))

(def project
  [:map {:closed true}
   [:xt/id :project/id]
   [::sm/type [:enum :project]]
   [::sm/created-at :instant]
   [::sm/deleted-at {:optional true} :instant]
   [:user/id :user/id]
   [:project/label {:crud/priority 1} :string]
   [:project/sensitive {:optional true} :boolean]
   [:project/archived {:optional true} :boolean]
   [:project/notes {:optional true :crud/priority 2} :string]])

(def project-log
  [:map {:closed true
         :timer/primary-rel :project-log/project-id}
   [:xt/id :project-log/id]
   [::sm/type [:enum :project-log]]
   [::sm/created-at :instant]
   [::sm/deleted-at {:optional true} :instant]
   [:user/id :user/id]
   [:project-log/project-id {:crud/priority 1 :crud/label "Project"} :project/id]
   [:project-log/beginning {:crud/priority 2} :instant]
   [:project-log/end {:optional true :crud/priority 3} :instant]
   [:project-log/time-zone :string]
   [:project-log/location-id {:optional true :crud/label "Location"} :location/id]
   [:project-log/notes {:optional true} :string]])
