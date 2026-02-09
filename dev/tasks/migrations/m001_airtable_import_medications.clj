(ns tasks.migrations.m001-airtable-import-medications
  "Prepare slim label-mapping artifacts for medication Airtable import."
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.pprint :as pp]
   [clojure.string :as str]
   [com.biffweb :refer [q]]
   [repl.airtable.core :as core]
   [repl.airtable.medication :as med]
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

(defn- normalize-label
  [label]
  (some-> label str/trim str/lower-case))

(defn- find-existing-medications
  [db user-id]
  (->> (q db
          '{:find  (pull ?e [:xt/id :medication/label])
            :where [[?e :user/id user-id]
                    [?e :tech.jgood.gleanmo.schema.meta/type :medication]
                    (not [?e :tech.jgood.gleanmo.schema.meta/deleted-at])]
            :in    [user-id]}
          user-id)
       (remove (comp str/blank? :medication/label))
       (map (fn [{:keys [xt/id medication/label]}]
              {:xt/id            id
               :medication/label label
               :normalized-label (normalize-label label)}))
       (sort-by :normalized-label)
       vec))

(defn- by-normalized-label
  [medications]
  (reduce (fn [acc {:keys [normalized-label] :as med-doc}]
            (update acc normalized-label (fnil conj []) med-doc))
          {}
          medications))

(defn- write-edn!
  [path data]
  (io/make-parents path)
  (spit path (with-out-str (pp/pprint data))))

(defn- load-overrides
  [mapping-file]
  (if (str/blank? mapping-file)
    {}
    (let [path (io/file mapping-file)]
      (cond
        (not (.exists path))
        (do
          (u/print-red (str "  ERROR: --mapping-file not found: " mapping-file))
          ::invalid)

        (not (.isFile path))
        (do
          (u/print-red (str "  ERROR: --mapping-file is not a regular file: " mapping-file))
          ::invalid)

        (not (.canRead path))
        (do
          (u/print-red (str "  ERROR: --mapping-file is not readable: " mapping-file))
          ::invalid)

        :else
        (let [data (edn/read-string (slurp path))]
          (if (map? data)
            data
            (do
              (u/print-red "  ERROR: --mapping-file must contain an EDN map")
              ::invalid)))))))

(defn- override-error
  [label reason value]
  {:airtable-label label
   :status         :error
   :reason         reason
   :value          value})

(defn- resolve-label
  [label overrides existing-by-normalized]
  (let [normalized      (normalize-label label)
        override-sentinel ::no-override
        override        (get overrides label override-sentinel)
        auto-candidates (get existing-by-normalized normalized [])]
    (cond
      (= override :create-new)
      {:airtable-label   label
       :normalized-label normalized
       :status           :create-new
       :source           :override
       :resolved-id      (med/medication-uuid label)
       :resolved-label   (str/trim label)}

      (string? override)
      (let [target-candidates (get existing-by-normalized (normalize-label override) [])]
        (cond
          (= 1 (count target-candidates))
          (let [target-medication (first target-candidates)]
            {:airtable-label    label
             :normalized-label  normalized
             :status            :existing
             :source            :override
             :resolved-id       (:xt/id target-medication)
             :resolved-label    (:medication/label target-medication)})

          (zero? (count target-candidates))
          (override-error label :override-target-not-found override)

          :else
          (override-error label :override-target-ambiguous override)))

      (nil? override)
      {:airtable-label   label
       :normalized-label normalized
       :status           :unresolved
       :source           :override}

      (not= override override-sentinel)
      (override-error label :invalid-override-value override)

      (= 1 (count auto-candidates))
      (let [target-medication (first auto-candidates)]
        {:airtable-label   label
         :normalized-label normalized
         :status           :existing
         :source           :auto
         :resolved-id      (:xt/id target-medication)
         :resolved-label   (:medication/label target-medication)})

      (zero? (count auto-candidates))
      {:airtable-label   label
       :normalized-label normalized
       :status           :unresolved
       :source           :auto}

      :else
      (override-error label :auto-match-ambiguous (mapv :medication/label auto-candidates)))))

