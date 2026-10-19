(ns tech.jgood.gleanmo.schema.api-token-schema
  (:require
   [tech.jgood.gleanmo.schema.meta :as sm]))

;; A bearer credential for the agent API (`tech.jgood.gleanmo.api`). Only the
;; SHA-256 of the token is stored; the plaintext is shown once at creation.
;; `prefix` is the recognisable head of the token (`gmn_ab12cd34`) so a list of
;; tokens can be told apart without revealing any of them.
;;
;; Permissions are entity *groups* (see `api.access/groups`), not entity types,
;; so a token granted `:tasks` keeps covering tasks if the group later gains a
;; type. Write implies read. The two `-granted` flags decide whether the token
;; may ask for sensitive or archived records at all; each request still has to
;; ask for them explicitly. `expires-at` is optional: tokens made before it
;; existed, and tokens created with no expiry, never expire.
(def api-token
  [:map {:closed true}
   [:xt/id :api-token/id]
   [::sm/type [:enum :api-token]]
   [::sm/created-at :instant]
   [::sm/deleted-at {:optional true} :instant]
   [:user/id :user/id]
   [:api-token/label :string]
   [:api-token/token-hash :string]
   [:api-token/prefix :string]
   [:api-token/read-groups [:set :keyword]]
   [:api-token/write-groups [:set :keyword]]
   [:api-token/sensitive-granted {:optional true} :boolean]
   [:api-token/archived-granted {:optional true} :boolean]
   [:api-token/expires-at {:optional true} :instant]
   [:api-token/last-used-at {:optional true} :instant]
   [:api-token/revoked-at {:optional true} :instant]])
