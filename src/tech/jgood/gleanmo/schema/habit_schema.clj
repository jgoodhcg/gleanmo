(ns tech.jgood.gleanmo.schema.habit-schema
  (:require
   [tech.jgood.gleanmo.schema.meta :as sm]))

(def habit
  (-> [:map {:closed true}
       [:xt/id :habit/id]
       [::sm/type [:enum :habit]]
       [::sm/deleted-at {:optional true} :instant]
       [::sm/created-at :instant]
       [:user/id :user/id]
       [:habit/label {:crud/priority 1 :crud/label "Habit"} :string]
       [:habit/sensitive {:optional true} :boolean]
       [:habit/notes {:optional true} :string]
       [:habit/archived {:optional true} :boolean]
       [:airtable/id {:optional true} :string]
       [:airtable/ported-at {:optional true} :instant]]
      (concat sm/legacy-meta)
      ;; DEPRECATED
      (concat
       [[:habit/name {:optional true, :hide true} :string]])
      vec))

(def habit-log
  (-> [:map {:closed true}
       [:xt/id :habit-log/id]
       [::sm/type [:enum :habit-log]]
       [::sm/deleted-at {:optional true} :instant]
       [::sm/created-at :instant]
       [:user/id :user/id]
       [:habit-log/timestamp {:crud/priority 2} :instant]
       [:habit-log/time-zone  :string]
       [:habit-log/habit-ids {:crud/priority 1, :crud/label "Habits"}
        [:set :habit/id]]
       [:habit-log/notes {:optional true, :crud/priority 3 :crud/label "Notes"}
        :string]]
      (concat sm/legacy-meta)
      vec))
