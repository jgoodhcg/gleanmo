(ns repl.airtable.medication
  "Airtable migration for medication catalog and medication-log entities.

   Workflow:
   1. Download medication-log table:
      clj -M:dev download-airtable -k $API_KEY -b BASE_ID -n medication-log

   2. In REPL:
      (def prod-node (repl/prod-node-start))
      (def ctx (repl/get-prod-db-context prod-node))

      ;; Preview transformations
      (convert-airtable-medications \"airtable_data/medication_log_xxx.edn\" user-id)

      ;; Validate and write
      (write-medications-to-db ctx \"email@example.com\")"
  (:require
   [clojure.string :as str]
   [com.biffweb :as biff :refer [q]]
   [malli.core :as m]
   [repl.airtable.core :as core]
   [tech.jgood.gleanmo :as main]
   [tech.jgood.gleanmo.schema.medication-schema :as meds]
   [tech.jgood.gleanmo.schema.meta :as sm]
   [tick.core :as t]))

;; =============================================================================
;; Namespace UUIDs for Deterministic ID Generation
;; =============================================================================

(def medication-namespace-uuid
  "Namespace UUID for medication catalog entries.
   Derived from normalized medication labels."
  #uuid "a1b2c3d4-e5f6-7890-abcd-ef1234567890")

(def medication-log-namespace-uuid
  "Namespace UUID for medication-log entries.
   Derived from Airtable record IDs."
  #uuid "b2c3d4e5-f6a7-8901-bcde-f12345678901")

;; =============================================================================
;; Enum Mappings
;; =============================================================================

(def unit-mapping
  "Map Airtable unit strings to medication-log schema keywords."
  {"mg"      :mg
   "mcg"     :mcg
   "g"       :g
   "capsule" :capsule
   "glob"    :glob
   "sprays"  :sprays})

(def injection-site-mapping
  "Map Airtable injection site strings to medication-log schema keywords."
  {"left thigh"        :left-thigh
   "right thigh"       :right-thigh
   "left lower belly"  :left-lower-belly
   "right lower belly" :right-lower-belly})

;; =============================================================================
;; UUID Generation
;; =============================================================================

(defn medication-uuid
  "Generate a deterministic UUID for a medication catalog entry from its label.
   The label is normalized (lowercased and trimmed) for consistency."
  [label]
  (when-not (str/blank? label)
    (let [normalized (-> label str/trim str/lower-case)]
      (core/deterministic-uuid medication-namespace-uuid normalized))))

(defn medication-log-uuid
  "Generate a deterministic UUID for a medication-log entry from its Airtable ID."
  [airtable-id]
  (when-not (str/blank? airtable-id)
    (core/deterministic-uuid medication-log-namespace-uuid airtable-id)))

;; =============================================================================
;; Record Transformation
;; =============================================================================

(defn airtable->medication
  "Transform medication label into a medication catalog entity.
   Returns nil if label is blank."
  [label user-id now]
  (when-not (str/blank? label)
    {:xt/id           (medication-uuid label)
     ::sm/type        :medication
     ::sm/created-at  now
     :db/doc-type     :medication
     :user/id         user-id
     :medication/label (str/trim label)}))

(defn airtable->medication-log
  "Transform an Airtable record into a medication-log entity."
  [airtable-record user-id medication-id now]
  (let [fields        (get airtable-record "fields")
        airtable-id   (get airtable-record "id")
        created-time  (get airtable-record "createdTime")
        timestamp-str (get fields "timestamp")
        dosage        (get fields "dosage")
        unit-str      (get fields "unit")
        notes         (get fields "notes")
        injection-str (get fields "injection-site")
        injection-key (some-> injection-str str/lower-case
                              (get injection-site-mapping))]
    (-> {:xt/id                       (medication-log-uuid airtable-id)
         ::sm/type                    :medication-log
         ::sm/created-at              (or (core/parse-timestamp timestamp-str)
                                          (core/parse-timestamp created-time))
         :db/doc-type                 :medication-log
         :user/id                     user-id
         :medication-log/medication-id medication-id
         :medication-log/timestamp    (or (core/parse-timestamp timestamp-str)
                                          (core/parse-timestamp created-time))
         :medication-log/dosage       (float (or dosage 0))
         :medication-log/unit         (core/parse-enum unit-str unit-mapping :other)
         ;; Airtable metadata for traceability
         :airtable/id                 airtable-id
         :airtable/created-time       (core/parse-timestamp created-time)
         :airtable/ported-at          now}
        (cond->
         (not (str/blank? notes))
          (assoc :medication-log/notes notes)

          injection-key
          (assoc :medication-log/injection-site injection-key)))))

;; =============================================================================
;; Batch Conversion
;; =============================================================================

(defn extract-unique-medications
  "Extract unique medication labels from Airtable records."
  [records]
  (->> records
       (map #(get-in % ["fields" "medication"]))
       (remove str/blank?)
       (map str/trim)
       distinct
       vec))

(defn convert-airtable-medications
  "Convert Airtable records to medication catalog entities.
   Returns a vector of unique medication entities."
  [file-path user-id]
  (let [records (core/read-airtable-file file-path)
        labels  (extract-unique-medications records)
        now     (t/now)]
    (->> labels
         (map #(airtable->medication % user-id now))
         (remove nil?)
         vec)))

(defn convert-airtable-medication-logs
  "Convert Airtable records to medication-log entities.
   Requires medications to already exist in DB to resolve medication-id."
  [file-path user-id db]
  (let [records (core/read-airtable-file file-path)
        now     (t/now)]
    (->> records
         (map (fn [record]
                (let [label         (get-in record ["fields" "medication"])
                      medication-id (medication-uuid label)]
                  (airtable->medication-log record user-id medication-id now))))
         vec)))

;; =============================================================================
;; Validation
;; =============================================================================

(defn validate-medications
  "Validate medication entities against Malli schema.
   Returns {:passed N, :failed [...], :total N}."
  [medications]
  (let [registry  (:registry main/malli-opts)
        validator (m/validator meds/medication {:registry registry})
        results   (map (fn [med]
                         {:entity med
                          :valid? (validator med)})
                       medications)
        failed    (filter #(not (:valid? %)) results)]
    {:passed (count (filter :valid? results))
     :failed (map :entity failed)
     :total  (count results)}))

(defn validate-medication-logs
  "Validate medication-log entities against Malli schema.
   Returns {:passed N, :failed [...], :total N}."
  [logs]
  (let [registry  (:registry main/malli-opts)
        validator (m/validator meds/medication-log {:registry registry})
        results   (map (fn [log]
                         {:entity log
                          :valid? (validator log)})
                       logs)
        failed    (filter #(not (:valid? %)) results)]
    {:passed (count (filter :valid? results))
     :failed (map :entity failed)
     :total  (count results)}))

;; =============================================================================
;; Database Operations
;; =============================================================================

(defn write-medications-to-db
  "Write medication catalog entities to the database.
   Creates medications first, then logs (since logs reference medications)."
  [{:keys [biff/db] :as ctx} file-path email]
  (let [{user-id :xt/id}
        (first (q db
                  '{:find  (pull ?e [*])
                    :where [[?e :user/email email]]
                    :in    [email]}
                  email))
        medications (convert-airtable-medications file-path user-id)
        validation  (validate-medications medications)]
    (println "Medications to import:" (:total validation))
    (println "  Passed validation:" (:passed validation))
    (println "  Failed validation:" (count (:failed validation)))
    (when (seq (:failed validation))
      (println "  First failed:" (first (:failed validation))))
    (when (= (:passed validation) (:total validation))
      (println "Submitting medications...")
      (biff/submit-tx ctx medications)
      (println "Done."))))

(defn write-medication-logs-to-db
  "Write medication-log entities to the database.
   Medications must already exist."
  [{:keys [biff/db] :as ctx} file-path email]
  (let [{user-id :xt/id}
        (first (q db
                  '{:find  (pull ?e [*])
                    :where [[?e :user/email email]]
                    :in    [email]}
                  email))
        logs       (convert-airtable-medication-logs file-path user-id db)
        validation (validate-medication-logs logs)]
    (println "Medication logs to import:" (:total validation))
    (println "  Passed validation:" (:passed validation))
    (println "  Failed validation:" (count (:failed validation)))
    (when (seq (:failed validation))
      (println "  First failed:" (first (:failed validation))))
    (when (= (:passed validation) (:total validation))
      (println "Submitting medication logs...")
      (biff/submit-tx ctx logs)
      (println "Done."))))

;; =============================================================================
;; REPL Usage
;; =============================================================================

(comment
  ;; 1. Download data first:
  ;; clj -M:dev download-airtable -k $API_KEY -b BASE_ID -n medication-log

  ;; 2. Discover field keys
  (core/get-field-keys "airtable_data/medication_log_xxx.edn")

  ;; 3. Preview conversions
  (convert-airtable-medications
   "airtable_data/medication_log_xxx.edn"
   #uuid "00000000-0000-0000-0000-000000000000")

  ;; 4. Connect to prod and write
  (require '[repl :refer [prod-node-start get-prod-db-context]])
  (def prod-node (prod-node-start))
  (def ctx (get-prod-db-context prod-node))

  ;; Write medications first (catalog)
  (write-medications-to-db ctx "airtable_data/medication_log_xxx.edn" "email@example.com")

  ;; Then write medication logs
  (write-medication-logs-to-db ctx "airtable_data/medication_log_xxx.edn" "email@example.com")
  ;;
  )
