(ns tasks.medication-import
  "Import medication logs from Airtable export files into XTDB.

   Transforms Airtable's single-table structure into Gleanmo's two-entity design:
   - :medication catalog entries (with deterministic UUIDs from labels)
   - :medication-log entries (with FK to medication, deterministic UUIDs from airtable ID)"
  (:require
   [cheshire.core :as json]
   [clj-http.client :as http]
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
        (fetch-and-report-field-keys options)))))

;; =============================================================================
;; Sections to implement
;; =============================================================================

;; 1. Fuzzy String Matching (Levenshtein Distance)
;; 2. Enum Mappings
;; 3. Deterministic UUIDs
;; 4. Database Operations
;; 5. Airtable Record Parsing
;; 6. Medication Catalog Building
;; 7. Medication Log Transformation
;; 8. Validation
;; 9. Import Pipeline

(comment
  ;; Test from REPL
  (import-medications "--help")

  ;; Test connection (assumes AIRTABLE_API_KEY env var is set)
  (import-medications "-b" "your-base-id-here")
  ;;
  )
