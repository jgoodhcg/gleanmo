(ns tasks.migrations.m004-airtable-import-bouldering
  "Airtable import for bouldering: problems + tries -> boulder-problem,
   synthesized boulder-session (one per Airtable day), boulder-attempt.

   Usage:
     clj -M:dev migrate m004-airtable-import-bouldering \\
       --problems-file airtable_data/bouldering_problems_xxx.edn \\
       --tries-file airtable_data/bouldering_tries_xxx.edn \\
       --email user@example.com --target dev --dry-run"
  (:require
   [clojure.java.io :as io]
   [clojure.pprint :as pp]
   [clojure.string :as str]
   [com.biffweb :as biff :refer [q]]
   [repl.airtable.bouldering :as bouldering]
   [repl.airtable.core :as core]
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

(defn run
  [{:keys [email db ctx dry-run options]}]
  (let [problems-file (:problems-file options)
        tries-file    (:tries-file options)]
    (u/print-cyan "Running m004-airtable-import-bouldering")
    (println "  Problems file:" (or problems-file "(none)"))
    (println "  Tries file:" (or tries-file "(none)"))
    (let [user (when-not (str/blank? email) (find-user-by-email db email))]
      (cond
        (nil? user)
        (u/print-red (str "  ERROR: no user for email " email))

        (some #(or (str/blank? %) (not (.isFile (io/file %))))
              [problems-file tries-file])
        (u/print-red "  ERROR: --problems-file and --tries-file are required")

        :else
        (let [user-id      (:xt/id user)
              now          (t/now)
              output-dir   "tmp/migrations/bouldering"
              problem-recs (core/read-airtable-file problems-file)
              try-recs     (core/read-airtable-file tries-file)

              problem-docs (mapv #(bouldering/airtable->problem % user-id now)
                                 problem-recs)
              gym-lookup   (bouldering/problem-gym-lookup problem-recs)
              session-docs (bouldering/tries->sessions try-recs gym-lookup
                                                       user-id now)
              attempt-docs (mapv #(bouldering/airtable->attempt % user-id now)
                                 try-recs)

              prob-val (bouldering/validate-problems (map strip-doc-type problem-docs))
              sess-val (bouldering/validate-sessions (map strip-doc-type session-docs))
              att-val  (bouldering/validate-attempts (map strip-doc-type attempt-docs))

              problems (split-validated problem-docs prob-val :airtable/id)
              sessions (split-validated session-docs sess-val :xt/id)
              attempts (split-validated attempt-docs att-val :airtable/id)

              report {:generated-at now
                      :mode         (if dry-run :dry-run :write)
                      :problems     {:records (count problem-recs)
                                     :valid   (count (:passed problems))
                                     :failed  (count (:failed problems))}
                      :sessions     {:synthesized (count session-docs)
                                     :valid       (count (:passed sessions))
                                     :failed      (count (:failed sessions))}
                      :attempts     {:records (count try-recs)
                                     :valid   (count (:passed attempts))
                                     :failed  (count (:failed attempts))}}]
          (write-edn! (str output-dir "/problems-to-write.edn") (:passed problems))
          (write-edn! (str output-dir "/sessions-to-write.edn") (:passed sessions))
          (write-edn! (str output-dir "/attempts-to-write.edn") (:passed attempts))
          (write-edn! (str output-dir "/rejected-rows.edn")
                      (vec (concat (:failed problems) (:failed sessions)
                                   (:failed attempts))))
          (write-edn! (str output-dir "/migration-report.edn") report)
          (u/print-green (str "  Problems: " (count problem-recs)
                              " valid: " (count (:passed problems))
                              " failed: " (count (:failed problems))))
          (u/print-green (str "  Sessions synthesized: " (count session-docs)
                              " valid: " (count (:passed sessions))
                              " failed: " (count (:failed sessions))))
          (u/print-green (str "  Attempts: " (count try-recs)
                              " valid: " (count (:passed attempts))
                              " failed: " (count (:failed attempts))))
          (println "  Artifacts written to" output-dir)
          (if dry-run
            (u/print-yellow "  Dry-run mode — skipping database writes.")
            (do
              (u/print-cyan "  Writing to database...")
              (biff/submit-tx ctx (:passed problems))
              (u/print-green (str "  Wrote " (count (:passed problems)) " problems."))
              (biff/submit-tx ctx (:passed sessions))
              (u/print-green (str "  Wrote " (count (:passed sessions)) " sessions."))
              (doseq [[i batch] (map-indexed vector
                                             (partition-all 1000 (:passed attempts)))]
                (biff/submit-tx ctx (vec batch))
                (u/print-green (str "  Wrote attempt batch " (inc i)
                                    " (" (count batch) " docs)")))
              (u/print-green "  Write complete."))))))))
