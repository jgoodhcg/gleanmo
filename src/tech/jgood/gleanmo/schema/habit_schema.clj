(ns tech.jgood.gleanmo.schema.habit-schema
  (:require [tech.jgood.gleanmo.schema.meta :as sm]))

(def habit
  [:map {:closed true}
   [:xt/id                               :habit/id]
   [sm/type                              [:enum :habit]]
   [sm/deleted-at {:optional true}       :instant]
   [sm/created-at                        :instant]
   [:user/id                             :user/id]
   [:habit/name {:optional true}         :string]  ;; DEPRECATED
   [:habit/label                         :string]
   [:habit/sensitive {:optional true}    :boolean]
   [:habit/notes {:optional true}        :string]
   [:habit/archived {:optional true}     :boolean]
   [:airtable/id {:optional true}        :string]
   [:airtable/ported-at {:optional true} :instant]])

(def habit-log
  [:map {:closed true}
   [:xt/id                            :habit-log/id]
   [sm/type                           [:enum :habit-log]]
   [sm/deleted-at {:optional true}    :instant]
   [sm/created-at                     :instant]
   [:user/id                          :user/id]
   [:habit-log/timestamp              :instant]
   [:habit-log/time-zone              :string]
   [:habit-log/habit-ids              [:set :habit/id]]
   [:habit-log/notes {:optional true} :string]])
