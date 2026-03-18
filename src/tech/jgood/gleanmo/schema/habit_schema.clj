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
       [:habit/notes {:optional true :crud/priority 2} :string]
       [:habit/archived {:optional true} :boolean]
       [:airtable/id {:optional true} :string]
       ;; TODO: Missing airtable/created-time
       ;; See roadmap/airtable-metadata-consistency.md #2
       [:airtable/ported-at {:optional true} :instant]]
      (concat
       sm/legacy-meta
       ;; DEPRECATED
       [[:habit/name {:optional true, :hide true} :string]])
      vec))

(def habit-log
  ;; TODO: Missing airtable/id, airtable/created-time, airtable/ported-at
  ;; See roadmap/airtable-metadata-consistency.md #3
  (-> [:map {:closed true}
       [:xt/id :habit-log/id]
       [::sm/type [:enum :habit-log]]
       [::sm/deleted-at {:optional true} :instant]
       [::sm/created-at :instant]
       [:user/id :user/id]
       [:habit-log/timestamp :instant]
       [:habit-log/time-zone  :string]
       [:habit-log/habit-ids {:crud/priority 1, :crud/label "Habits"}
        [:set :habit/id]]
       [:habit-log/notes {:optional true, :crud/priority 2 :crud/label "Notes"}
        :string]]
      (concat sm/legacy-meta)
      vec))
