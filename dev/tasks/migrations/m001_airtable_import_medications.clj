(ns tasks.migrations.m001-airtable-import-medications
  "Import medication catalog entities from Airtable EDN export."
  (:require
   [tasks.util :as u]))

(defn run
  "Run the medication import migration."
  [{:keys [file email _db _ctx]}]
  (u/print-cyan "Running m001-airtable-import-medications")
  (println "  File:" file)
  (println "  Email:" email)
  (println "  (not yet implemented)"))
