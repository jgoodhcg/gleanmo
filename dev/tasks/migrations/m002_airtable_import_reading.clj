(ns tasks.migrations.m002-airtable-import-reading
  "Airtable import for book-source, book, and reading-log entities.

   Unlike m001 (medications), reading has two separate Airtable tables:
   books and reading-log. Book-source entities are extracted from the
   'from' field in the books table. Location entities are reconciled
   against existing DB locations by label, creating new ones only for
   unmatched labels."
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
  "Import book-sources, books, locations, and reading-logs from Airtable EDN exports.

   Requires --books-file and --logs-file options.
   In production (--target prod), throws if mapped location labels are missing from DB.
   In dev, creates any missing locations automatically."
  [{:keys [email db ctx dry-run target options]}]
  (let [books-file (:books-file options)
        logs-file  (:logs-file options)
        strict?    (= target "prod")]
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

              ;; Phase 1: Extract and transform book-sources from "from" field
              _                (u/print-cyan "  Phase 1: Extracting book-sources...")
              source-labels    (reading/extract-unique-book-sources books-file)
              source-docs      (->> source-labels
                                    (map #(reading/airtable->book-source % user-id now))
                                    (remove nil?)
                                    vec)
              source-validation (validate-docs source-docs
                                              reading/validate-book-sources
                                              :book-source/label)

              ;; Phase 2: Transform books
              _          (u/print-cyan "  Phase 2: Transforming books...")
              book-records    (core/read-airtable-file books-file)
              book-docs       (->> book-records
                                   (map #(reading/airtable->book % user-id now))
                                   (remove nil?)
                                   vec)
              book-validation (validate-docs book-docs
                                             reading/validate-books
                                             :book/title)

              ;; Phase 3: Reconcile locations from reading-log data
              _               (u/print-cyan (str "  Phase 3: Reconciling locations"
                                                 (when strict? " (strict/prod mode)")
                                                 "..."))
              location-labels (reading/extract-unique-location-labels logs-file)
              location-lookup (reading/build-location-label->uuid db user-id location-labels strict?)
              new-loc-docs    (reading/locations-to-create db user-id location-labels now)
              loc-validation  (if (seq new-loc-docs)
                                (validate-docs new-loc-docs
                                               reading/validate-locations
                                               :location/label)
                                {:passed [] :failed []})

              ;; Phase 4: Build lookup and transform reading-logs
              _               (u/print-cyan "  Phase 4: Transforming reading-logs...")
              airtable-book-lookup (reading/build-airtable-book-id-lookup books-file)
              log-records     (core/read-airtable-file logs-file)
              log-docs        (->> log-records
                                   (map #(reading/airtable->reading-log
                                          % user-id airtable-book-lookup location-lookup now))
                                   (remove nil?)
                                   vec)
              log-validation  (validate-docs log-docs
                                             reading/validate-reading-logs
                                             :airtable/id)

              ;; Write artifacts
              sources-path    (str output-dir "/book-sources-to-write.edn")
              books-path      (str output-dir "/books-to-write.edn")
              locations-path  (str output-dir "/locations-to-create.edn")
              logs-path       (str output-dir "/reading-logs-to-write.edn")
              rejected-sources (->> (:failed source-validation)
                                    (map (fn [doc]
                                           {:reason            :invalid-book-source-doc
                                            :book-source/label (:book-source/label doc)
                                            :doc               doc}))
                                    vec)
              rejected-books  (->> (:failed book-validation)
                                   (map (fn [doc]
                                          {:reason     :invalid-book-doc
                                           :book/title (:book/title doc)
                                           :doc        doc}))
                                   vec)
              rejected-locs   (->> (:failed loc-validation)
                                   (map (fn [doc]
                                          {:reason          :invalid-location-doc
                                           :location/label  (:location/label doc)
                                           :doc             doc}))
                                   vec)
              rejected-logs   (->> (:failed log-validation)
                                   (map (fn [doc]
                                          {:reason      :invalid-reading-log-doc
                                           :airtable/id (:airtable/id doc)
                                           :doc         doc}))
                                   vec)
              all-rejected    (vec (concat rejected-sources rejected-books
                                          rejected-locs rejected-logs))
              rejected-path   (str output-dir "/rejected-rows.edn")
              matched-count   (- (count location-labels) (count new-loc-docs))
              report          {:generated-at   now
                               :mode           (if dry-run :dry-run :write)
                               :books-file     books-file
                               :logs-file      logs-file
                               :book-sources   {:labels (count source-labels)
                                                :transformed (count source-docs)
                                                :valid (count (:passed source-validation))
                                                :failed (count (:failed source-validation))}
                               :books          {:records (count book-records)
                                                :transformed (count book-docs)
                                                :valid (count (:passed book-validation))
                                                :failed (count (:failed book-validation))}
                               :locations      {:labels (count location-labels)
                                                :matched-existing matched-count
                                                :new-to-create (count new-loc-docs)
                                                :valid (count (:passed loc-validation))
                                                :failed (count (:failed loc-validation))}
                               :reading-logs   {:records (count log-records)
                                                :transformed (count log-docs)
                                                :valid (count (:passed log-validation))
                                                :failed (count (:failed log-validation))}
                               :rejected-total (count all-rejected)}
              report-path     (str output-dir "/migration-report.edn")]

          (write-edn! sources-path (:passed source-validation))
          (write-edn! books-path (:passed book-validation))
          (write-edn! locations-path (:passed loc-validation))
          (write-edn! logs-path (:passed log-validation))
          (write-edn! rejected-path all-rejected)
          (write-edn! report-path report)

          (println)
          (u/print-green (str "  Book-source labels found: " (count source-labels)))
          (println "    Transformed:" (count source-docs))
          (println "    Valid:" (count (:passed source-validation)))
          (println "    Failed:" (count (:failed source-validation)))
          (println)
          (u/print-green (str "  Book records loaded: " (count book-records)))
          (println "    Transformed:" (count book-docs))
          (println "    Valid:" (count (:passed book-validation)))
          (println "    Failed:" (count (:failed book-validation)))
          (println)
          (u/print-green (str "  Location labels found: " (count location-labels)))
          (println "    Matched existing:" matched-count)
          (println "    New to create:" (count new-loc-docs))
          (when (seq new-loc-docs)
            (println "    Valid:" (count (:passed loc-validation)))
            (println "    Failed:" (count (:failed loc-validation))))
          (println)
          (u/print-green (str "  Reading-log records loaded: " (count log-records)))
          (println "    Transformed:" (count log-docs))
          (println "    Valid:" (count (:passed log-validation)))
          (println "    Failed:" (count (:failed log-validation)))
          (println)
          (println "  Wrote artifacts:")
          (println "   -" sources-path)
          (println "   -" books-path)
          (println "   -" locations-path)
          (println "   -" logs-path)
          (println "   -" rejected-path)
          (println "   -" report-path)

          (if dry-run
            (u/print-yellow "  Dry-run mode — skipping database writes.")
            (let [loc-docs-to-write    (:passed loc-validation)
                  source-docs-to-write (:passed source-validation)
                  book-docs-to-write   (:passed book-validation)
                  log-docs-to-write    (:passed log-validation)
                  log-batches          (partition-all 1000 log-docs-to-write)]
              (println)
              (u/print-cyan "  Writing to database...")
              (when (seq loc-docs-to-write)
                (biff/submit-tx ctx loc-docs-to-write)
                (u/print-green (str "  Wrote " (count loc-docs-to-write) " new locations.")))
              (biff/submit-tx ctx source-docs-to-write)
              (u/print-green (str "  Wrote " (count source-docs-to-write) " book-sources."))
              (biff/submit-tx ctx book-docs-to-write)
              (u/print-green (str "  Wrote " (count book-docs-to-write) " books."))
              (doseq [[i batch] (map-indexed vector log-batches)]
                (biff/submit-tx ctx (vec batch))
                (u/print-green (str "  Wrote log batch " (inc i) "/" (count log-batches)
                                    " (" (count batch) " docs)")))
              (println)
              (u/print-green
               (str "  Write complete: "
                    (count loc-docs-to-write) " locations, "
                    (count source-docs-to-write) " book-sources, "
                    (count book-docs-to-write) " books, "
                    (count log-docs-to-write) " reading-logs ("
                    (count log-batches) " batches).")))))))))
