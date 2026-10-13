(ns tech.jgood.gleanmo.schema.cronometer-schema
  "Cronometer-owned schema: the per-user connection, and the lineage
   attributes Cronometer imports add to the provider-neutral entities
   (`nutrition-schema`, `biometric-schema`, `activity-observation-schema`).
   See roadmap/056-cronometer-integration.md, Data Model.

   Imported ids are name-based UUIDs of `<user-id>/<kind>/<cronometer key>`
   (`cronometer.transform/doc-id`), so a re-sync addresses the same documents
   without a lookup."
  (:require
   [tech.jgood.gleanmo.schema.meta :as sm]))

(def lineage
  "Cronometer lineage carried by every entity a Cronometer import writes. All
   optional, so the same entities can come from another provider. The raw
   day, time and offset make a time-zone correction a recompute, not a
   re-fetch."
  [[:cronometer/raw {:optional true, :hide true} :string]
   [:cronometer/day {:optional true} :string]
   [:cronometer/time {:optional true} :string]
   [:cronometer/offset {:optional true} :int]
   [:cronometer/synced-at {:optional true} :instant]])

(def cronometer-connection
  ;; One per user: the Cronometer email and password, encrypted together with
  ;; AES-256-GCM under `:gleanmo/credentials-key` (see `tech.jgood.gleanmo.crypto`).
  ;; Nothing here is plaintext. Disconnecting or changing the password evicts
  ;; the document, because XTDB keeps every version of it.
  ;;
  ;; Reachable from nowhere but the Cronometer status page and the sync: it is
  ;; in no API group and not in `crud-entities`.
  [:map {:closed true}
   [:xt/id :cronometer-connection/id]
   [::sm/type [:enum :cronometer-connection]]
   [::sm/deleted-at {:optional true} :instant]
   [::sm/created-at :instant]
   [:user/id :user/id]
   ;; base64 AES-GCM ciphertext of the EDN `{:email :password}`
   [:cronometer-connection/ciphertext :string]
   ;; base64 random 12-byte nonce
   [:cronometer-connection/nonce :string]
   [:cronometer-connection/key-version :int]])
