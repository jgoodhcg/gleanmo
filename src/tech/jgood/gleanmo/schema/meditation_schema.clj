(ns tech.jgood.gleanmo.schema.meditation-schema
  (:require
   [tech.jgood.gleanmo.schema.meta :as sm]))

(def meditation
  (-> [:map {:closed true}
       [:xt/id :meditation/id]
       [::sm/type [:enum :meditation]]
       [::sm/deleted-at {:optional true} :instant]
       [::sm/created-at :instant]
       [:user/id :user/id]
       [:meditation/label :string]
       [:meditation/notes {:optional true} :string]]
      (concat sm/legacy-meta)
      ;; DEPRECATED
      (concat [[:meditation/name {:optional true, :hide true} :string]
               [:meditation-type/name {:optional true, :hide true} :string]
               [:meditation-type/label {:optional true, :hide true} :string]
               [:meditation-type/notes {:optional true, :hide true} :string]])
      vec))

(def meditation-log
  (-> [:map {:closed true
             :timer/primary-rel :meditation-log/type-id}
       [:xt/id :meditation-log/id]
       [::sm/type [:enum :meditation-log]]
       [::sm/deleted-at {:optional true} :instant]
       [::sm/created-at :instant]
       [:user/id :user/id]
       [:meditation-log/location-id :location/id]
       [:meditation-log/beginning :instant]
       [:meditation-log/end {:optional true} :instant]
       [:meditation-log/position
        [:enum :sitting :lying :walking :standing :moving]]
       [:meditation-log/guided :boolean]
       [:meditation-log/type-id :meditation/id]
       [:meditation-log/interrupted :boolean]
       [:meditation-log/notes {:optional true} :string]
       [:meditation-log/time-zone :string]
       [:meditation-log/sequence-completed {:optional true} :boolean]]
      (concat sm/legacy-meta)
      vec))
