(ns tech.jgood.gleanmo.schema.user-schema
  (:require [tech.jgood.gleanmo.schema.meta :as sm]))

(def user
  (-> [:map {:closed true}
       [:xt/id :user/id]
       [:authz/super-user {:optional true} :boolean]
       [::sm/type [:enum :user]]
       [::sm/created-at :instant]
       [::sm/deleted-at {:optional true} :instant]
       [:user/email :string]
       [:user/time-zone {:optional true} :string]
       [:user/joined-at :instant]]
      (concat sm/legacy-meta)
      vec))
