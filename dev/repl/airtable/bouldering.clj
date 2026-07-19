(ns repl.airtable.bouldering
  "Airtable migration for boulder-problem, boulder-session, and
   boulder-attempt entities.

   Airtable has two tables: 'bouldering problems' (circuit difficulty, hold
   color, wall, gym) and 'bouldering tries' (timestamp, duration, sent/Top/
   flash, laps, one-off booleans). There is no session table — sessions are
   synthesized here, one per Airtable 'day' value, spanning that day's first
   to last try.

   Field notes from the 2026-07-19 export:
   - duration is the watch stopwatch in centiseconds (avg ~4900 ≈ 49s);
     converted to seconds here.
   - one-off booleans (dabs, peel, Bailed, Off start, Reversed, Foot slip,
     intentional-practice-dabs) map to :boulder-attempt/tags.
   - relativity free text ('better', 'Warmup', 'Gassed') maps to
     :boulder-attempt/feel."
  (:require
   [clojure.string :as str]
   [malli.core :as m]
   [repl.airtable.core :as core]
   [tech.jgood.gleanmo :as main]
   [tech.jgood.gleanmo.schema.bouldering-schema :as bs]
   [tech.jgood.gleanmo.schema.meta :as sm]))

(def problem-namespace-uuid
  "Namespace UUID for boulder-problem entities, derived from Airtable ids."
  #uuid "e5f6a7b8-c9d0-1234-efab-4567890abcde")

(def session-namespace-uuid
  "Namespace UUID for synthesized boulder-session entities, derived from the
   Airtable 'day' value."
  #uuid "f6a7b8c9-d0e1-2345-fabc-567890abcdef")

(def attempt-namespace-uuid
  "Namespace UUID for boulder-attempt entities, derived from Airtable ids."
  #uuid "a7b8c9d0-e1f2-3456-abcd-67890abcdef1")

(def default-gym "Terra firma")

(def tag-field->tag
  {"dabs"                      :dab
   "intentional-practice-dabs" :intentional-practice-dab
   "peel"                      :peel
   "Bailed"                    :bail
   "Off start"                 :off-start
   "Reversed"                  :reversed
   "Foot slip"                 :foot-slip})

(defn airtable->problem
  [{:strs [id createdTime fields]} user-id now]
  (let [{:strs [gym difficulty hold-color wall notes Archived]} fields
        created (core/parse-timestamp createdTime)]
    (cond->
     {:db/doc-type :boulder-problem
      :xt/id (core/deterministic-uuid problem-namespace-uuid id)
      ::sm/type :boulder-problem
      ::sm/created-at created
      :user/id user-id
      :boulder-problem/gym (or gym default-gym)
      :boulder-problem/difficulty (or difficulty "Ungraded")
      :airtable/id id
      :airtable/created-time created
      :airtable/ported-at now}
      hold-color (assoc :boulder-problem/hold-color hold-color)
      wall (assoc :boulder-problem/wall wall)
      notes (assoc :boulder-problem/notes notes)
      (some? Archived) (assoc :boulder-problem/archived (boolean Archived))
      (int? (get fields "id"))
      (assoc :airtable/original-problem-number (get fields "id")))))

(defn- day-str
  "The record's 'day' value, nil unless it's a usable string — a handful of
   Airtable rows carry a formula-error map here instead."
  [rec]
  (let [d (get-in rec ["fields" "day"])]
    (when (and (string? d) (not (str/blank? d))) d)))

(defn- try-instant
  [{:strs [createdTime fields]}]
  (core/parse-timestamp (or (get fields "timestamp") createdTime)))

(defn tries->sessions
  "Synthesize one boulder-session per Airtable 'day', spanning that day's
   first to last try. Gym comes from the day's problems via problem-gym-by-rec
   (Airtable problem record id -> gym), falling back to the default gym."
  [try-records problem-gym-by-rec user-id now]
  (->> try-records
       (group-by day-str)
       (keep
        (fn [[day recs]]
          (when day
            (let [instants (sort (keep try-instant recs))
                  gym      (or (->> recs
                                    (keep #(-> % (get-in ["fields" "problem"]) first problem-gym-by-rec))
                                    frequencies
                                    (sort-by val >)
                                    ffirst)
                               default-gym)]
              {:db/doc-type :boulder-session
               :xt/id (core/deterministic-uuid session-namespace-uuid day)
               ::sm/type :boulder-session
               ::sm/created-at (first instants)
               :user/id user-id
               :boulder-session/gym gym
               :boulder-session/beginning (first instants)
               :boulder-session/end (last instants)
               :airtable/ported-at now}))))
       vec))

(defn airtable->attempt
  [{:strs [id createdTime fields] :as rec} user-id now]
  (let [{:strs [duration sent flash Top Laps notes relativity problem]} fields
        day      (day-str rec)
        created  (core/parse-timestamp createdTime)
        ts       (try-instant rec)
        tags     (set (keep (fn [[field tag]] (when (get fields field) tag))
                            tag-field->tag))
        prob-rec (first problem)]
    (cond->
     {:db/doc-type :boulder-attempt
      :xt/id (core/deterministic-uuid attempt-namespace-uuid id)
      ::sm/type :boulder-attempt
      ::sm/created-at created
      :user/id user-id
      :boulder-attempt/timestamp ts
      :boulder-attempt/sent (boolean sent)
      :airtable/id id
      :airtable/created-time created
      :airtable/ported-at now}
      day (assoc :boulder-attempt/session-id
                 (core/deterministic-uuid session-namespace-uuid day))
      prob-rec (assoc :boulder-attempt/problem-id
                      (core/deterministic-uuid problem-namespace-uuid prob-rec))
      flash (assoc :boulder-attempt/flash true)
      Top (assoc :boulder-attempt/top true)
      ;; centiseconds -> seconds
      (int? duration) (assoc :boulder-attempt/duration-seconds
                             (int (/ duration 100)))
      (int? Laps) (assoc :boulder-attempt/laps Laps)
      (seq tags) (assoc :boulder-attempt/tags tags)
      (not (str/blank? relativity)) (assoc :boulder-attempt/feel (str/trim relativity))
      notes (assoc :boulder-attempt/notes notes))))

(defn- validate-with
  [schema entities]
  (let [registry  (:registry main/malli-opts)
        validator (m/validator schema {:registry registry})
        results   (map (fn [e] {:entity e :valid? (validator e)}) entities)
        failed    (filter #(not (:valid? %)) results)]
    {:passed (count (filter :valid? results))
     :failed (map :entity failed)
     :total  (count results)}))

(defn validate-problems [xs] (validate-with bs/boulder-problem xs))
(defn validate-sessions [xs] (validate-with bs/boulder-session xs))
(defn validate-attempts [xs] (validate-with bs/boulder-attempt xs))

(defn problem-gym-lookup
  "Airtable problem record id -> gym string, for session synthesis."
  [problem-records]
  (into {}
        (keep (fn [{:strs [id fields]}]
                (when-let [gym (get fields "gym")] [id gym])))
        problem-records))