(defn- unresolved-overrides-skeleton
  [resolution]
  (->> resolution
       (filter #(= :unresolved (:status %)))
       (map :airtable-label)
       (sort-by normalize-label)
       (reduce (fn [m label]
                 (assoc m label nil))
               {})))

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

(defn- build-medications-to-upsert
  [resolution user-id]
  (let [now (t/now)]
    (->> resolution
         (filter #(= :create-new (:status %)))
         (map :resolved-label)
         distinct
         (map #(med/airtable->medication % user-id now))
         (remove nil?)
         vec)))

(defn- build-medication-log-docs
  [records resolution-by-label user-id]
  (let [now (t/now)]
    (reduce (fn [{:keys [logs rejected] :as acc} record]
              (let [airtable-id (get record "id")
                    label       (some-> (get-in record ["fields" "medication"])
                                        str/trim)
                    resolved    (get resolution-by-label label)]
                (cond
                  (str/blank? label)
                  (assoc acc :rejected
                         (conj rejected {:reason      :missing-medication-label
                                         :airtable/id airtable-id}))

                  (nil? resolved)
                  (assoc acc :rejected
                         (conj rejected {:reason            :label-not-in-resolution
                                         :airtable/id       airtable-id
                                         :airtable/med-label label}))

                  (not (contains? #{:existing :create-new} (:status resolved)))
                  (assoc acc :rejected
                         (conj rejected {:reason             :unresolved-label
                                         :airtable/id        airtable-id
                                         :airtable/med-label label
                                         :resolution-status  (:status resolved)}))

                  :else
                  (let [doc (med/airtable->medication-log record user-id (:resolved-id resolved) now)]
                    (if doc
                      (assoc acc :logs (conj logs doc))
                      (assoc acc :rejected
                             (conj rejected {:reason             :log-conversion-returned-nil
                                             :airtable/id        airtable-id
                                             :airtable/med-label label})))))))
            {:logs [] :rejected []}
            records)))

(defn- valid-file?
  [file-path]
  (cond
    (str/blank? file-path)
    (file-error "--file is required")

    (not (.exists (io/file file-path)))
    (file-error (str "--file not found: " file-path))

    (not (.isFile (io/file file-path)))
    (file-error (str "--file is not a regular file: " file-path))

    (not (.canRead (io/file file-path)))
    (file-error (str "--file is not readable: " file-path))

    :else
    true))

(defn run
  "Build Airtable vs existing medication mapping artifacts for manual reconciliation.

   Slim mapping format:
   {\"Airtable Label\" \"Existing Label\"
    \"Another Airtable Label\" :create-new}"
  [{:keys [file email db mapping-file]}]
  (u/print-cyan "Running m001-airtable-import-medications")
  (println "  File:" file)
  (println "  Email:" email)
  (println "  Mapping file:" (or mapping-file "(none - auto matching only)"))
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
    (when (and user (valid-file? file))
      (let [records                (core/read-airtable-file file)
            airtable-labels        (med/extract-unique-medications records)
            existing-medications   (find-existing-medications db (:xt/id user))
            existing-by-normalized (by-normalized-label existing-medications)
            output-dir             "tmp/migrations/medication"
            airtable-path          (str output-dir "/airtable-medications.edn")
            existing-path          (str output-dir "/existing-medications.edn")
            overrides-path         (or mapping-file (str output-dir "/medication-mapping-overrides.edn"))
            preview-path           (str output-dir "/medication-resolution-preview.edn")
            overrides              (load-overrides mapping-file)]
        (when (= ::invalid overrides)
          (throw (ex-info "Invalid mapping file" {:mapping-file mapping-file})))

        (write-edn! airtable-path (vec (sort-by normalize-label airtable-labels)))
        (write-edn! existing-path existing-medications)

        (let [resolution        (->> airtable-labels
                                     (sort-by normalize-label)
                                     (mapv #(resolve-label % overrides existing-by-normalized)))
              errors            (filter #(= :error (:status %)) resolution)
              auto-exact-count  (count (filter #(and (= :existing (:status %))
                                                     (= :auto (:source %)))
                                               resolution))
              override-count    (count (filter #(= :override (:source %)) resolution))
              unresolved-count  (count (filter #(= :unresolved (:status %)) resolution))
              create-new-count  (count (filter #(= :create-new (:status %)) resolution))]
          (when (and (str/blank? mapping-file)
                     (not (.exists (io/file overrides-path))))
            (write-edn! overrides-path (unresolved-overrides-skeleton resolution)))
          (write-edn! preview-path resolution)

          (u/print-green (str "  Airtable records loaded: " (count records)))
          (println "  Airtable unique medication labels:" (count airtable-labels))
          (println "  Existing medication rows for user:" (count existing-medications))
          (println "  Auto exact matches:" auto-exact-count)
          (println "  Override-driven resolutions:" override-count)
          (println "  Explicit create-new:" create-new-count)
          (println "  Unresolved labels:" unresolved-count)
          (println "  Wrote artifacts:")
          (println "   -" airtable-path)
          (println "   -" existing-path)
          (println "   -" overrides-path)
          (println "   -" preview-path)

          (when (or (seq errors) (pos? unresolved-count))
            (u/print-red "  ERROR: mapping must resolve all labels before dry-run generation")
            (doseq [{:keys [airtable-label reason value]} errors]
              (println "   -" airtable-label "|" reason "|" value))
            (when (pos? unresolved-count)
              (println "   - unresolved label count:" unresolved-count))
            (throw (ex-info "Invalid/incomplete mapping entries"
                            {:error-count      (count errors)
                             :unresolved-count unresolved-count
                             :preview-file     preview-path})))

          (let [resolution-by-label       (into {} (map (juxt :airtable-label identity)) resolution)
                medications-to-upsert-all (build-medications-to-upsert resolution (:xt/id user))
                medication-validation     (validate-docs medications-to-upsert-all
                                                         med/validate-medications
                                                         :medication/label)
                {:keys [logs rejected]}   (build-medication-log-docs records
                                                                     resolution-by-label
                                                                     (:xt/id user))
                log-validation            (validate-docs logs
                                                         med/validate-medication-logs
                                                         :airtable/id)
                medication-upsert-path    (str output-dir "/medications-to-upsert.edn")
                logs-write-path           (str output-dir "/medication-logs-to-write.edn")
                rejected-path             (str output-dir "/rejected-rows.edn")
                report-path               (str output-dir "/migration-report.edn")
                rejected-medications      (->> (:failed medication-validation)
                                               (map (fn [doc]
                                                      {:reason            :invalid-medication-doc
                                                       :medication/label (:medication/label doc)
                                                       :doc               doc}))
                                               vec)
                rejected-logs             (->> (:failed log-validation)
                                               (map (fn [doc]
                                                      {:reason      :invalid-medication-log-doc
                                                       :airtable/id (:airtable/id doc)
                                                       :doc         doc}))
                                               vec)
                all-rejected              (vec (concat rejected rejected-medications rejected-logs))
                report                    {:generated-at                (t/now)
                                           :mode                       :dry-run
                                           :file                       file
                                           :mapping-file               mapping-file
                                           :record-count               (count records)
                                           :airtable-unique-labels     (count airtable-labels)
                                           :existing-medications-count (count existing-medications)
                                           :resolution                 {:auto-exact      auto-exact-count
                                                                        :override-driven override-count
                                                                        :create-new      create-new-count
                                                                        :unresolved      unresolved-count
                                                                        :errors          (count errors)}
                                           :medications-upsert         {:total  (count medications-to-upsert-all)
                                                                        :valid  (count (:passed medication-validation))
                                                                        :failed (count (:failed medication-validation))}
                                           :medication-logs            {:converted        (count logs)
                                                                        :valid            (count (:passed log-validation))
                                                                        :failed           (count (:failed log-validation))
                                                                        :precheck-rejected (count rejected)}
                                           :rejected-total             (count all-rejected)}]
            (write-edn! medication-upsert-path (:passed medication-validation))
            (write-edn! logs-write-path (:passed log-validation))
            (write-edn! rejected-path all-rejected)
            (write-edn! report-path report)

            (println "  Dry-run artifacts:")
            (println "   -" medication-upsert-path)
            (println "   -" logs-write-path)
            (println "   -" rejected-path)
            (println "   -" report-path)
            (println "  Dry-run summary:")
            (println "    Medications to upsert (valid/total):"
                     (count (:passed medication-validation))
                     "/"
                     (count medications-to-upsert-all))
            (println "    Medication logs to write (valid/converted):"
                     (count (:passed log-validation))
                     "/"
                     (count logs))
            (println "    Total rejected rows/docs:" (count all-rejected))))))))
