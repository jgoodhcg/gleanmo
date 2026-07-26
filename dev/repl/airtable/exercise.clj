(ns repl.airtable.exercise
  "Airtable migration for exercise, exercise-session, exercise-set, and
   exercise-line entities.

   Airtable has two tables: 'exercises' (name, source, notes, aliases,
   pt-recommended) and 'exercise log' (timestamp, stopwatch duration in
   seconds, reps, weight, distance, sparse one-off columns). There is no
   session or set structure — each log row becomes one exercise-set (timed
   interval) holding one exercise-line (the reps/weight record), and
   sessions are synthesized by splitting the time-sorted log rows wherever
   consecutive rows are more than an hour apart.

   Field notes from the 2026-07-26 export:
   - duration is stopwatch seconds ('duration minutes' is the same value
     / 60). Rows without it, with 0, or with implausible values (a
     stopwatch left running for hours) import as auto-started zero-length
     sets; implausible originals are kept on airtable/original-duration.
   - 145 rows are pure junk (no timestamp/exercise/reps/duration/notes)
     and 65 rows have only a timestamp with no exercise link — both are
     skipped and counted in the migration report.
   - reps is occasionally a formula artifact double (14.909...) — rounded,
     original kept. 'breaths' (breathing work) fills reps when reps is
     absent; raw value kept on airtable/breaths.
   - weight has no unit in Airtable; everything was logged in lbs.
     distance is treadmill miles.
   - 'better/worse than normal set' booleans and 'Angle', 'steps
     (treadmill numbers)' map to airtable/* lineage fields on the line."
  (:require
   [clojure.string :as str]
   [malli.core :as m]
   [repl.airtable.core :as core]
   [tech.jgood.gleanmo :as main]
   [tech.jgood.gleanmo.schema.exercise-schema :as es]
   [tech.jgood.gleanmo.schema.meta :as sm]
   [tick.core :as t]))

