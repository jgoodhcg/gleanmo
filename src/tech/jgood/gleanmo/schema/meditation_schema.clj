(ns tech.jgood.gleanmo.schema.meditation-schema
  (:require [tech.jgood.gleanmo.schema.meta :as sm]))

(def meditation-type
  [:map {:closed true}
   [:xt/id                                  :meditation-type/id]
   [sm/type                                 [:enum :meditation-type]]
   [sm/deleted-at {:optional true}          :instant]
   [sm/created-at                           :instant]
   [:user/id                                :user/id]
   [:meditation-type/name {:optional true}  :string]  ;; DEPRECATED
   [:meditation-type/label                  :string]
   [:meditation-type/notes {:optional true} :string]])

(def meditation-log
  [:map {:closed true}
   [:xt/id                                 :meditation-log/id]
   [sm/type                                [:enum :meditation-log]]
   [sm/deleted-at {:optional true}         :instant]
   [sm/created-at                          :instant]
   [:user/id                               :user/id]
   [:meditation-log/location-id            :location/id]
   [:meditation-log/beginning              :instant]
   [:meditation-log/end {:optional true}   :instant]
   [:meditation-log/position               [:enum :sitting :lying :walking :standing :moving]]
   [:meditation-log/guided                 :boolean]
   [:meditation-log/type-id                :meditation-type/id]
   [:meditation-log/interrupted            :boolean]
   [:meditation-log/notes {:optional true} :string]
   [:meditation-log/time-zone              :string]])
