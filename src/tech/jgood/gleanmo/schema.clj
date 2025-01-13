(ns tech.jgood.gleanmo.schema
  (:require [tick.core :as t]
            [tech.jgood.gleanmo.schema.habit-schema :as hs]
            [tech.jgood.gleanmo.schema.meditation-schema :as ms]
            [tech.jgood.gleanmo.schema.exercise-schema :as es]))

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
   :user                [:map {:closed true}
                         [:xt/id                             :user/id]
                         [:authz/super-user {:optional true} :boolean]
                         [::type                             [:enum :user]]
                         [:user/email                        :string]
                         [:user/time-zone {:optional true}   :string]
                         [:user/joined-at                    :instant]]
   :habit               hs/habit
   :habit-log           hs/habit-log
   :location            [:map {:closed true}
                         [:xt/id                           :location/id]
                         [::type                           [:enum :location]]
                         [::deleted-at {:optional true}    :instant]
                         [::created-at                     :instant]
                         [:user/id                         :user/id]
                         [:location/name {:optional true}  :string] ;; DEPRECATED
                         [:location/label                  :string]
                         [:location/notes {:optional true} :string]]
   :meditation-type     ms/meditation-type
   :meditation-log      ms/meditation-log
   :ical-url            [:map {:closed true}
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
                         [:ical-url/name {:optional true}           :string]  ;; DEPRECATED
                         [:ical-url/label {:optional true}          :string]
                         [:ical-url/notes {:optional true}          :string]
                         [:ical-url/visibility {:optional true}     [:enum :public :private]]]
   :exercise            es/exercise
   :exercise-session    es/exercise-session
   :exercise-log        es/exercise-log
   :exercise-set        es/exercise-set
   :cruddy              [:map {:closed true}
                         [:xt/id                         :uuid]
                         [::type                         [:enum :cruddy]]
                         [::deleted-at  {:optional true} :boolean]
                         [::created-at                   :instant]
                         [:cruddy/label                  :string]
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
