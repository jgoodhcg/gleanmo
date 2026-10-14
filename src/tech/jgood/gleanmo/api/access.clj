(ns tech.jgood.gleanmo.api.access
  "What an API token may touch. Pure functions over the token document, so
   every rule here is checkable without a database or a request.

   Tokens are granted entity *groups*. A type that belongs to no group is
   unreachable through the API whatever the token says — that is how `:user`,
   `:ical-url` (its URL is a credential), and `:api-token` itself stay out."
  (:require
   [clojure.string :as str])
  (:import
   [java.security MessageDigest SecureRandom]
   [java.util Base64]))

(def groups
  "Permission groups in display order. `:types` are the entity types the group
   covers. A `:read-only` group is never writable through the API, whatever a
   token says: its data is owned by a sync (Cronometer), and the next sync
   would overwrite an agent's edit."
  [{:key :tasks,      :label "Tasks & projects",
    :types [:task :project :project-log]}
   {:key :exercise,   :label "Exercise",
    :types [:exercise :exercise-session :exercise-set :exercise-line]}
   {:key :bouldering, :label "Bouldering",
    :types [:boulder-session :boulder-problem :boulder-attempt]}
   {:key :habits,     :label "Habits",
    :types [:habit :habit-log]}
   {:key :meditation, :label "Meditation",
    :types [:meditation :meditation-log]}
   {:key :reading,    :label "Reading",
    :types [:book :book-source :reading-log]}
   {:key :calendar,   :label "Calendar events",
    :types [:calendar-event]}
   {:key :goals,      :label "Goals",
    :types [:goal]}
   {:key :locations,  :label "Locations",
    :types [:location]}
   {:key :mood,       :label "Mood",
    :types [:mood-log]}
   {:key :symptoms,   :label "Symptoms",
    :types [:symptom-episode :symptom-log]}
   {:key :medication, :label "Medication",
    :types [:medication :medication-log]}
   {:key :bm,         :label "BM logs",
    :types [:bm-log]}
   {:key :nutrition,  :label "Nutrition",
    :types [:food :food-log :nutrition-day], :read-only true}
   {:key :biometrics, :label "Biometrics",
    :types [:biometric-log], :read-only true}
   {:key :activities, :label "Activities",
    :types [:activity-observation], :read-only true}])

(def group-keys (set (map :key groups)))

(def read-only-groups
  (into #{} (comp (filter :read-only) (map :key)) groups))

(def type->group
  (into {}
        (for [{:keys [key types]} groups
              t types]
          [t key])))

;; ---------------------------------------------------------------------------
;; Tokens
;; ---------------------------------------------------------------------------

(def token-prefix "gmn_")

(defn generate-token
  "A new random bearer token: `gmn_` and 32 random bytes, base64url."
  []
  (let [bytes (byte-array 32)]
    (.nextBytes (SecureRandom.) bytes)
    (str token-prefix
         (.encodeToString (.withoutPadding (Base64/getUrlEncoder)) bytes))))

(defn hash-token
  "Hex SHA-256 of `token`. The token carries 256 random bits, so a fast hash
   is the right tool — a slow password hash would buy nothing."
  [token]
  (let [digest (.digest (MessageDigest/getInstance "SHA-256")
                        (.getBytes (str token) "UTF-8"))]
    (apply str (map #(format "%02x" (bit-and % 0xff)) digest))))

(defn display-prefix
  "The part of `token` safe to show in a token list."
  [token]
  (subs token 0 (min (count token) (+ (count token-prefix) 8))))

(defn bearer-token
  "The token from an `Authorization: Bearer …` header value, or nil."
  [header]
  (when-let [[_ token] (some->> header (re-matches #"(?i)Bearer\s+(\S+)\s*"))]
    token))

(defn usable?
  "Whether a stored token document may authenticate a request."
  [token-doc]
  (and (some? token-doc)
       (nil? (:api-token/revoked-at token-doc))
       (nil? (:tech.jgood.gleanmo.schema.meta/deleted-at token-doc))))

;; ---------------------------------------------------------------------------
;; Permissions
;; ---------------------------------------------------------------------------

(defn- granted-groups
  [token-doc access]
  (let [writes (into #{}
                     (remove read-only-groups)
                     (:api-token/write-groups token-doc))]
    (case access
      :write writes
      ;; A write grant on a read-only group still grants read.
      :read  (into (set (:api-token/write-groups token-doc))
                   (:api-token/read-groups token-doc)))))

(defn can?
  "Whether `token-doc` grants `access` (`:read` or `:write`) on `entity-type`."
  [token-doc access entity-type]
  (boolean
   (when-let [group (type->group entity-type)]
     (contains? (granted-groups token-doc access) group))))

(defn readable-types
  "Every entity type the token can read, in group order."
  [token-doc]
  (filterv #(can? token-doc :read %) (mapcat :types groups)))

(defn sensitive-granted?
  [token-doc]
  (true? (:api-token/sensitive-granted token-doc)))

(defn archived-granted?
  [token-doc]
  (true? (:api-token/archived-granted token-doc)))

(defn visibility-errors
  "Errors for a request asking to see sensitive or archived records the token
   was not granted. An empty vector means the request may proceed."
  [token-doc {:keys [include-sensitive include-archived]}]
  (cond-> []
    (and include-sensitive (not (sensitive-granted? token-doc)))
    (conj "This token is not granted sensitive records.")
    (and include-archived (not (archived-granted? token-doc)))
    (conj "This token is not granted archived records.")))

(defn permissions-summary
  "A plain-data description of the token's grants, for the API and the UI."
  [token-doc]
  {:read      (vec (sort (map name (granted-groups token-doc :read))))
   :write     (vec (sort (map name (granted-groups token-doc :write))))
   :sensitive (sensitive-granted? token-doc)
   :archived  (archived-granted? token-doc)})

(defn parse-group
  "The group keyword for a submitted group name, or nil if it is not one."
  [s]
  (let [k (keyword (str/trim (str s)))]
    (when (group-keys k) k)))
