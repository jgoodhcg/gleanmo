(ns tech.jgood.gleanmo.schema.location-schema
  (:require
    [tech.jgood.gleanmo.schema.meta :as sm]))

(def location
  (-> [:map {:closed true}
       [:xt/id                           :location/id]
       [::sm/type                           [:enum :location]]
       [::sm/deleted-at {:optional true}    :instant]
       [::sm/created-at                     :instant]
       [:user/id                         :user/id]
       [:location/name {:optional true}  :string] ;; DEPRECATED
       [:location/label                  :string]
       [:location/notes {:optional true} :string]]
      (concat sm/legacy-meta)
      vec))
