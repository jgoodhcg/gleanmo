(ns tasks.migrations.m002-airtable-import-reading
  "Airtable import for book and reading-log entities.

   Unlike m001 (medications), reading has two separate Airtable tables:
   books and reading-log. No label reconciliation is needed since books
   are a standalone table with their own Airtable IDs."
  (:require
   [clojure.java.io :as io]
   [clojure.pprint :as pp]
   [clojure.string :as str]
   [com.biffweb :as biff :refer [q]]
   [repl.airtable.core :as core]
   [repl.airtable.reading :as reading]
   [tick.core :as t]
   [tasks.util :as u]))

(defn- find-user-by-email
  [db email]
  (first (q db
            '{:find  (pull ?e [:xt/id :user/email])
              :where [[?e :user/email email]]
              :in    [email]}
            email)))

(defn- file-error
  [message]
  (u/print-red (str "  ERROR: " message))
  false)

(defn- valid-file?
  [file-path label]
  (cond
    (str/blank? file-path)
    (file-error (str label " is required"))

    (not (.exists (io/file file-path)))
    (file-error (str label " not found: " file-path))

    (not (.isFile (io/file file-path)))
    (file-error (str label " is not a regular file: " file-path))

    (not (.canRead (io/file file-path)))
    (file-error (str label " is not readable: " file-path))

    :else
    true))

(defn- write-edn!
  [path data]
  (io/make-parents path)
  (spit path (with-out-str (pp/pprint data))))

(defn- strip-doc-type
  [doc]
  (dissoc doc :db/doc-type))

(defn- validate-docs
  [docs validate-fn id-key]
  (let [sanitized  (mapv strip-doc-type docs)
        validation (validate-fn sanitized)
        failed-set (set (map id-key (:failed validation)))
        failed     (->> docs
                        (filter #(contains? failed-set (id-key %)))
                        vec)
        passed     (->> docs
                        (remove #(contains? failed-set (id-key %)))
                        vec)]
    {:validation validation
     :passed     passed
     :failed     failed}))

(defn run
  "Import books and reading-logs from Airtable EDN exports.

   Requires --books-file and --logs-file options."
  [{:keys [email db ctx dry-run options]}]
  (let [books-file (:books-file options)
        logs-file  (:logs-file options)]
    (u/print-cyan "Running m002-airtable-import-reading")
    (println "  Books file:" (or books-file "(none)"))
    (println "  Logs file:" (or logs-file "(none)"))
    (println "  Email:" email)

    (let [user (cond
                 (str/blank? email)
                 (do
                   (u/print-red "  ERROR: --email is required for user lookup")
                   nil)

                 :else
                 (if-let [found-user (find-user-by-email db email)]
                   (do
                     (u/print-green (str "  Found user: " (:xt/id found-user)))
                     (println "  Email:" (:user/email found-user))
                     found-user)
                   (do
                     (u/print-yellow (str "  No user found for email: " email))
                     nil)))]
      (when (and user
                 (valid-file? books-file "--books-file")
                 (valid-file? logs-file "--logs-file"))
        (let [user-id    (:xt/id user)
              output-dir "tmp/migrations/reading"
              now        (t/now)

              ;; Phase 1: Transform books
              _          (u/print-cyan "  Phase 1: Transforming books...")
              book-records    (core/read-airtable-file books-file)
              book-docs       (->> book-records
                                   (map #(reading/airtable->book % user-id now))
                                   (remove nil?)
                                   vec)
              book-validation (validate-docs book-docs
                                             reading/validate-books
                                             :book/title)

              ;; Phase 2: Build lookup and transform reading-logs
              _               (u/print-cyan "  Phase 2: Transforming reading-logs...")
              airtable-book-lookup (reading/build-airtable-book-id-lookup books-file)
              log-records     (core/read-airtable-file logs-file)
              log-docs        (->> log-records
                                   (map #(reading/airtable->reading-log
                                          % user-id airtable-book-lookup now))
                                   (remove nil?)
                                   vec)
              log-validation  (validate-docs log-docs
                                             reading/validate-reading-logs
                                             :airtable/id)

              ;; Write artifacts
              books-path      (str output-dir "/books-to-write.edn")
              logs-path       (str output-dir "/reading-logs-to-write.edn")
              rejected-books  (->> (:failed book-validation)
                                   (map (fn [doc]
                                          {:reason     :invalid-book-doc
                                           :book/title (:book/title doc)
                                           :doc        doc}))
                                   vec)
              rejected-logs   (->> (:failed log-validation)
                                   (map (fn [doc]
                                          {:reason      :invalid-reading-log-doc
                                           :airtable/id (:airtable/id doc)
                                           :doc         doc}))
                                   vec)
              all-rejected    (vec (concat rejected-books rejected-logs))
              rejected-path   (str output-dir "/rejected-rows.edn")
              report          {:generated-at  now
                               :mode          (if dry-run :dry-run :write)
                               :books-file    books-file
                               :logs-file     logs-file
                               :books         {:records (count book-records)
                                               :transformed (count book-docs)
                                               :valid (count (:passed book-validation))
                                               :failed (count (:failed book-validation))}
                               :reading-logs  {:records (count log-records)
                                               :transformed (count log-docs)
                                               :valid (count (:passed log-validation))
                                               :failed (count (:failed log-validation))}
                               :rejected-total (count all-rejected)}
              report-path     (str output-dir "/migration-report.edn")]

          (write-edn! books-path (:passed book-validation))
          (write-edn! logs-path (:passed log-validation))
          (write-edn! rejected-path all-rejected)
          (write-edn! report-path report)

          (println)
          (u/print-green (str "  Book records loaded: " (count book-records)))
          (println "    Transformed:" (count book-docs))
          (println "    Valid:" (count (:passed book-validation)))
          (println "    Failed:" (count (:failed book-validation)))
          (println)
          (u/print-green (str "  Reading-log records loaded: " (count log-records)))
          (println "    Transformed:" (count log-docs))
          (println "    Valid:" (count (:passed log-validation)))
          (println "    Failed:" (count (:failed log-validation)))
          (println)
          (println "  Wrote artifacts:")
          (println "   -" books-path)
          (println "   -" logs-path)
          (println "   -" rejected-path)
          (println "   -" report-path)

          (if dry-run
            (u/print-yellow "  Dry-run mode — skipping database writes.")
            (let [book-docs-to-write (:passed book-validation)
                  log-docs-to-write  (:passed log-validation)
                  log-batches        (partition-all 1000 log-docs-to-write)]
              (println)
              (u/print-cyan "  Writing to database...")
              (biff/submit-tx ctx book-docs-to-write)
              (u/print-green (str "  Wrote " (count book-docs-to-write) " books."))
              (doseq [[i batch] (map-indexed vector log-batches)]
                (biff/submit-tx ctx (vec batch))
                (u/print-green (str "  Wrote log batch " (inc i) "/" (count log-batches)
                                    " (" (count batch) " docs)")))
              (println)
              (u/print-green
               (str "  Write complete: "
                    (count book-docs-to-write) " books, "
                    (count log-docs-to-write) " reading-logs ("
                    (count log-batches) " batches).")))))))))
