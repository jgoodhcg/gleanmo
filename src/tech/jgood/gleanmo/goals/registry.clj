(ns tech.jgood.gleanmo.goals.registry
  "The measurement registry: every source/measure/aggregation combination a
   goal may use, with its relation filter, unit, and wording.

   Pure data with no dependencies, so the schema rules, the queries, the
   calculations, and the editor all read the same allowlist. Adding a
   measurement is adding an entry here plus its calculation in
   `tech.jgood.gleanmo.goals.calc`."
  (:require
   [clojure.string :as str]))

(def sources
  "Goal sources, with the optional relation filter each supports."
  {:project-log      {:label    "Project logs"
                      :relation {:key :goal/project-ids, :entity :project,
                                 :label "Projects", :field :project-log/project-id}}
   :reading-log      {:label    "Reading logs"
                      :relation {:key :goal/book-ids, :entity :book,
                                 :label "Books", :field :reading-log/book-id}}
   :meditation-log   {:label    "Meditation logs"
                      :relation {:key :goal/meditation-ids, :entity :meditation,
                                 :label "Meditations", :field :meditation-log/type-id}}
   :habit-log        {:label    "Habit logs"
                      :relation {:key :goal/habit-ids, :entity :habit,
                                 :label "Habits", :field :habit-log/habit-ids}}
   :exercise-session {:label "Exercise sessions"}
   :boulder-session  {:label "Boulder sessions"}
   :boulder-attempt  {:label "Boulder attempts"}
   :exercise-line    {:label    "Exercise lines"
                      :relation {:key :goal/exercise-ids, :entity :exercise,
                                 :label "Exercises", :field :exercise-line/exercise-id}}})

(def relation-keys
  "Every goal relation-filter attribute."
  (into #{} (keep (comp :key :relation)) (vals sources)))

(def ^:private timings-by-aggregation
  {:total      #{:dated :weekly :open-ended}
   :best       #{:dated :open-ended}
   :completion #{:dated :open-ended}})

(def ^:private base-measurements
  [{:source :project-log, :measure :duration, :aggregation :total, :unit :seconds,
    :label "Time spent",
    :counts "Completed project-log intervals. Overlapping intervals count once; intervals crossing local midnight are split between dates."}
   {:source :reading-log, :measure :duration, :aggregation :total, :unit :seconds,
    :label "Time reading",
    :counts "Completed reading intervals across all recorded formats. Overlapping intervals count once; time is split at local midnight. This measures time invested, not book completion."}
   {:source :meditation-log, :measure :duration, :aggregation :total, :unit :seconds,
    :label "Time meditating",
    :counts "Completed meditation intervals. Overlapping intervals count once; open timers are excluded."}
   {:source :meditation-log, :measure :records, :aggregation :total, :unit :sessions,
    :label "Sessions",
    :counts "Completed meditation logs, each counted once on its local beginning date. Interrupted sessions count once their interval has ended."}
   {:source :habit-log, :measure :records, :aggregation :total, :unit :logs,
    :label "Logs",
    :counts "Habit logs by local timestamp date. A log counts once even when several selected habits match it; several logs on one day each count."}
   {:source :exercise-session, :measure :records, :aggregation :total, :unit :sessions,
    :label "Sessions",
    :counts "Completed exercise sessions on the local date each began."}
   {:source :boulder-session, :measure :records, :aggregation :total, :unit :sessions,
    :label "Sessions",
    :counts "Completed boulder sessions on the local date each began. Several sessions on one day each count."}
   {:source :boulder-attempt, :measure :attempts, :aggregation :total, :unit :attempts,
    :label "Attempts",
    :counts "Sum of recorded attempts per completed boulder attempt; a missing attempts count contributes one. Laps do not multiply attempts. Sent or not, an attempt counts on its local beginning date."}
   {:source :boulder-attempt, :measure :duration, :aggregation :total, :unit :seconds,
    :label "Time on the wall",
    :counts "Completed boulder-attempt intervals. Rest between attempts is excluded; overlapping intervals count once."}
   {:source :exercise-line, :measure :reps, :aggregation :total, :unit :reps,
    :label "Reps",
    :counts "Sum of reps from exercise lines, each counted on the local date its parent set began."}
   {:source :exercise-line, :measure :weight, :aggregation :best, :unit :kg,
    :label "Best weight",
    :counts "Greatest weight on one exercise line with at least one rep. Pounds convert to kilograms; lines without a weight unit are excluded. This is a best performance, not accumulated weight."}
   {:source :exercise-line, :measure :duration, :aggregation :best, :unit :seconds,
    :label "Best duration",
    :counts "Longest explicitly recorded duration on one exercise line. A set's interval is never used, because a set can contain several exercises."}
   {:source :reading-log, :measure :book-completion, :aggregation :completion, :unit nil,
    :label "Finish a book",
    :counts "Complete when a reading log for this book is marked finished. Reaching a book total never substitutes for the finished flag."}])

(def ^:private kind-by-measure
  {:duration :duration, :records :count, :reps :count, :attempts :count,
   :weight :best, :book-completion :completion})

(defn- enrich
  [{:keys [source measure aggregation unit] :as m}]
  (let [relation (get-in sources [source :relation])]
    (assoc m
           :id          (keyword (name source) (str (name measure) "-" (name aggregation)))
           :kind        (if (= :best aggregation) :best (kind-by-measure measure))
           :integer?    (contains? #{:sessions :logs :reps :attempts} unit)
           :timings     (timings-by-aggregation aggregation)
           :relation    relation
           ;; Duration totals are entered in hours; best durations are short
           ;; holds entered in seconds. Stored values are always canonical.
           :input-unit  (cond
                          (and (= unit :seconds) (= aggregation :total))
                          {:label "hours", :factor 3600}
                          (= unit :seconds) {:label "seconds", :factor 1}
                          unit              {:label (name unit), :factor 1}))))

(def measurements
  "Every supported measurement, enriched with derived attributes."
  (mapv enrich base-measurements))

(def by-id
  (into {} (map (juxt :id identity)) measurements))

(defn measurement
  "The registry entry for a goal (or any map carrying `:goal/source`,
   `:goal/measure`, and `:goal/aggregation`), or nil if unsupported."
  [{:goal/keys [source measure aggregation]}]
  (some #(when (and (= source (:source %))
                    (= measure (:measure %))
                    (= aggregation (:aggregation %)))
           %)
        measurements))

(defn id->string
  "Form value for a measurement id."
  [id]
  (str (namespace id) "/" (name id)))

(defn string->measurement
  "Resolve a source/measurement form string, or return nil for invalid input."
  [s]
  (when (string? s)
    (let [[ns-part name-part] (str/split s #"/" 2)]
      (when (and ns-part name-part)
        (get by-id (keyword ns-part name-part))))))

(def unit-labels
  {:seconds "seconds", :sessions "sessions", :logs "logs", :reps "reps",
   :attempts "attempts", :kg "kg"})

(def timing-labels
  {:dated "Dated", :weekly "Weekly reset", :open-ended "No deadline"})
