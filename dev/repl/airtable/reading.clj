(ns repl.airtable.reading
  "Airtable migration for book and reading-log entities.

   Workflow:
   1. Download tables:
      clj -M:dev download-airtable -k $API_KEY -b BASE_ID -n books
      clj -M:dev download-airtable -k $API_KEY -b BASE_ID -n reading-log

   2. In REPL:
      (def prod-node (repl/prod-node-start))
      (def ctx (repl/get-prod-db-context prod-node))

      ;; Preview transformations
      (convert-airtable-books \"airtable_data/books_xxx.edn\" user-id)

      ;; Validate and write
      (write-books-to-db ctx \"airtable_data/books_xxx.edn\" \"email@example.com\")"
  (:require
   [clojure.string :as str]
   [com.biffweb :as biff :refer [q]]
   [malli.core :as m]
   [repl.airtable.core :as core]
   [tech.jgood.gleanmo :as main]
   [tech.jgood.gleanmo.schema.reading-schema :as rs]
   [tech.jgood.gleanmo.schema.meta :as sm]
   [tick.core :as t]))

;; =============================================================================
;; Namespace UUIDs for Deterministic ID Generation
;; =============================================================================

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

(def location-mapping
  "Map Airtable location strings to reading-log schema keywords."
  {"dog park"               :dog-park
   "bed"                    :bed
   "stressless wing chair"  :stressless-wing-chair
   "car"                    :car
   "chair"                  :chair
   "gym"                    :gym
   "kaiti's bed"            :kaitis-bed
   "couch"                  :couch
   "other"                  :other
   "porch"                  :porch
   "beach"                  :beach
   "desk (gaming)"          :desk-gaming
   "deck"                   :deck
   "hammock"                :hammock})

(def format-mapping
  "Map Airtable format strings to reading-log schema keywords."
  {"audiobook"  :audiobook
   "paperback"  :paperback
   "hardcover"  :hardcover})

(def from-mapping
  "Map Airtable 'from' strings to book schema keywords."
  {"library of america"                         :library-of-america
   "amazon"                                     :amazon
   "audible"                                    :audible
   "barnes and nobles woodland mall"            :barnes-and-nobles-woodland-mall
   "the gallery bookstore chicago"              :the-gallery-bookstore-chicago
   "curious book shop east lansing"             :curious-book-shop-east-lansing
   "argos comics and used books grand rapids"   :argos-comics-and-used-books-grand-rapids
   "kurzgesagt shop"                            :kurzgesagt-shop
   "grpl friends of the library sale"           :grpl-friends-of-the-library-sale
   "black dog books and records grand rapids"   :black-dog-books-and-records-grand-rapids
   "schuler books"                              :schuler-books
   "other"                                      :other})

;; =============================================================================
;; UUID Generation
;; =============================================================================

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

(defn airtable->book
  "Transform an Airtable book record into a book entity."
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
      (-> {:xt/id                  (book-uuid title)
           ::sm/type               :book
           ::sm/created-at         (or (core/parse-timestamp created-time) now)
           :db/doc-type            :book
           :user/id                user-id
           :book/title             (str/trim title)
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
            (assoc :book/from
                   (core/parse-enum from-raw from-mapping :other))

            (not (str/blank? notes))
            (assoc :book/notes notes))))))

(defn airtable->reading-log
  "Transform an Airtable reading-log record into a reading-log entity.
   Requires a lookup map from Airtable book IDs to book UUIDs."
  [airtable-record user-id airtable-book-id->uuid now]
  (let [fields        (get airtable-record "fields")
        airtable-id   (get airtable-record "id")
        created-time  (get airtable-record "createdTime")
        beg-str       (get fields "beg")
        end-str       (get fields "end")
        time-zone     (or (get fields "time-zone") "US/Eastern")
        location-str  (get fields "location")
        format-str    (get fields "format")
        finished?     (get fields "finished?")
        notes         (get fields "notes")
        book-ids-raw  (get fields "book")
        book-at-id    (first book-ids-raw)
        book-uuid     (when book-at-id (get airtable-book-id->uuid book-at-id))]
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

            (not (str/blank? location-str))
            (assoc :reading-log/location
                   (core/parse-enum location-str location-mapping :other))

            (not (str/blank? format-str))
            (assoc :reading-log/format
                   (core/parse-enum format-str format-mapping :audiobook))

            (some? finished?)
            (assoc :reading-log/finished? (boolean finished?))

            (not (str/blank? notes))
            (assoc :reading-log/notes notes))))))

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
   Requires a books file to build the airtable-id → book-uuid lookup."
  [logs-file-path books-file-path user-id]
  (let [records              (core/read-airtable-file logs-file-path)
        airtable-book-lookup (build-airtable-book-id-lookup books-file-path)
        now                  (t/now)]
    (->> records
         (map #(airtable->reading-log % user-id airtable-book-lookup now))
         (remove nil?)
         vec)))

;; =============================================================================
;; Validation
;; =============================================================================

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
   Books must already exist."
  [{:keys [biff/db] :as ctx} logs-file-path books-file-path email]
  (let [{user-id :xt/id}
        (first (q db
                  '{:find  (pull ?e [*])
                    :where [[?e :user/email email]]
                    :in    [email]}
                  email))
        logs       (convert-airtable-reading-logs logs-file-path books-file-path user-id)
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
  (convert-airtable-books
   "airtable_data/books_xxx.edn"
   #uuid "00000000-0000-0000-0000-000000000000")

  ;; 4. Connect to prod and write
  (require '[repl :refer [prod-node-start get-prod-db-context]])
  (def prod-node (prod-node-start))
  (def ctx (get-prod-db-context prod-node))

  ;; Write books first (catalog)
  (write-books-to-db ctx "airtable_data/books_xxx.edn" "email@example.com")

  ;; Then write reading logs
  (write-reading-logs-to-db ctx
                            "airtable_data/reading_log_xxx.edn"
                            "airtable_data/books_xxx.edn"
                            "email@example.com")
  ;;
  )