(def exercise-namespace-uuid
  "Namespace UUID for exercise entities, derived from Airtable ids."
  #uuid "c1d2e3f4-a5b6-4789-9abc-def012345678")

(def session-namespace-uuid
  "Namespace UUID for synthesized exercise-session entities, derived from
   the first log row's Airtable id in each segment."
  #uuid "d2e3f4a5-b6c7-489a-abcd-ef0123456789")

(def set-namespace-uuid
  "Namespace UUID for exercise-set entities, derived from Airtable log ids."
  #uuid "e3f4a5b6-c7d8-49ab-bcde-f01234567890")

(def line-namespace-uuid
  "Namespace UUID for exercise-line entities, derived from Airtable log ids."
  #uuid "f4a5b6c7-d8e9-4abc-9def-012345678901")

(def session-gap-minutes
  "A new session starts when consecutive log rows are at least this far
   apart. Airtable days often hold several distinct workout/PT blocks
   (morning stretch, evening gym), so day-bucketing like bouldering's
   would be wrong here."
  60)

(def max-plausible-duration-seconds
  "Stopwatch values beyond this (2h) are treated as left-running mistakes;
   the longest legitimate logged set is ~42 minutes."
  (* 2 60 60))

;; =============================================================================
;; Exercises
;; =============================================================================

(defn airtable->exercise
  "Nil for nameless exercises that were never logged (empty Airtable rows);
   a nameless exercise with logs gets a placeholder label."
  [{:strs [id createdTime fields]} user-id now]
  (let [{:strs [name source notes aliases pt-recommended]} fields
        log-count (get fields "log-count" 0)
        label     (some-> name str/trim not-empty)
        created   (or (core/parse-timestamp (get fields "created-at"))
                      (core/parse-timestamp createdTime))]
    (when (or label (pos? log-count))
      (cond->
       {:db/doc-type :exercise
        :xt/id (core/deterministic-uuid exercise-namespace-uuid id)
        ::sm/type :exercise
        ::sm/created-at created
        :user/id user-id
        :exercise/label (or label "Unnamed exercise (Airtable)")
        :airtable/id id
        :airtable/created-time created
        :airtable/ported-at now
        :airtable/log-count log-count}
        source (assoc :exercise/source source)
        notes (assoc :exercise/notes notes)
        aliases (assoc :airtable/aliases aliases)
        (some? pt-recommended) (assoc :airtable/pt-recommended
                                      (boolean pt-recommended))))))

;; =============================================================================
;; Log rows -> sessions / sets / lines
;; =============================================================================

(defn log-end
  "The Airtable timestamp — the moment the row was logged, i.e. the set's
   end."
  [{:strs [createdTime fields]}]
  (core/parse-timestamp (or (get fields "timestamp") createdTime)))

(defn importable-log?
  "A log row is importable when it links an exercise and has a timestamp.
   Rows failing this carry no other data in the export (verified 2026-07-26)."
  [rec]
  (boolean (and (seq (get-in rec ["fields" "exercise"]))
                (log-end rec))))

(defn- set-interval
  "Beginning/end for a log row. Beginning is end minus the stopwatch
   seconds; absent, zero, or implausible durations give a zero-length
   auto-started interval."
  [{:strs [fields] :as rec}]
  (let [end     (log-end rec)
        d       (get fields "duration")
        usable? (and (number? d)
                     (pos? d)
                     (<= d max-plausible-duration-seconds))]
    {:end          end
     :beginning    (if usable?
                     (t/<< end (t/new-duration (long d) :seconds))
                     end)
     :auto-started (not usable?)
     :original-duration (when (and (number? d)
                                   (> d max-plausible-duration-seconds))
                          d)}))

(defn segment-logs
  "Sort importable log rows by end timestamp and split into session
   segments wherever the gap to the previous row is >= session-gap-minutes."
  [recs]
  (let [gap    (t/new-duration session-gap-minutes :minutes)
        sorted (sort-by (juxt log-end #(get % "id")) recs)]
    (reduce (fn [segments rec]
              (let [prev-end (some-> (peek segments) peek log-end)]
                (if (and prev-end
                         (t/< (log-end rec) (t/>> prev-end gap)))
                  (conj (pop segments) (conj (peek segments) rec))
                  (conj segments [rec]))))
            []
            sorted)))

(defn segment->session
  "Synthesize an exercise-session spanning a segment's first set beginning
   to its last set end. Deterministic id from the first row's Airtable id."
  [segment user-id now]
  (let [intervals (map set-interval segment)
        beginning (first (sort (map :beginning intervals)))
        end       (last (sort (map :end intervals)))]
    {:db/doc-type :exercise-session
     :xt/id (core/deterministic-uuid session-namespace-uuid
                                     (get (first segment) "id"))
     ::sm/type :exercise-session
     ::sm/created-at beginning
     :user/id user-id
     :exercise-session/beginning beginning
     :exercise-session/end end
     :airtable/ported-at now}))

(defn log->set
  [{:strs [id createdTime fields] :as rec} session-id user-id now]
  (let [{:keys [beginning end auto-started original-duration]} (set-interval rec)
        created (core/parse-timestamp createdTime)]
    (cond->
     {:db/doc-type :exercise-set
      :xt/id (core/deterministic-uuid set-namespace-uuid id)
      ::sm/type :exercise-set
      ::sm/created-at created
      :user/id user-id
      :exercise-set/session-id session-id
      :exercise-set/beginning beginning
      :exercise-set/end end
      :airtable/id id
      :airtable/created-time created
      :airtable/ported-at now}
      auto-started (assoc :exercise-set/auto-started true)
      original-duration (assoc :airtable/original-duration original-duration)
      (first (get fields "exercise")) (assoc :airtable/exercise-id
                                             (first (get fields "exercise"))))))

(defn- round-int [n] (int (Math/round (double n))))

(defn log->line
  [{:strs [id createdTime fields]} user-id now]
  (let [{:strs [reps weight distance notes breaths]} fields
        angle   (get fields "Angle")
        steps   (get fields "steps (treadmill numbers)")
        better  (get fields "better than normal set")
        worse   (get fields "worse than normal set")
        created (core/parse-timestamp createdTime)
        reps'   (cond
                  (int? reps)      reps
                  (number? reps)   (round-int reps)
                  (number? breaths) (round-int breaths)
                  :else            nil)]
    (cond->
     {:db/doc-type :exercise-line
      :xt/id (core/deterministic-uuid line-namespace-uuid id)
      ::sm/type :exercise-line
      ::sm/created-at created
      :user/id user-id
      :exercise-line/set-id (core/deterministic-uuid set-namespace-uuid id)
      :exercise-line/exercise-id
      (core/deterministic-uuid exercise-namespace-uuid
                               (first (get fields "exercise")))
      :airtable/id id
      :airtable/created-time created
      :airtable/ported-at now}
      reps' (assoc :exercise-line/reps reps')
      (and (number? reps) (not (int? reps))) (assoc :airtable/original-reps reps)
      (number? weight) (assoc :exercise-line/weight weight
                              :exercise-line/weight-unit :lbs)
      (number? distance) (assoc :exercise-line/distance distance
                                :exercise-line/distance-unit :miles)
      (number? breaths) (assoc :airtable/breaths breaths)
      (number? steps) (assoc :airtable/steps steps)
      (number? angle) (assoc :airtable/angle angle)
      better (assoc :airtable/better-than-normal true)
      worse (assoc :airtable/worse-than-normal true)
      notes (assoc :exercise-line/notes notes))))

;; =============================================================================
;; Validation
;; =============================================================================

(defn- validate-with
  [schema entities]
  (let [registry  (:registry main/malli-opts)
        validator (m/validator schema {:registry registry})
        results   (map (fn [e] {:entity e :valid? (validator e)}) entities)
        failed    (filter #(not (:valid? %)) results)]
    {:passed (count (filter :valid? results))
     :failed (map :entity failed)
     :total  (count results)}))

(defn validate-exercises [xs] (validate-with es/exercise xs))
(defn validate-sessions [xs] (validate-with es/exercise-session xs))
(defn validate-sets [xs] (validate-with es/exercise-set xs))
(defn validate-lines [xs] (validate-with es/exercise-line xs))
