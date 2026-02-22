(ns repl.airtable.medication-runner
  "DEPRECATED: Interactive REPL runner for medication Airtable migration.

   This REPL-based workflow is superseded by the CLI migration task.
   Use: clj -M:dev migrate-airtable --entity medication --file <path> --target dev|prod --user-id <uuid>

   Preserved for reference only."
  {:deprecated "Use `clj -M:dev migrate-airtable` CLI task instead."}
  (:require
   [com.biffweb :as biff :refer [q]]
   [repl.airtable.core :as core]
   [repl.airtable.medication :as med]
   [repl :refer [prod-node-start get-prod-db-context get-context]]))

;; =============================================================================
;; CONFIG - Edit these values
;; =============================================================================

(def config
  {:file-path "airtable_data/medication_log_XXXX.edn" ;; <-- YOUR FILE
   :email "YOUR_EMAIL_HERE"}) ;; <-- YOUR EMAIL

;; =============================================================================
;; Step 1: Verify the file - check field keys
;; =============================================================================

(comment
  (core/get-field-keys (:file-path config))
  ;; Expected: #{"medication" "dosage" "unit" "timestamp" "notes" "injection-site" ...}
  ;;
  )

;; =============================================================================
;; Step 2: Preview with LOCAL dev database (safe, no prod changes)
;; =============================================================================

(comment
  ;; Get local context
  (def local-ctx (get-context))

  ;; Check your user exists
  (q (:biff/db local-ctx)
     '{:find (pull ?e [:xt/id :user/email])
       :where [[?e :user/email email]]
       :in [email]}
     (:email config))

  ;; Preview medications that will be created
  (let [user-id (:xt/id (first (q (:biff/db local-ctx)
                                  '{:find (pull ?e [*])
                                    :where [[?e :user/email email]]
                                    :in [email]}
                                  (:email config))))]
    (med/convert-airtable-medications (:file-path config) user-id))

  ;; Preview medication logs (first 3)
  (let [db (:biff/db local-ctx)
        user-id (:xt/id (first (q db
                                  '{:find (pull ?e [*])
                                    :where [[?e :user/email email]]
                                    :in [email]}
                                  (:email config))))]
    (->> (med/convert-airtable-medication-logs (:file-path config) user-id db)
         (take 3)))
  ;;
  )

;; =============================================================================
;; Step 3: Connect to PRODUCTION database
;; =============================================================================

(comment
  ;; Only run this ONCE per REPL session
  (def prod-node (prod-node-start))
  (def prod-ctx (get-prod-db-context prod-node))

  ;; Verify connection - check your user exists in prod
  (q (:biff/db prod-ctx)
     '{:find (pull ?e [:xt/id :user/email])
       :where [[?e :user/email email]]
       :in [email]}
     (:email config))
  ;;
  )

;; =============================================================================
;; Step 4: Write to PRODUCTION - Medications first (catalog)
;; =============================================================================

(comment
  ;; This creates the medication catalog entries
  ;; Run this BEFORE medication logs
  (med/write-medications-to-db prod-ctx (:file-path config) (:email config))
  ;;
  )

;; =============================================================================
;; Step 5: Write to PRODUCTION - Medication logs
;; =============================================================================

(comment
  ;; This creates the medication log entries
  ;; Run this AFTER medications
  (med/write-medication-logs-to-db prod-ctx (:file-path config) (:email config))
  ;;
  )

;; =============================================================================
;; Step 6: Verify (optional)
;; =============================================================================

(comment
  ;; Count medications in prod
  (q (:biff/db prod-ctx)
     '{:find [(count ?e)]
       :where [[?e :tech.jgood.gleanmo.schema.meta/type :medication]]})

  ;; Count medication logs in prod
  (q (:biff/db prod-ctx)
     '{:find [(count ?e)]
       :where [[?e :tech.jgood.gleanmo.schema.meta/type :medication-log]]})
  ;;
  )
