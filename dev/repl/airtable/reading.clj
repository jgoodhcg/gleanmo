(ns repl.airtable.reading
  "Airtable migration for book-source, book, and reading-log entities.

   Workflow:
   1. Download tables:
      clj -M:dev download-airtable -k $API_KEY -b BASE_ID -n books
      clj -M:dev download-airtable -k $API_KEY -b BASE_ID -n reading-log

   2. In REPL:
      (def prod-node (repl/prod-node-start))
      (def ctx (repl/get-prod-db-context prod-node))

      ;; Preview transformations
      (convert-airtable-books \"airtable_data/books_xxx.edn\" user-id)

      ;; Validate and write (book-sources first, then books, then logs)
      (write-book-sources-to-db ctx \"airtable_data/books_xxx.edn\" \"email@example.com\")
      (write-books-to-db ctx \"airtable_data/books_xxx.edn\" \"email@example.com\")
      (write-reading-logs-to-db ctx ...)"
  (:require
   [clojure.string :as str]
   [com.biffweb :as biff :refer [q]]
   [malli.core :as m]
   [repl.airtable.core :as core]
   [tech.jgood.gleanmo :as main]
   [tech.jgood.gleanmo.schema.location-schema :as ls]
   [tech.jgood.gleanmo.schema.reading-schema :as rs]
   [tech.jgood.gleanmo.schema.meta :as sm]
   [tick.core :as t]))

;; =============================================================================
;; Namespace UUIDs for Deterministic ID Generation
;; =============================================================================

(def location-namespace-uuid
  "Namespace UUID for location entities created during reading migration.
   Used as fallback when no existing location matches by label."
  #uuid "a1b2c3d4-e5f6-7890-abcd-0123456789ab")

(def book-source-namespace-uuid
  "Namespace UUID for book-source catalog entries.
   Derived from normalized source labels."
  #uuid "b2c3d4e5-f6a7-8901-bcde-1234567890ab")

(def book-namespace-uuid
  "Namespace UUID for book catalog entries.
   Derived from normalized book titles."
  #uuid "c3d4e5f6-a7b8-9012-cdef-234567890abc")

(def reading-log-namespace-uuid
  "Namespace UUID for reading-log entries.
   Derived from Airtable record IDs."
  #uuid "d4e5f6a7-b8c9-0123-defa-34567890abcd")

;; =============================================================================
;; Enum Mappings
;; =============================================================================

(def airtable-location->prod-label
  "Map Airtable location strings (lowercased) to existing production location labels.
   These MUST exist in the production database. In dev mode, they are created if missing.
   Airtable values NOT in this map are treated as new locations to create."
  {"bed"                    "Bed"
   "kaiti\u2019s bed"       "Bed"
   "porch"                  "Porch"
   "stressless wing chair"  "Stressless Wingback Chair"
   "hammock"                "Hammock"
   "couch"                  "Living room"
   "desk (gaming)"          "Office desk"
   "chair"                  "Living room"})

(def unmapped-location-labels
  "Airtable location strings (lowercased) that have no production equivalent.
   These become new location entities in both dev and prod."
  {"dog park"  "Dog Park"
   "deck"      "Deck"
   "beach"     "Beach"
   "car"       "Car"
   "gym"       "Gym"
   "other"     "Other"})

(def format-mapping
  "Map Airtable format strings to reading-log schema keywords."
  {"audiobook"  :audiobook
   "paperback"  :paperback
   "hardcover"  :hardcover})

(def from-label-mapping
  "Map Airtable 'from' strings (lowercased) to properly capitalized book-source labels."
  {"library of america"                         "Library of America"
   "amazon"                                     "Amazon"
   "audible"                                    "Audible"
   "barnes and nobles woodland mall"            "Barnes and Nobles Woodland Mall"
   "the gallery bookstore chicago"              "The Gallery Bookstore Chicago"
   "curious book shop east lansing"             "Curious Book Shop East Lansing"
   "argos comics and used books grand rapids"   "Argos Comics and Used Books Grand Rapids"
   "kurzgesagt shop"                            "Kurzgesagt Shop"
   "grpl friends of the library sale"           "GRPL Friends of the Library Sale"
   "black dog books and records grand rapids"   "Black Dog Books and Records Grand Rapids"
   "schuler books"                              "Schuler Books"
   "other"                                      "Other"})

;; =============================================================================
;; UUID Generation
;; =============================================================================

(defn location-uuid
  "Generate a deterministic UUID for a location from its label.
   Used as fallback when no existing location matches by label."
  [label]
  (when-not (str/blank? label)
    (let [normalized (-> label str/trim str/lower-case)]
      (core/deterministic-uuid location-namespace-uuid normalized))))

(defn book-source-uuid
  "Generate a deterministic UUID for a book-source from its label.
   The label is normalized (lowercased and trimmed) for consistency."
  [label]
  (when-not (str/blank? label)
    (let [normalized (-> label str/trim str/lower-case)]
      (core/deterministic-uuid book-source-namespace-uuid normalized))))

(defn book-uuid
  "Generate a deterministic UUID for a book from its title.
   The title is normalized (lowercased and trimmed) for consistency."
  [title]
  (when-not (str/blank? title)
    (let [normalized (-> title str/trim str/lower-case)]
      (core/deterministic-uuid book-namespace-uuid normalized))))

(defn reading-log-uuid
  "Generate a deterministic UUID for a reading-log entry from its Airtable ID."
  [airtable-id]
  (when-not (str/blank? airtable-id)
    (core/deterministic-uuid reading-log-namespace-uuid airtable-id)))

;; =============================================================================
;; Record Transformation
;; =============================================================================

(defn location-str->label
  "Resolve an Airtable location string to a target location label.
   Checks the production mapping first, then the unmapped (new) mapping,
   then falls back to the trimmed original string."
  [location-raw]
  (when-not (str/blank? location-raw)
    (let [normalized (-> location-raw str/trim str/lower-case)]
      (or (get airtable-location->prod-label normalized)
          (get unmapped-location-labels normalized)
          (str/trim location-raw)))))

(defn from-str->book-source-label
  "Resolve an Airtable 'from' string to a properly capitalized book-source label.
   Falls back to the trimmed original string if not in the mapping."
  [from-raw]
  (when-not (str/blank? from-raw)
    (let [normalized (-> from-raw str/trim str/lower-case)]
      (get from-label-mapping normalized (str/trim from-raw)))))

(defn airtable->book
  "Transform an Airtable book record into a book entity.
   Maps the 'from' field to :book/book-source-ids (set of book-source UUIDs)."
  [airtable-record user-id now]
  (let [fields       (get airtable-record "fields")
        airtable-id  (get airtable-record "id")
        created-time (get airtable-record "createdTime")
        title        (get fields "title")
        author       (get fields "author")
        formats-raw  (get fields "formats")
        published    (get fields "published")
        from-raw     (get fields "from")
        notes        (get fields "notes")]
    (when-not (str/blank? title)
      (let [trimmed-title (str/trim title)]
        (-> {:xt/id                  (book-uuid title)
             ::sm/type               :book
             ::sm/created-at         (or (core/parse-timestamp created-time) now)
             :db/doc-type            :book
             :user/id                user-id
             :book/title             trimmed-title
             :book/label             trimmed-title
             :airtable/id            airtable-id
             :airtable/created-time  (core/parse-timestamp created-time)
             :airtable/ported-at     now}
            (cond->
             (not (str/blank? author))
              (assoc :book/author (str/trim author))

              (seq formats-raw)
              (assoc :book/formats
                     (->> formats-raw
                          (map #(get format-mapping (str/lower-case (str/trim %))))
                          (remove nil?)
                          set))

              (not (str/blank? published))
              (assoc :book/published (t/date published))

              (not (str/blank? from-raw))
              (assoc :book/book-source-ids
                     (let [label (from-str->book-source-label from-raw)]
                       #{(book-source-uuid label)}))

              (not (str/blank? notes))
              (assoc :book/notes notes)))))))

(defn airtable->reading-log
  "Transform an Airtable reading-log record into a reading-log entity.
   Requires a lookup map from Airtable book IDs to book UUIDs and a
   location label→UUID lookup for resolving location references."
  [airtable-record user-id airtable-book-id->uuid location-label->uuid now]
  (let [fields        (get airtable-record "fields")
        airtable-id   (get airtable-record "id")
        created-time  (get airtable-record "createdTime")
        beg-str       (get fields "beg")
        end-str       (get fields "end")
        time-zone     (or (get fields "time-zone") "US/Eastern")
        location-str  (get fields "location")
        format-str    (get fields "format")
        finished?     (get fields "finished")
        notes         (get fields "notes")
        book-ids-raw  (get fields "book")
        book-at-id    (first book-ids-raw)
        book-uuid     (when book-at-id (get airtable-book-id->uuid book-at-id))
        loc-label     (when-not (str/blank? location-str)
                        (location-str->label location-str))
        loc-uuid      (when loc-label
                        (let [uuid (get location-label->uuid loc-label)]
                          (when-not uuid
                            (println "  WARN: no location UUID for:" (pr-str loc-label)))
                          uuid))]
    (when book-uuid
      (-> {:xt/id                  (reading-log-uuid airtable-id)
           ::sm/type               :reading-log
           ::sm/created-at         (or (core/parse-timestamp beg-str)
                                       (core/parse-timestamp created-time)
                                       now)
           :db/doc-type            :reading-log
           :user/id                user-id
           :reading-log/book-id    book-uuid
           :reading-log/beginning  (or (core/parse-timestamp beg-str)
                                       (core/parse-timestamp created-time)
                                       now)
           :reading-log/time-zone  time-zone
           :airtable/id            airtable-id
           :airtable/created-time  (core/parse-timestamp created-time)
           :airtable/ported-at     now}
          (cond->
           (not (str/blank? end-str))
            (assoc :reading-log/end (core/parse-timestamp end-str))

            loc-uuid
            (assoc :reading-log/location-id loc-uuid)

            (not (str/blank? location-str))
            (assoc :airtable/original-location (str/trim location-str))

            (not (str/blank? format-str))
            (assoc :reading-log/format
                   (core/parse-enum format-str format-mapping nil))

            (some? finished?)
            (assoc :reading-log/finished? (boolean finished?))

            (not (str/blank? notes))
            (assoc :reading-log/notes notes))))))

;; =============================================================================
;; Book-Source Extraction
;; =============================================================================

(defn airtable->book-source
  "Create a book-source entity from a source label string."
  [label user-id now]
  (when-not (str/blank? label)
    {:xt/id              (book-source-uuid label)
     ::sm/type           :book-source
     ::sm/created-at     now
     :db/doc-type        :book-source
     :user/id            user-id
     :book-source/label  label}))

(defn extract-unique-book-sources
  "Extract unique book-source labels from the 'from' field across all book records."
  [books-file-path]
  (let [records (core/read-airtable-file books-file-path)]
    (->> records
         (map #(get-in % ["fields" "from"]))
         (remove str/blank?)
         (map from-str->book-source-label)
         (remove nil?)
         distinct
         vec)))

(defn convert-airtable-book-sources
  "Convert unique 'from' values from the books file into book-source entities."
  [books-file-path user-id]
  (let [labels (extract-unique-book-sources books-file-path)
        now    (t/now)]
    (->> labels
         (map #(airtable->book-source % user-id now))
         (remove nil?)
         vec)))

;; =============================================================================
;; Location Extraction & Reconciliation
;; =============================================================================

(def prod-required-labels
  "The set of location labels that must already exist in production.
   Derived from the values of airtable-location->prod-label."
  (set (vals airtable-location->prod-label)))

(defn extract-unique-location-labels
  "Extract unique location labels from the 'location' field across all reading-log records.
   Returns the target labels (after mapping through airtable-location->prod-label
   and unmapped-location-labels)."
  [logs-file-path]
  (let [records (core/read-airtable-file logs-file-path)]
    (->> records
         (map #(get-in % ["fields" "location"]))
         (remove str/blank?)
         (map location-str->label)
         (remove nil?)
         distinct
         vec)))

(defn- fetch-existing-locations
  "Fetch all non-deleted locations for a user from the database.
   Returns a map of normalized-label → UUID."
  [db user-id]
  (->> (q db
          '{:find  (pull ?e [:xt/id :location/label])
            :where [[?e :location/label]
                    [?e :user/id uid]
                    (not [?e :tech.jgood.gleanmo.schema.meta/deleted-at])]
            :in    [uid]}
          user-id)
       (map (fn [{:keys [xt/id location/label]}]
              [(str/lower-case (str/trim label)) id]))
       (into {})))

(defn build-location-label->uuid
  "Build a lookup from target location label to UUID.

   For labels found in the DB: uses the existing UUID.
   For labels NOT found in the DB: generates a deterministic UUID.

   When strict? is true (production), throws if any label from
   `prod-required-labels` is missing from the DB."
  [db user-id labels strict?]
  (let [existing (fetch-existing-locations db user-id)
        missing-prod (->> labels
                          (filter #(contains? prod-required-labels %))
                          (remove #(contains? existing (str/lower-case (str/trim %))))
                          vec)]
    (when (and strict? (seq missing-prod))
      (throw (ex-info
              (str "Production locations not found in DB: " (pr-str missing-prod)
                   "\nThese must exist before running the migration in production.")
              {:missing-labels missing-prod})))
    (->> labels
         (map (fn [label]
                (let [normalized (str/lower-case (str/trim label))
                      uuid       (or (get existing normalized)
                                     (location-uuid label))]
                  [label uuid])))
         (into {}))))

(defn locations-to-create
  "Return location entities that need to be created (not already in DB).
   In dev mode this creates any missing location; in prod mode the caller
   should have already validated via build-location-label->uuid with strict?=true."
  [db user-id labels now]
  (let [existing    (fetch-existing-locations db user-id)
        existing-id-set (set (vals existing))]
    (->> labels
         (map (fn [label]
                (let [normalized (str/lower-case (str/trim label))
                      uuid       (or (get existing normalized)
                                     (location-uuid label))]
                  {:uuid uuid :label label :exists? (contains? existing-id-set uuid)})))
         (remove :exists?)
         (map (fn [{:keys [uuid label]}]
                {:xt/id              uuid
                 ::sm/type           :location
                 ::sm/created-at     now
                 :db/doc-type        :location
                 :user/id            user-id
                 :location/label     label
                 :airtable/ported-at now}))
         vec)))

;; =============================================================================
;; Batch Conversion
;; =============================================================================

(defn convert-airtable-books
  "Convert Airtable book records to book entities.
   Returns a vector of book entities."
  [file-path user-id]
  (let [records (core/read-airtable-file file-path)
        now     (t/now)]
    (->> records
         (map #(airtable->book % user-id now))
         (remove nil?)
         vec)))

(defn build-airtable-book-id-lookup
  "Build a map from Airtable book record ID to deterministic book UUID.
   Used to resolve book references in reading-log records."
  [books-file-path]
  (let [records (core/read-airtable-file books-file-path)]
    (->> records
         (map (fn [record]
                (let [airtable-id (get record "id")
                      title       (get-in record ["fields" "title"])]
                  [airtable-id (book-uuid title)])))
         (into {}))))

(defn convert-airtable-reading-logs
  "Convert Airtable reading-log records to reading-log entities.
   Requires a books file to build the airtable-id → book-uuid lookup
   and a location label→UUID lookup for resolving location references."
  [logs-file-path books-file-path user-id location-label->uuid]
  (let [records              (core/read-airtable-file logs-file-path)
        airtable-book-lookup (build-airtable-book-id-lookup books-file-path)
        now                  (t/now)]
    (->> records
         (map #(airtable->reading-log % user-id airtable-book-lookup location-label->uuid now))
         (remove nil?)
         vec)))

;; =============================================================================
;; Validation
;; =============================================================================

(defn validate-locations
  "Validate location entities against Malli schema.
   Returns {:passed N, :failed [...], :total N}."
  [locations]
  (let [registry  (:registry main/malli-opts)
        validator (m/validator ls/location {:registry registry})
        results   (map (fn [loc]
                         {:entity loc
                          :valid? (validator loc)})
                       locations)
        failed    (filter #(not (:valid? %)) results)]
    {:passed (count (filter :valid? results))
     :failed (map :entity failed)
     :total  (count results)}))

(defn validate-book-sources
  "Validate book-source entities against Malli schema.
   Returns {:passed N, :failed [...], :total N}."
  [sources]
  (let [registry  (:registry main/malli-opts)
        validator (m/validator rs/book-source {:registry registry})
        results   (map (fn [src]
                         {:entity src
                          :valid? (validator src)})
                       sources)
        failed    (filter #(not (:valid? %)) results)]
    {:passed (count (filter :valid? results))
     :failed (map :entity failed)
     :total  (count results)}))

(defn validate-books
  "Validate book entities against Malli schema.
   Returns {:passed N, :failed [...], :total N}."
  [books]
  (let [registry  (:registry main/malli-opts)
        validator (m/validator rs/book {:registry registry})
        results   (map (fn [book]
                         {:entity book
                          :valid? (validator book)})
                       books)
        failed    (filter #(not (:valid? %)) results)]
    {:passed (count (filter :valid? results))
     :failed (map :entity failed)
     :total  (count results)}))

(defn validate-reading-logs
  "Validate reading-log entities against Malli schema.
   Returns {:passed N, :failed [...], :total N}."
  [logs]
  (let [registry  (:registry main/malli-opts)
        validator (m/validator rs/reading-log {:registry registry})
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

(defn write-book-sources-to-db
  "Write book-source entities to the database.
   Must be called before write-books-to-db."
  [{:keys [biff/db] :as ctx} books-file-path email]
  (let [{user-id :xt/id}
        (first (q db
                  '{:find  (pull ?e [*])
                    :where [[?e :user/email email]]
                    :in    [email]}
                  email))
        sources    (convert-airtable-book-sources books-file-path user-id)
        validation (validate-book-sources sources)]
    (println "Book sources to import:" (:total validation))
    (println "  Passed validation:" (:passed validation))
    (println "  Failed validation:" (count (:failed validation)))
    (when (seq (:failed validation))
      (println "  First failed:" (first (:failed validation))))
    (when (= (:passed validation) (:total validation))
      (println "Submitting book sources...")
      (biff/submit-tx ctx sources)
      (println "Done."))))

(defn write-books-to-db
  "Write book entities to the database."
  [{:keys [biff/db] :as ctx} file-path email]
  (let [{user-id :xt/id}
        (first (q db
                  '{:find  (pull ?e [*])
                    :where [[?e :user/email email]]
                    :in    [email]}
                  email))
        books      (convert-airtable-books file-path user-id)
        validation (validate-books books)]
    (println "Books to import:" (:total validation))
    (println "  Passed validation:" (:passed validation))
    (println "  Failed validation:" (count (:failed validation)))
    (when (seq (:failed validation))
      (println "  First failed:" (first (:failed validation))))
    (when (= (:passed validation) (:total validation))
      (println "Submitting books...")
      (biff/submit-tx ctx books)
      (println "Done."))))

(defn write-reading-logs-to-db
  "Write reading-log entities to the database.
   Books and locations must already exist."
  [{:keys [biff/db] :as ctx} logs-file-path books-file-path email]
  (let [{user-id :xt/id}
        (first (q db
                  '{:find  (pull ?e [*])
                    :where [[?e :user/email email]]
                    :in    [email]}
                  email))
        loc-labels (extract-unique-location-labels logs-file-path)
        loc-lookup (build-location-label->uuid db user-id loc-labels false)
        logs       (convert-airtable-reading-logs logs-file-path books-file-path user-id loc-lookup)
        validation (validate-reading-logs logs)]
    (println "Reading logs to import:" (:total validation))
    (println "  Passed validation:" (:passed validation))
    (println "  Failed validation:" (count (:failed validation)))
    (when (seq (:failed validation))
      (println "  First failed:" (first (:failed validation))))
    (when (= (:passed validation) (:total validation))
      (println "Submitting reading logs...")
      (biff/submit-tx ctx logs)
      (println "Done."))))

;; =============================================================================
;; REPL Usage
;; =============================================================================

(comment
  ;; 1. Download data first:
  ;; clj -M:dev download-airtable -k $API_KEY -b BASE_ID -n books
  ;; clj -M:dev download-airtable -k $API_KEY -b BASE_ID -n reading-log

  ;; 2. Discover field keys
  (core/get-field-keys "airtable_data/books_xxx.edn")
  (core/get-field-keys "airtable_data/reading_log_xxx.edn")

  ;; 3. Preview conversions
  (extract-unique-book-sources "airtable_data/books_xxx.edn")
  (convert-airtable-books
   "airtable_data/books_xxx.edn"
   #uuid "00000000-0000-0000-0000-000000000000")

  ;; 4. Connect to prod and write
  (require '[repl :refer [prod-node-start get-prod-db-context]])
  (def prod-node (prod-node-start))
  (def ctx (get-prod-db-context prod-node))

  ;; Write book-sources first (catalog of where books came from)
  (write-book-sources-to-db ctx "airtable_data/books_xxx.edn" "email@example.com")

  ;; Then write books (reference book-sources)
  (write-books-to-db ctx "airtable_data/books_xxx.edn" "email@example.com")

  ;; Then write reading logs (reference books)
  (write-reading-logs-to-db ctx
                            "airtable_data/reading_log_xxx.edn"
                            "airtable_data/books_xxx.edn"
                            "email@example.com")
  ;;
  )
