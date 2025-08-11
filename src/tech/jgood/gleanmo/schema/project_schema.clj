(ns tech.jgood.gleanmo.schema.project-schema
  (:require
   [tech.jgood.gleanmo.schema.meta :as sm]))

(def project
  (-> [:map {:closed true}
       [:xt/id :project/id]
       [::sm/type [:enum :project]]
       [::sm/created-at :instant]
       [::sm/deleted-at {:optional true} :instant]
       [:user/id :user/id]
       [:project/label :string]
       [:project/roam-pages {:optional true} [:set [:map
                                                     [:uid :string]
                                                     [:title {:optional true} :string]]]]
       [:project/archived {:optional true} :boolean]
       [:project/sensitive {:optional true} :boolean]
       [:project/notes {:optional true} :string]]
      vec))

(def project-log
  (-> [:map {:closed true}
       [:xt/id :project-log/id]
       [::sm/type [:enum :project-log]]
       [::sm/created-at :instant]
       [::sm/deleted-at {:optional true} :instant]
       [:user/id :user/id]
       [:project-log/project-id :project/id]
       [:project-log/beginning :instant]
       [:project-log/end :instant]
       [:project-log/notes {:optional true} :string]]
      vec))