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
       [:user/current-location-id {:optional true} :location/id]
       [:user/show-sensitive {:optional true} :boolean]
       [:user/show-archived {:optional true} :boolean]
       [:user/show-bm-logs {:optional true} :boolean]
       [:authz/super-user {:optional true} :boolean]
       ;; An email change waiting on the code sent to the new address. Only
       ;; `user/email` signs anyone in; these are cleared once the change
       ;; verifies, expires, runs out of attempts, or is canceled.
       [:user/pending-email {:optional true, :hide true} :string]
       [:user/pending-email-code-hash {:optional true, :hide true} :string]
       [:user/pending-email-timestamp {:optional true, :hide true} :instant]
       [:user/pending-email-failed-attempts {:optional true, :hide true} :int]
       [::sm/deleted-at {:optional true} :instant]
       ;; Deprecated: kept for backward compatibility with existing user
       ;; docs.
       [:user/hide-bm-logs {:optional true, :hide true} :boolean]]
      (concat sm/legacy-meta)
      vec))
