(ns tech.jgood.gleanmo.schema.user-schema
  (:require [tech.jgood.gleanmo.schema.meta :as sm]))

(def user
  (-> [:map {:closed true}
       [:xt/id :user/id]
       [::sm/type [:enum :user]]
       [::sm/created-at :instant]
       [:user/joined-at :instant]
       [:user/email :string]
       [:user/time-zone {:optional true} :string]
       [:authz/super-user {:optional true} :boolean]
       [::sm/deleted-at {:optional true} :instant]]
      (concat sm/legacy-meta)
      vec))
