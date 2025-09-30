(ns tech.jgood.gleanmo.schema.performance-schema
  (:require [tech.jgood.gleanmo.schema.meta :as sm]))

(def performance-report
  [:map {:closed true}
   [:xt/id :keyword]
   [::sm/type [:enum :performance-report]]
   [::sm/deleted-at {:optional true} :instant]
   [::sm/created-at :instant]
   [:performance-report/instance-id :string]
   [:performance-report/instance-started-at :instant]
   [:performance-report/generated-at :instant]
   [:performance-report/git-sha :string]
   [:performance-report/pstats
    [:map-of :keyword
     [:map
      [:clock [:map-of :keyword :any]]
      [:stats [:map-of :keyword [:map-of :keyword :any]]]
      [:summary :string]]]]])
