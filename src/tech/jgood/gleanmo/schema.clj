(ns tech.jgood.gleanmo.schema
  (:require [tick.core :as t]))

;; all glenamo/type attributes are the schema key
(def schema
  {:instant             [:fn t/instant?]
   :user/id             :uuid
   :habit/id            :uuid
   :habit-log/id        :uuid
   :exercise/id         :uuid
   :exercise-log/id     :uuid
   :exercise-session/id :uuid
   :user                [:map {:closed true}
                         [:xt/id                             :user/id]
                         [:authz/super-user {:optional true} :boolean]
                         [::type                             [:enum :user]]
                         ;; TODO validate email
                         [:user/email                        :string]
                         ;; TODO validate time zone id
                         [:user/time-zone {:optional true}   :string]
                         [:user/joined-at                    :instant]]
   :habit               [:map {:closed true}
                         [:xt/id                               :habit/id]
                         [::type                               [:enum :habit]]
                         [::deleted-at {:optional true}        :instant]
                         [::created-at                         :instant]
                         [:user/id                             :user/id]
                         [:habit/name                          :string]
                         [:habit/sensitive {:optional true}    :boolean]
                         [:habit/notes {:optional true}        :string]
                         [:habit/archived {:optional true}     :boolean]
                         [:airtable/id {:optional true}        :string]
                         [:airtable/ported-at {:optional true} :instant]]
   :habit-log           [:map {:closed true}
                         [:xt/id                            :habit-log/id]
                         [::type                            [:enum :habit-log]]
                         [::deleted-at {:optional true}     :instant]
                         [::created-at                      :instant]
                         [:user/id                          :user/id]
                         [:habit-log/timestamp              :instant]
                         [:habit-log/time-zone              :string]
                         [:habit-log/habit-ids              [:set :habit/id]]
                         [:habit-log/notes {:optional true} :string]
                         ]
   ;; TODO sort attributes and at created-at
   :exercise            [:map {:closed true}
                         [:xt/id :exercise/id]
                         [::type                                  [:enum :habit-log]]
                         [::deleted-at {:optional true}           :instant]
                         [::created-at                       :instant]
                         [:exercise/label                         :string]
                         [:airtable/log-count {:optional true}    :instant]
                         [:airtable/id {:optional true}           :string]
                         [:airtable/ported {:optional true}       :boolean]
                         [:exercise/source {:optional true}       :string]
                         [:airtable/exercise-log {:optional true} :string]
                         [:exercise/notes  {:optional true}       :string]
                         [:airtable/created-time {:optional true} :instant]]
   ;;
   :exercise-session    [:map {:closed true}
                         [:xt/id                      :exercise-session/id]
                         [::type                     [:enum :exercise-session]]
                         [::deleted-at {:optional true}  :instant]
                         [::created-at                       :instant]
                         [:exercise-session/beginning :instant]
                         [:exercise-session/end       :instant]]
   :exercise-log        [:map {:closed true}
                         [:xt/id                                   :uuid]
                         [::type                                  [:enum :exercise-log]]
                         [::deleted-at {:optional true}               :boolean]
                         [:exercise-session/id                     :exercise-session/id]
                         [:exercise-log.interval/beginning         :instant]
                         [:exercise-log.interval/end               :instant]
                         [:exercise-log.interval/global-median-end :boolean]
                         [:exercise-log/notes                      :string]
                         [:airtable/ported                         :boolean]
                         [:airtable/missing-duration               :number]]
   :exercise-set        [:map {:closed true}
                         [:xt/id                                                    :uuid]
                         [::type                                                    :exercise-set]
                         [::deleted-at                                {:optional true} :boolean]
                         [:exercise/id                                              :exercise/id]
                         [:exercise-log/id                                          :exercise-log/id]
                         [:exercise-set.interval/beginning                          :instant]
                         [:exercise-set.interval/end                                :instant]
                         [:exercise-set/distance                                    :number]
                         [:exercise-set/distance-unit                               :string]
                         [:exercise-set/weight-unit                                 :string]
                         [:exercise-set/reps                                        :int]
                         [:exercise-set/weight-amount                               :number]
                         [:exercise-set.interval/global-median-end {:optional true} :boolean]
                         [:airtable/ported                         {:optional true} :boolean]
                         [:airtable/exercise-id                                     :string]
                         [:airtable/missing-duration                                :number]]})

(def module
  {:schema schema})
