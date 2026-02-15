(ns repl.airtable.core
  "Shared utilities for Airtable entity migrations.

   Workflow:
   1. Download Airtable table to EDN file using CLI:
      clj -M:dev download-airtable -k $API_KEY -b BASE_ID -n table-name
      -> airtable_data/{table_name}_{timestamp}.edn

   2. Use entity-specific namespace (e.g., repl.airtable.medication) to:
      - Transform records from EDN file
      - Validate against Malli schema
      - Write to production database via REPL"
  (:require
   [clj-uuid :as uuid]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [tick.core :as t]))

;; =============================================================================
;; Timestamp Parsing
;; =============================================================================

(defn parse-timestamp
  "Parse an ISO 8601 timestamp string to a tick instant.
   Returns nil if the timestamp is blank or invalid."
  [timestamp-str]
  (when-not (str/blank? timestamp-str)
    (try
      (t/instant timestamp-str)
      (catch Exception e
        (println "Warning: Failed to parse timestamp:" timestamp-str)
        nil))))

;; =============================================================================
;; Deterministic UUIDs
;; =============================================================================

(defn deterministic-uuid
  "Generate a deterministic UUID v5 from a namespace UUID and seed string.

   This allows idempotent imports - the same seed will always produce
   the same UUID, preventing duplicates on re-import."
  [namespace-uuid seed]
  (when-not (str/blank? seed)
    (uuid/v5 namespace-uuid seed)))

;; =============================================================================
;; Enum Parsing
;; =============================================================================

(defn parse-enum
  "Parse a string value to a keyword using a mapping.
   Returns default-val (or :n-a) if value is nil or not in mapping."
  ([value mapping]
   (parse-enum value mapping :n-a))
  ([value mapping default-val]
   (if value
     (let [result (get mapping (str/trim (str/lower-case value)) default-val)]
       (when (= result default-val)
         (println "  WARN: unmapped enum value:" (pr-str value)))
       result)
     default-val)))

;; =============================================================================
;; File Reading
;; =============================================================================

(defn read-airtable-file
  "Read an Airtable EDN export file and return a sequence of records.
   Each line in the file is one EDN record."
  [file-path]
  (with-open [rdr (io/reader file-path)]
    (->> (line-seq rdr)
         (map (fn [line]
                (when (seq (str/trim line))
                  (edn/read-string line))))
         (remove nil?)
         vec)))

(defn get-field-keys
  "Return a set of all field keys present across Airtable records in a file.
   Useful for discovering the schema of an Airtable table."
  [file-path]
  (with-open [rdr (io/reader file-path)]
    (->> (line-seq rdr)
         (mapcat (fn [line]
                   (when (seq (str/trim line))
                     (keys (get (edn/read-string line) "fields")))))
         (into #{}))))

(comment
  ;; Example: discover field keys in a downloaded file
  (get-field-keys "airtable_data/medication_log_xxx.edn")
  ;;
  )
