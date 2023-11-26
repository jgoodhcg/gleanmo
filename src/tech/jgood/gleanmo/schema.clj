(ns tech.jgood.gleanmo.schema)

;; all glenamo/type attributes are the schema key
(def schema
  {:user/id      :uuid
   :user         [:map {:closed true}
                  [:xt/id                           :user/id]
                  [::type                           :keyword]
                  [:user/email                      :string]
                  [:user/time-zone {:optional true} :string]
                  [:user/joined-at                  inst?]]
   :habit/id     :uuid
   :habit        [:map {:closed true}
                  [:xt/id                        :habit/id]
                  [::type                        :keyword]
                  [:user/id                      :user/id]
                  [:habit/name                   :string]
                  [:habit/sensitive              boolean?]
                  [:habit/notes {:optional true} :string]]
   :habit-log/id :uuid
   :habit-log    [:map {:closed true}
                  [:xt/id                            :habit-log/id]
                  [::type                            :keyword]
                  [:user/id                          :user/id]
                  [:habit-log/timestamp              inst?]
                  [:habit-log/habit-ids              [:set :habit/id]]
                  [:habit-log/notes {:optional true} :string]]})

(def plugin
  {:schema schema})
