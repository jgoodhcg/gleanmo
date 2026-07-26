(ns tasks.migrations.m005-airtable-import-exercise
  "Airtable import for exercise: exercises + exercise log -> exercise,
   synthesized exercise-session (gap-split), exercise-set, exercise-line.

   Usage:
     clj -M:dev migrate m005-airtable-import-exercise \\
       --exercises-file airtable_data/exercises_xxx.edn \\
       --logs-file airtable_data/exercise_log_xxx.edn \\
       --email user@example.com --target dev --dry-run"
  (:require
   [clojure.java.io :as io]
   [clojure.pprint :as pp]
   [clojure.string :as str]
   [com.biffweb :as biff :refer [q]]
   [repl.airtable.core :as core]
   [repl.airtable.exercise :as exercise]
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

(defn- split-validated
  [docs validation id-key]
  (let [failed-ids (set (map id-key (:failed validation)))]
    {:passed (vec (remove #(failed-ids (id-key %)) docs))
     :failed (vec (filter #(failed-ids (id-key %)) docs))}))

(defn- submit-batched!
  [ctx docs label]
  (doseq [[i batch] (map-indexed vector (partition-all 1000 docs))]
    (biff/submit-tx ctx (vec batch))
    (u/print-green (str "  Wrote " label " batch " (inc i)
                        " (" (count batch) " docs)"))))

(defn run
  [{:keys [email db ctx dry-run options]}]
  (let [exercises-file (:exercises-file options)
        logs-file      (:logs-file options)]
    (u/print-cyan "Running m005-airtable-import-exercise")
    (println "  Exercises file:" (or exercises-file "(none)"))
    (println "  Logs file:" (or logs-file "(none)"))
    (let [user (when-not (str/blank? email) (find-user-by-email db email))]
      (cond
        (nil? user)
        (u/print-red (str "  ERROR: no user for email " email))

        (some #(or (str/blank? %) (not (.isFile (io/file %))))
              [exercises-file logs-file])
        (u/print-red "  ERROR: --exercises-file and --logs-file are required")

        :else
        (let [user-id       (:xt/id user)
              now           (t/now)
              output-dir    "tmp/migrations/exercise"
              exercise-recs (core/read-airtable-file exercises-file)
              log-recs      (core/read-airtable-file logs-file)

              exercise-docs (vec (keep #(exercise/airtable->exercise % user-id now)
                                       exercise-recs))
              {importable true skipped false}
              (group-by exercise/importable-log? log-recs)
              segments      (exercise/segment-logs importable)
              session-docs  (mapv #(exercise/segment->session % user-id now)
                                  segments)
              set-docs      (vec (mapcat
                                  (fn [session segment]
                                    (map #(exercise/log->set % (:xt/id session)
                                                             user-id now)
                                         segment))
                                  session-docs
                                  segments))
              line-docs     (mapv #(exercise/log->line % user-id now) importable)

              ex-val   (exercise/validate-exercises (map strip-doc-type exercise-docs))
              sess-val (exercise/validate-sessions (map strip-doc-type session-docs))
              set-val  (exercise/validate-sets (map strip-doc-type set-docs))
              line-val (exercise/validate-lines (map strip-doc-type line-docs))

              exercises (split-validated exercise-docs ex-val :airtable/id)
              sessions  (split-validated session-docs sess-val :xt/id)
              sets      (split-validated set-docs set-val :airtable/id)
              lines     (split-validated line-docs line-val :airtable/id)

              report {:generated-at now
                      :mode         (if dry-run :dry-run :write)
                      :exercises    {:records (count exercise-recs)
                                     :imported (count exercise-docs)
                                     :skipped-nameless-unused (- (count exercise-recs)
                                                                 (count exercise-docs))
                                     :valid   (count (:passed exercises))
                                     :failed  (count (:failed exercises))}
                      :logs         {:records (count log-recs)
                                     :importable (count importable)
                                     :skipped (count skipped)}
                      :sessions     {:synthesized (count session-docs)
                                     :valid       (count (:passed sessions))
                                     :failed      (count (:failed sessions))}
                      :sets         {:valid  (count (:passed sets))
                                     :failed (count (:failed sets))}
                      :lines        {:valid  (count (:passed lines))
                                     :failed (count (:failed lines))}}]
          (write-edn! (str output-dir "/exercises-to-write.edn") (:passed exercises))
          (write-edn! (str output-dir "/sessions-to-write.edn") (:passed sessions))
          (write-edn! (str output-dir "/sets-to-write.edn") (:passed sets))
          (write-edn! (str output-dir "/lines-to-write.edn") (:passed lines))
          (write-edn! (str output-dir "/skipped-log-rows.edn") (vec skipped))
          (write-edn! (str output-dir "/rejected-rows.edn")
                      (vec (concat (:failed exercises) (:failed sessions)
                                   (:failed sets) (:failed lines))))
          (write-edn! (str output-dir "/migration-report.edn") report)
          (u/print-green (str "  Exercises: " (count exercise-recs)
                              " imported: " (count exercise-docs)
                              " valid: " (count (:passed exercises))
                              " failed: " (count (:failed exercises))))
          (u/print-green (str "  Logs: " (count log-recs)
                              " importable: " (count importable)
                              " skipped (no exercise/timestamp): " (count skipped)))
          (u/print-green (str "  Sessions synthesized: " (count session-docs)
                              " valid: " (count (:passed sessions))
                              " failed: " (count (:failed sessions))))
          (u/print-green (str "  Sets valid: " (count (:passed sets))
                              " failed: " (count (:failed sets))))
          (u/print-green (str "  Lines valid: " (count (:passed lines))
                              " failed: " (count (:failed lines))))
          (println "  Artifacts written to" output-dir)
          (if dry-run
            (u/print-yellow "  Dry-run mode — skipping database writes.")
            (do
              (u/print-cyan "  Writing to database...")
              (biff/submit-tx ctx (:passed exercises))
              (u/print-green (str "  Wrote " (count (:passed exercises)) " exercises."))
              (submit-batched! ctx (:passed sessions) "session")
              (submit-batched! ctx (:passed sets) "set")
              (submit-batched! ctx (:passed lines) "line")
              (u/print-green "  Write complete."))))))))
