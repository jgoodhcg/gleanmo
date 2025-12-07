(ns tasks.medication-import
  "Import medication logs from Airtable export files into XTDB.

   Transforms Airtable's single-table structure into Gleanmo's two-entity design:
   - :medication catalog entries (with deterministic UUIDs from labels)
   - :medication-log entries (with FK to medication, deterministic UUIDs from airtable ID)"
  (:require
   [cheshire.core :as json]
   [clj-http.client :as http]
   [clj-uuid :as uuid]
   [clojure.string :as str]
   [clojure.tools.cli :refer [parse-opts]]))

;; =============================================================================
;; CLI Options
;; =============================================================================

(def cli-options
  [["-e" "--api-key-env ENV_VAR" "Environment variable containing Airtable API Key"
    :default "AIRTABLE_API_KEY"]
   ["-b" "--base-id BASE-ID" "Airtable Base ID"]
   ["-t" "--table-name TABLE-NAME" "Table name or id (default: medication-log)"
    :default "medication-log"]
   ["-u" "--user-id USER-ID" "User UUID to associate entries with"]
   ["-d" "--dry-run" "Only analyze, don't write to database"
    :default true]
   ["-n" "--sample-size N" "Max records to fetch when inspecting field keys"
    :default 5
    :parse-fn #(Integer/parseInt %)]
   ["-h" "--help" "Show help"]])

;; =============================================================================
;; Airtable API
;; =============================================================================

(defn get-api-key
  "Read API key from the specified environment variable."
  [env-var]
  (let [value (System/getenv env-var)]
    (when (str/blank? value)
      (throw (ex-info (str "Environment variable " env-var " is not set or empty")
                      {:env-var env-var})))
    value))

(defn airtable-request
  "Make a request to the Airtable API."
  [api-key method url & [opts]]
  (try
    (-> (http/request
         (merge {:method  method
                 :url     url
                 :headers {"Authorization" (str "Bearer " api-key)}
                 :as      :json
                 :throw-exceptions false}
                opts))
        (as-> response
              (if (>= (:status response) 400)
                (throw (ex-info (str "Airtable API error: " (:status response))
                                {:status (:status response)
                                 :body (:body response)}))
                (:body response))))
    (catch Exception e
      (println "Error:" (.getMessage e))
      (when-let [data (ex-data e)]
        (println "Details:" (:body data)))
      (throw e))))

(defn list-bases
  "List all bases accessible with the API key."
  [api-key]
  (airtable-request api-key :get "https://api.airtable.com/v0/meta/bases"))

(defn get-base-schema
  "Get the schema (tables) for a specific base."
  [api-key base-id]
  (airtable-request api-key :get
                    (str "https://api.airtable.com/v0/meta/bases/" base-id "/tables")))

(defn list-records
  "List records from a table (with optional limit)."
  [api-key base-id table-name & [{:keys [max-records] :or {max-records 3}}]]
  (airtable-request api-key :get
                    (str "https://api.airtable.com/v0/" base-id "/"
                         (java.net.URLEncoder/encode table-name "UTF-8"))
                    {:query-params {"maxRecords" max-records}}))

(defn test-connection
  "Test the Airtable connection by listing tables in the base."
  [{:keys [api-key-env base-id table-name]}]
  (println "Testing Airtable connection...")
  (println)
  (let [api-key (get-api-key api-key-env)]
    (println "API key loaded from:" api-key-env)
    (println)

    (if-not base-id
      (println "No base-id provided. Use -b <base-id> to list tables.")
      (do
        (println "Fetching tables for base:" base-id)
        (println)
        (println "Note: This requires 'schema.bases:read' scope on your token.")
        (println)
        (let [schema (get-base-schema api-key base-id)
              tables (:tables schema)]
          (println "Success! Found" (count tables) "table(s):")
          (doseq [{:keys [id name]} tables]
            (println (str "  - " name " (id: " id ")"))))))))

;; =============================================================================
;; Enum Mappings
;; =============================================================================

(def unit-mapping
  "Map Airtable unit strings to medication-log schema keywords.

   Airtable values: mg, g, Glob, Sprays, Mcg, Capsule
   Schema enum: :mg :mcg :g :ml :capsule :tablet :pill :drop :sprays :units :glob :patch :puff :other"
  {"mg"      :mg
   "g"       :g
   "Glob"    :glob
   "glob"    :glob
   "Sprays"  :sprays
   "sprays"  :sprays
   "Mcg"     :mcg
   "mcg"     :mcg
   "Capsule" :capsule
   "capsule" :capsule})

(def injection-site-mapping
  "Map Airtable injection site strings to medication-log schema keywords.

   Airtable values: left thigh, right thigh, left lower belly, right lower belly
   Schema enum: :left-thigh :right-thigh :left-lower-belly :right-lower-belly"
  {"left thigh"        :left-thigh
   "right thigh"       :right-thigh
   "left lower belly"  :left-lower-belly
   "right lower belly" :right-lower-belly})

(defn normalize-unit
  "Convert an Airtable unit string to a schema keyword.
   Returns :other if the unit is not recognized."
  [unit-str]
  (if (str/blank? unit-str)
    :other
    (get unit-mapping unit-str :other)))

(defn normalize-injection-site
  "Convert an Airtable injection site string to a schema keyword.
   Returns nil if blank or not recognized."
  [site-str]
  (when-not (str/blank? site-str)
    (get injection-site-mapping site-str)))

;; =============================================================================
;; Deterministic UUIDs
;; =============================================================================

(def medication-namespace-uuid
  "Namespace UUID for medication-related deterministic UUID generation.
   All medication and medication-log UUIDs will be derived from this namespace."
  #uuid "a1b2c3d4-e5f6-7890-abcd-ef1234567890")

(defn medication-uuid
  "Generate a deterministic UUID for a medication catalog entry from its label.

   The label is normalized (lowercased and trimmed) to ensure consistency.
   This allows idempotent imports - the same medication name will always
   produce the same UUID."
  [label]
  (when-not (str/blank? label)
    (let [normalized (-> label str/trim str/lower-case)]
      (uuid/v5 medication-namespace-uuid normalized))))

(defn medication-log-uuid
  "Generate a deterministic UUID for a medication-log entry from its Airtable record ID.

   This ensures that re-importing the same Airtable record will produce
   the same UUID, preventing duplicates."
  [airtable-id]
  (when-not (str/blank? airtable-id)
    (uuid/v5 medication-namespace-uuid (str "log:" airtable-id))))

;; =============================================================================
;; Field discovery
;; =============================================================================

(defn field-keys
  "Return a set of all field keys present across Airtable records."
  [records]
  (->> records
       (map #(or (:fields %) {}))
       (map keys)
       (reduce into #{})))

(defn fetch-and-report-field-keys
  "Fetch up to `sample-size` records and print the discovered field keys."
  [{:keys [api-key-env base-id table-name sample-size]}]
  (if-not base-id
    (do
      (println "Skipping field discovery: base-id is required.")
      (println))
    (let [api-key (get-api-key api-key-env)
          response (list-records api-key base-id table-name {:max-records sample-size})
          records (:records response)
          observed-keys (field-keys records)]
      (println "Fetched" (count records) "record(s) from" table-name "(max" sample-size ")")
      (if (seq observed-keys)
        (do
          (println "Observed field keys:" (count observed-keys))
          (doseq [k (sort observed-keys)]
            (println "  -" k)))
        (println "No field keys found. The table may be empty or missing fields."))
      (println)
      {:records records
       :field-keys observed-keys})))

(defn demo-transformations
  "Demonstrate enum mappings and UUID generation on sample records."
  [{:keys [api-key-env base-id table-name sample-size]}]
  (if-not base-id
    (println "Skipping transformation demo: base-id is required.")
    (let [api-key (get-api-key api-key-env)
          response (list-records api-key base-id table-name {:max-records sample-size})
          records (:records response)]
      (println "=== Transformation Demo ===")
      (println)
      (println "DEBUG: First record structure:")
      (println (first records))
      (println)
      (if (seq records)
        (doseq [{:keys [id fields]} (take 3 records)]
          (let [;; cheshire with :as :json converts JSON keys to keywords
                medication (:medication fields)
                unit (:unit fields)
                dosage (:dosage fields)
                timestamp (:timestamp fields)
                injection-site (:injection-site fields)]
            (println "Record ID:" id)
            (println "  Raw data:")
            (println "    medication:" medication)
            (println "    dosage:" dosage)
            (println "    unit:" unit)
            (when injection-site
              (println "    injection site:" injection-site))
            (println "  Transformations:")
            (println "    medication UUID:" (medication-uuid medication))
            (println "    log UUID:" (medication-log-uuid id))
            (println "    normalized unit:" (normalize-unit unit))
            (when injection-site
              (println "    normalized site:" (normalize-injection-site injection-site)))
            (println)))
        (println "No records to demonstrate transformations."))
      (println))))

;; =============================================================================
;; Entry Point
;; =============================================================================

(defn import-medications
  "Main entry point for medication import task.

   Usage: clj -M:dev import-medications -e AIRTABLE_API_KEY -b <base-id>"
  [& args]
  (let [{:keys [options errors summary]} (parse-opts args cli-options)]
    (println "=== Medication Import ===")
    (println)
    (cond
      (:help options)
      (do
        (println "Import medication logs from Airtable into XTDB")
        (println)
        (println "Options:")
        (println summary))

      (seq errors)
      (do
        (println "Errors:")
        (doseq [e errors]
          (println " " e))
        (println)
        (println "Use --help for usage information"))

      :else
      (do
        (println "Options:")
        (println "  API Key Env:" (:api-key-env options))
        (println "  Base ID:" (or (:base-id options) "[not provided]"))
        (println "  Table:" (:table-name options))
        (println "  User ID:" (or (:user-id options) "[not provided]"))
        (println "  Dry run:" (:dry-run options))
        (println "  Sample size:" (:sample-size options))
        (println)

        ;; Test connection
        (test-connection options)

        ;; Fetch a page and report observed field keys
        (fetch-and-report-field-keys options)

        ;; Demonstrate transformations on sample data
        (demo-transformations options)))))

;; =============================================================================
;; Sections to implement
;; =============================================================================

;; 1. Fuzzy String Matching (Levenshtein Distance) - TODO
;; 2. Enum Mappings - DONE
;; 3. Deterministic UUIDs - DONE
;; 4. Database Operations - TODO
;; 5. Airtable Record Parsing - TODO
;; 6. Medication Catalog Building - TODO
;; 7. Medication Log Transformation - TODO
;; 8. Validation - TODO
;; 9. Import Pipeline - TODO

(comment
  ;; Test from REPL
  (import-medications "--help")

  ;; Test connection (assumes AIRTABLE_API_KEY env var is set)
  (import-medications "-b" "your-base-id-here")

  ;; Test enum mappings
  (normalize-unit "mg")           ; => :mg
  (normalize-unit "Mcg")          ; => :mcg
  (normalize-unit "capsule")      ; => :capsule
  (normalize-unit "unknown")      ; => :other
  (normalize-unit nil)            ; => :other

  (normalize-injection-site "left thigh")       ; => :left-thigh
  (normalize-injection-site "right lower belly") ; => :right-lower-belly
  (normalize-injection-site "unknown")          ; => nil

  ;; Test deterministic UUIDs
  (medication-uuid "Ibuprofen")               ; => consistent UUID
  (medication-uuid "ibuprofen")               ; => same UUID (normalized)
  (medication-uuid "  Ibuprofen  ")           ; => same UUID (trimmed)
  (medication-log-uuid "recXXXXXXXXXXXXX")    ; => consistent UUID
  (medication-log-uuid "recXXXXXXXXXXXXX")    ; => same UUID (idempotent)
  ;;
  )
