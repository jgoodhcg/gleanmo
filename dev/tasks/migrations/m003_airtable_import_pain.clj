(ns tasks.migrations.m003-airtable-import-pain
  "Airtable import for the pain log -> symptom-log entities (type :pain).

   Usage:
     clj -M:dev migrate m003-airtable-import-pain \\
       --file airtable_data/pain_log_xxx.edn \\
       --email user@example.com --target dev --dry-run"
  (:require
   [clojure.java.io :as io]
   [clojure.pprint :as pp]
   [clojure.string :as str]
   [com.biffweb :as biff :refer [q]]
   [repl.airtable.core :as core]
   [repl.airtable.symptom :as symptom]
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
  [{:keys [email db ctx dry-run file]}]
  (u/print-cyan "Running m003-airtable-import-pain")
  (println "  File:" (or file "(none)"))
  (let [user (when-not (str/blank? email) (find-user-by-email db email))]
    (cond
      (nil? user)
      (u/print-red (str "  ERROR: no user for email " email))

      (or (str/blank? file) (not (.isFile (io/file file))))
      (u/print-red (str "  ERROR: --file missing or unreadable: " file))

      :else
      (let [user-id    (:xt/id user)
            now        (t/now)
            output-dir "tmp/migrations/pain"
            records    (core/read-airtable-file file)
            docs       (mapv #(symptom/airtable->symptom-log % user-id now) records)
            validation (symptom/validate-symptom-logs (map strip-doc-type docs))
            failed-ids (set (map :airtable/id (:failed validation)))
            passed     (vec (remove #(failed-ids (:airtable/id %)) docs))
            failed     (vec (filter #(failed-ids (:airtable/id %)) docs))
            report     {:generated-at now
                        :mode         (if dry-run :dry-run :write)
                        :file         file
                        :records      (count records)
                        :valid        (count passed)
                        :failed       (count failed)}]
        (write-edn! (str output-dir "/symptom-logs-to-write.edn") passed)
        (write-edn! (str output-dir "/rejected-rows.edn") failed)
        (write-edn! (str output-dir "/migration-report.edn") report)
        (u/print-green (str "  Records: " (count records)
                            " valid: " (count passed)
                            " failed: " (count failed)))
        (println "  Artifacts written to" output-dir)
        (if dry-run
          (u/print-yellow "  Dry-run mode — skipping database writes.")
          (do
            (u/print-cyan "  Writing to database...")
            (doseq [[i batch] (map-indexed vector (partition-all 1000 passed))]
              (biff/submit-tx ctx (vec batch))
              (u/print-green (str "  Wrote batch " (inc i)
                                  " (" (count batch) " docs)")))
            (u/print-green (str "  Write complete: " (count passed)
                                " symptom-logs."))))))))
