(ns tasks.migrations.m006-airtable-import-mood
  "Airtable import for the mood log -> mood-log entities (circumplex
   valence/arousal derived from Plutchik labels).

   Usage:
     clj -M:dev migrate m006-airtable-import-mood \\
       --moods-file airtable_data/moods_xxx.edn \\
       --logs-file airtable_data/mood_log_xxx.edn \\
       --email user@example.com --target dev --dry-run"
  (:require
   [clojure.java.io :as io]
   [clojure.pprint :as pp]
   [clojure.string :as str]
   [com.biffweb :as biff :refer [q]]
   [repl.airtable.core :as core]
   [repl.airtable.mood :as mood]
   [tasks.util :as u]
   [tick.core :as t]))

(defn- find-user-by-email
  [db email]
  (first (q db
            '{:find  (pull ?e [:xt/id :user/email])
              :where [[?e :user/email email]]
              :in    [email]}
            email)))

(defn- write-edn!
  [path data]
  (io/make-parents path)
  (spit path (with-out-str (pp/pprint data))))

(defn- strip-doc-type [doc] (dissoc doc :db/doc-type))

(defn run
  [{:keys [email db ctx dry-run options]}]
  (let [moods-file (:moods-file options)
        logs-file  (:logs-file options)]
    (u/print-cyan "Running m006-airtable-import-mood")
    (println "  Moods file:" (or moods-file "(none)"))
    (println "  Logs file:" (or logs-file "(none)"))
    (let [user (when-not (str/blank? email) (find-user-by-email db email))]
      (cond
        (nil? user)
        (u/print-red (str "  ERROR: no user for email " email))

        (some #(or (str/blank? %) (not (.isFile (io/file %))))
              [moods-file logs-file])
        (u/print-red "  ERROR: --moods-file and --logs-file are required")

        :else
        (let [user-id    (:xt/id user)
              now        (t/now)
              output-dir "tmp/migrations/mood"
              mood-recs  (core/read-airtable-file moods-file)
              log-recs   (core/read-airtable-file logs-file)
              lookup     (mood/mood-lookup mood-recs)
              docs       (vec (keep #(mood/airtable->mood-log % lookup user-id now)
                                    log-recs))
              skipped    (vec (remove #(get-in % ["fields" "mood"]) log-recs))
              validation (mood/validate-mood-logs (map strip-doc-type docs))
              failed-ids (set (map :airtable/id (:failed validation)))
              passed     (vec (remove #(failed-ids (:airtable/id %)) docs))
              failed     (vec (filter #(failed-ids (:airtable/id %)) docs))
              report     {:generated-at now
                          :mode         (if dry-run :dry-run :write)
                          :records      (count log-recs)
                          :imported     (count docs)
                          :skipped-no-mood (count skipped)
                          :valid        (count passed)
                          :failed       (count failed)}]
          (write-edn! (str output-dir "/mood-logs-to-write.edn") passed)
          (write-edn! (str output-dir "/skipped-rows.edn") skipped)
          (write-edn! (str output-dir "/rejected-rows.edn") failed)
          (write-edn! (str output-dir "/migration-report.edn") report)
          (u/print-green (str "  Records: " (count log-recs)
                              " imported: " (count docs)
                              " skipped (no mood link): " (count skipped)
                              " valid: " (count passed)
                              " failed: " (count failed)))
          (println "  Artifacts written to" output-dir)
          (if dry-run
            (u/print-yellow "  Dry-run mode — skipping database writes.")
            (do
              (u/print-cyan "  Writing to database...")
              (biff/submit-tx ctx passed)
              (u/print-green (str "  Write complete: " (count passed)
                                  " mood-logs.")))))))))
