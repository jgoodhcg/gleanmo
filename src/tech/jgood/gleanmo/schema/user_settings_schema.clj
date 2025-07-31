(ns tech.jgood.gleanmo.schema.user-settings-schema
  (:require [tech.jgood.gleanmo.schema.meta :as sm]))

(def user-settings
  (-> [:map {:closed true}
       [:xt/id :user-settings/id]
       [::sm/type [:enum :user-settings]]
       [::sm/deleted-at {:optional true} :instant]
       [::sm/created-at :instant]
       [:user/id :user/id]
       [:user-settings/show-sensitive {:optional true} :boolean]]
      (concat sm/legacy-meta)
      vec))