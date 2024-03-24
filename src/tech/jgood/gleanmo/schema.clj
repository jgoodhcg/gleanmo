(ns tech.jgood.gleanmo.schema)

;; all glenamo/type attributes are the schema key
(def schema
  {:inst                inst?
   :user/id             :uuid
   :user                [:map {:closed true}
                         [:xt/id                           :user/id]
                         [::type                          [:enum :user]]
                         [:user/email                      :string]
                         [:user/time-zone {:optional true} :string]
                         [:user/joined-at                  :inst]]
   :habit/id            :uuid
   :habit               [:map {:closed true}
                         [:xt/id                           :habit/id]
                         [::type                          [:enum :habit]]
                         [:user/id                         :user/id]
                         [:habit/name                      :string]
                         [:habit/sensitive                 :boolean]
                         [:habit/notes {:optional true}    :string]
                         [:habit/archived {:optional true} :boolean]]
   :habit-log/id        :uuid
   :habit-log           [:map {:closed true}
                         [:xt/id                            :habit-log/id]
                         [::type                           [:enum :habit-log]]
                         [:user/id                          :user/id]
                         [:habit-log/timestamp              :inst]
                         [:habit-log/habit-ids             [:set :habit/id]]
                         [:habit-log/notes {:optional true} :string]]
   :exercise            [:map {:closed true}
                         [:xt/id :uuid]
                         [::type                                 [:enum :habit-log]]
                         [:exercise/label                         :string]
                         [:airtable/log-count {:optional true}    :inst]
                         [:airtable/id {:optional true}           :string]
                         [:airtable/ported {:optional true}       :boolean]
                         [:exercise/source {:optional true}       :string]
                         [:airtable/exercise-log {:optional true} :string]
                         [:exercise/notes  {:optional true}       :string]
                         [:airtable/created-time {:optional true} :inst]]
   :exercise-log/id     :uuid
   :exercise-session/id :uuid
   :exercise-log        [:map {:closed true}
                          [:xt/id :uuid]
                          [::type                                  [:enum :exercise-log]]
                          [:exercise-log.interval/global-median-end :boolean]
                          [:exercise-log.interval/beginning         :inst]
                          [:exercise-log/notes                      :string]
                          [:airtable/ported                         :boolean]
                          [:exercise-session/id :exercise-session/id]
                          [:gleanmo/type :keyword]
                          [:airtable/missing-duration :number]
                          [:exercise-log.interval/end :inst]]
   :exercise-set        [:map {:closed true}
                         [:exercise-log/id :exercise-log/id]
                         [:exercise-set/interval/beginning :inst]
                         [:exercise-set/distance :number]
                         [:exercise-set/distance-unit :string]
                         [:airtable/ported :boolean]
                         [:exercise-set/weight-unit :string]
                         [:exercise-set.interval/end :inst]
                         [:exercise-set/reps :int]
                         [:airtable/exercise-id :string] ;; Assuming this is a string
                         [:exercise-set/weight-amount :number]
                         [:xt/id :uuid]
                         [:exercise-set.interval/global-median-end {:optional true} :boolean]
                         [:exercise/id :uuid]
                         [:airtable/missing-duration :number]]
   :exercise-session/id :uuid
   :exercise-session    [:map {:closed true}
                         [:xt/id                      :exercise-session/id]
                         [::type                     [:enum :exercise-session]]
                         [:exercise-session/beginning :inst]
                         [:exercise-session/end       :inst]]})

(def plugin
  {:schema schema})
