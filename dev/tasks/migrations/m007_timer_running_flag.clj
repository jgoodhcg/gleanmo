(ns tasks.migrations.m007-timer-running-flag
  "Backfill `<entity>/running` on timers that are open right now.

   The read path filters on that flag, so between the deploy and this run any
   timer that was already running is invisible in the UI — the documents are
   untouched, but nothing carries the flag yet. Stopping every running timer
   before deploying is what makes that window empty rather than alarming.

   Idempotent by construction: it repairs exactly the disagreements
   `queries/running-flag-audit` reports, so a second run finds none and writes
   nothing. Locally the dev server must be stopped first — RocksDB takes an
   exclusive lock.

   Usage:
     clj -M:dev migrate m007-timer-running-flag --target dev
     clj -M:dev migrate m007-timer-running-flag --target dev --dry-run"
  (:require
   [tasks.util :as u]
   [tech.jgood.gleanmo.db.mutations :as mutations]
   [tech.jgood.gleanmo.db.queries :as queries]
   [tech.jgood.gleanmo.schema.utils :as schema-utils]))

(defn run
  [{:keys [ctx db dry-run]}]
  (u/print-cyan "Running m007-timer-running-flag")
  (let [report
        (into
         {}
         (for [[entity-key {:keys [beginning-key end-key running-key]}]
               schema-utils/running-flag-entities]
           (let [{:keys [missing phantom]} (queries/running-flag-audit
                                            db beginning-key end-key
                                            running-key)]
             (println (str "  " entity-key
                           ": " (count missing) " to flag, "
                           (count phantom) " to clear"))
             (when-not dry-run
               (doseq [[ids value] [[missing true] [phantom :db/dissoc]]
                       id          ids]
                 (mutations/update-entity! ctx {:entity-key entity-key,
                                                :entity-id  id,
                                                :data       {running-key
                                                             value}})))
             [entity-key {:flagged (count missing),
                          :cleared (count phantom)}])))
        total  (reduce + (mapcat vals (vals report)))]
    (if dry-run
      (u/print-yellow "  Dry-run mode — skipping database writes.")
      (u/print-green (str "  Write complete: " total " document(s) updated.")))))
