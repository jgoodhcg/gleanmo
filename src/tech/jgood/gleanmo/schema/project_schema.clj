(ns tech.jgood.gleanmo.schema.project-schema
  (:require [tech.jgood.gleanmo.schema.meta :as sm]))

(def project
  [:map {:closed true}
   [:xt/id :project/id]
   [::sm/type [:enum :project]]
   [::sm/created-at :instant]
   [::sm/deleted-at {:optional true} :instant]
   [:user/id :user/id]
   [:project/label :string]
   [:project/sensitive {:optional true} :boolean]
   [:project/archived {:optional true} :boolean]
   [:project/notes {:optional true} :string]])

(def project-log
  [:map {:closed true
         :timer/primary-rel :project-log/project-id}
   [:xt/id :project-log/id]
   [::sm/type [:enum :project-log]]
   [::sm/created-at :instant]
   [::sm/deleted-at {:optional true} :instant]
   [:user/id :user/id]
   [:project-log/project-id :project/id]
   [:project-log/beginning :instant]
   [:project-log/end {:optional true} :instant]
   [:project-log/time-zone :string]
   [:project-log/location-id {:optional true} :location/id]
   [:project-log/notes {:optional true} :string]])
