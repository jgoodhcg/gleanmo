(ns tech.jgood.gleanmo.schema.user-schema
  (:require
   [tech.jgood.gleanmo.schema.meta :as sm]))

(def user
  (-> [:map {:closed true}
       [:xt/id :user/id]
       [::sm/type [:enum :user]]
       [::sm/created-at :instant]
       [:user/joined-at :instant]
       [:user/email :string]
       [:user/time-zone {:optional true} :string]
       [:user/show-sensitive {:optional true} :boolean]
       [:user/show-archived {:optional true} :boolean]
       [:user/show-bm-logs {:optional true} :boolean]
       [:authz/super-user {:optional true} :boolean]
       [::sm/deleted-at {:optional true} :instant]
       ;; Deprecated: kept for backward compatibility with existing user
       ;; docs.
       [:user/hide-bm-logs {:optional true, :hide true} :boolean]]
      (concat sm/legacy-meta)
      vec))
