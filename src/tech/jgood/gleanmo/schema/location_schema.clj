(ns tech.jgood.gleanmo.schema.location-schema
  (:require
    [tech.jgood.gleanmo.schema.meta :as sm]))

(def location
  (-> [:map {:closed true}
       [:xt/id :location/id]
       [::sm/type [:enum :location]]
       [::sm/deleted-at {:optional true} :instant]
       [::sm/created-at :instant]
       [:user/id :user/id]
       [:location/label {:crud/priority 1} :string]
       [:location/notes {:optional true :crud/priority 2} :string]
       [:location/archived {:optional true} :boolean]
       [:location/sensitive {:optional true} :boolean]]
      (concat sm/legacy-meta)
      ;; DEPRECATED
      (concat
        [[:location/name {:optional true} :string]])
      vec))
