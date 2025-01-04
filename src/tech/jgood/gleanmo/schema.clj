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
   :location/id         :uuid
   :meditation-type/id  :uuid
   :meditation-log/id   :uuid
   :ical-url/id         :uuid

   :user [:map {:closed true}
          [:xt/id                             :user/id]
          [:authz/super-user {:optional true} :boolean]
          [::type                             [:enum :user]]
          ;; TODO validate email
          [:user/email                        :string]
          ;; TODO validate time zone id
          [:user/time-zone {:optional true}   :string]
          [:user/joined-at                    :instant]]

   :habit [:map {:closed true}
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

   :habit-log [:map {:closed true}
               [:xt/id                            :habit-log/id]
               [::type                            [:enum :habit-log]]
               [::deleted-at {:optional true}     :instant]
               [::created-at                      :instant]
               [:user/id                          :user/id]
               [:habit-log/timestamp              :instant]
               [:habit-log/time-zone              :string]
               [:habit-log/habit-ids              [:set :habit/id]]
               [:habit-log/notes {:optional true} :string]]

   :location [:map {:closed true}
              [:xt/id                           :location/id]
              [::type                           [:enum :location]]
              [::deleted-at {:optional true}    :instant]
              [::created-at                     :instant]
              [:user/id                         :user/id]
              [:location/name                   :string]
              [:location/notes {:optional true} :string]]

   :meditation-type [:map {:closed true}
                     [:xt/id                                  :meditation-type/id]
                     [::type                                  [:enum :meditation-type]]
                     [::deleted-at {:optional true}           :instant]
                     [::created-at                            :instant]
                     [:user/id                                :user/id]
                     [:meditation-type/name                   :string]
                     [:meditation-type/notes {:optional true} :string]]

   :meditation-log [:map {:closed true}
                    [:xt/id                                 :meditation-log/id]
                    [::type                                 [:enum :meditation-log]]
                    [::deleted-at {:optional true}          :instant]
                    [::created-at                           :instant]
                    [:user/id                               :user/id]
                    [:meditation-log/location-id            :location/id]
                    [:meditation-log/beginning              :instant]
                    ;; TODO should we allow nil?
                    [:meditation-log/end {:optional true}   :instant]
                    ;; TODO consolidate types and maybe make this a keyword for allowed expansion?
                    [:meditation-log/position               [:enum :sitting :lying :walking :standing :moving]]
                    [:meditation-log/guided                 :boolean]
                    [:meditation-log/type-id                :meditation-type/id]
                    [:meditation-log/interrupted            :boolean]
                    [:meditation-log/notes {:optional true} :string]
                    [:meditation-log/time-zone              :string]]

   :ical-url [:map {:closed true}
              [:xt/id                                    :ical-url/id]
              [::type                                    [:enum :ical-url]]
              [::deleted-at {:optional true}             :instant]
              [::created-at                              :instant]
              [:ical-url/user-id                         :user/id]
              [:ical-url/url                             :string]
              [:ical-url/refreshed-at {:optional true}   :instant]
              [:ical-url/last-fetched {:optional true}   :instant]
              ;; [:ical-url/fetch-interval {:optional true} :duration] ;; duration can be represented in ISO 8601 format
              [:ical-url/source {:optional true}         :string]
              [:ical-url/active {:optional true}         :boolean]
              [:ical-url/name {:optional true}           :string]
              [:ical-url/notes {:optional true}          :string]
              [:ical-url/visibility {:optional true}     [:enum :public :private]]]

   ;; TODO sort attributes and at created-at
   :exercise [:map {:closed true}
              [:xt/id :exercise/id]
              [::type                                  [:enum :habit-log]]
              [::deleted-at {:optional true}           :instant]
              [::created-at                            :instant]
              [:exercise/label                         :string]
              [:airtable/log-count {:optional true}    :instant]
              [:airtable/id {:optional true}           :string]
              [:airtable/ported {:optional true}       :boolean]
              [:exercise/source {:optional true}       :string]
              [:airtable/exercise-log {:optional true} :string]
              [:exercise/notes  {:optional true}       :string]
              [:airtable/created-time {:optional true} :instant]]

   :exercise-session [:map {:closed true}
                      [:xt/id                        :exercise-session/id]
                      [::type                        [:enum :exercise-session]]
                      [::deleted-at {:optional true} :instant]
                      [::created-at                  :instant]
                      [:exercise-session/beginning   :instant]
                      [:exercise-session/end         :instant]]

   :exercise-log [:map {:closed true}
                  [:xt/id                                   :uuid]
                  [::type                                   [:enum :exercise-log]]
                  [::deleted-at {:optional true}            :boolean]
                  [:exercise-session/id                     :exercise-session/id]
                  [:exercise-log.interval/beginning         :instant]
                  [:exercise-log.interval/end               :instant]
                  [:exercise-log.interval/global-median-end :boolean]
                  [:exercise-log/notes                      :string]
                  [:airtable/ported                         :boolean]
                  [:airtable/missing-duration               :number]]

   :exercise-set [:map {:closed true}
                  [:xt/id                                                    :uuid]
                  [::type                                                    [:enum :exercise-set]]
                  [::deleted-at                             {:optional true} :boolean]
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
                  [:airtable/missing-duration                                :number]]

   :cruddy [:map {:closed true}
            [:xt/id                         :uuid]
            [::type                         [:enum :cruddy]]
            [:cruddy/name                   :string]
            [:cruddy/num                    :number]
            [:cruddy/bool                   :boolean]
            [:cruddy/integer                :int]
            [:cruddy/single-relation        :habit/id]
            [:cruddy/set-relation           [:set :habit/id]]
            [:cruddy/enum                   [:enum :a :b :c]]
            [:cruddy/timestamp              :instant]
            [:cruddy/float {:optional true} :float]]})

(def module
  {:schema schema})
