(ns tech.jgood.gleanmo.goals.dashboard
  "Orchestration for the goals dashboard.

   Reads each goal source's records once — one scan per source, shared by
   every goal on it, over the union of their windows — through
   `db/queries.clj`, then computes every goal's progress with the pure
   functions in `goals/calc.clj`. Book goals read their one book's logs,
   bound to that book."
  (:require
   [tech.jgood.gleanmo.db.queries :as queries]
   [tech.jgood.gleanmo.goals.calc :as calc]
   [tech.jgood.gleanmo.goals.registry :as registry]
   [tech.jgood.gleanmo.schema.meta :as sm])
  (:import
   [java.time Instant LocalDate]))

(def history-days
  "Length of the activity strip: twelve weeks of completed days."
  84)

(def recent-days
  "Length of the open-ended goal's recent-rhythm panel."
  28)

(def ^:private interval-lookback-days
  "Intervals are dated by their beginning but clipped to the window, so a
   duration scan starts this many days early to catch intervals that began
   before the window and ended inside it."
  3)

(def reading-duration
  (registry/measurement {:goal/source      :reading-log
                         :goal/measure     :duration
                         :goal/aggregation :total}))

(defn- min-date [^LocalDate a ^LocalDate b] (if (.isBefore a b) a b))
(defn- min-inst [^Instant a ^Instant b] (if (.isBefore a b) a b))
(defn- max-inst [^Instant a ^Instant b] (if (.isAfter a b) a b))

(defn- request-window
  "The instants one goal needs records for: its counting start or the
   activity strip's start, whichever is earlier, through the cutoff."
  [goal m cutoff-date]
  (let [zone  (calc/zone-of goal)
        start (min-date (:goal/starts-on goal)
                        (calc/plus-days cutoff-date (- history-days)))
        start (cond-> start
                (= :duration (:measure m))
                (calc/plus-days (- interval-lookback-days)))]
    {:since (calc/day-start start zone)
     :until (calc/day-start cutoff-date zone)}))

(defn source-requests
  "One records request per source, covering every goal that reads it. The
   relation scope is the union of the goals' filters, or everything when any
   goal on the source is unfiltered."
  [entries cutoff-date]
  (into {}
        (for [[source es] (group-by (comp :source :measurement) entries)]
          (let [windows (map #(request-window (:goal %) (:measurement %) cutoff-date) es)
                filters (map #(get (:goal %) (get-in % [:measurement :relation :key]))
                             es)]
            [source {:source       source
                     :since        (reduce min-inst (map :since windows))
                     :until        (reduce max-inst (map :until windows))
                     :relation-ids (when (every? seq filters)
                                     (reduce into #{} filters))}]))))

(defn- hidden?
  [entity {:keys [show-sensitive show-archived]}]
  (let [t (some-> (::sm/type entity) name)]
    (or (and (not show-sensitive) (true? (get entity (keyword t "sensitive"))))
        (and (not show-archived) (true? (get entity (keyword t "archived")))))))

(defn- related-entities
  "`{id entity}` for every record the goals filter on that the user owns."
  [db user-id goals]
  (let [ids (->> goals
                 (mapcat (fn [g] (mapcat #(get g %) registry/relation-keys)))
                 distinct)]
    (->> (queries/fetch-entities-by-ids db ids)
         (filter #(= user-id (:user/id %)))
         (remove ::sm/deleted-at)
         (into {} (map (juxt :xt/id identity))))))

(defn entity-label
  [entity]
  (let [t (some-> (::sm/type entity) name)]
    (or (get entity (keyword t "label"))
        (get entity (keyword t "title"))
        "Untitled")))

(defn- scope
  "Labels of the goal's selected relation records, or nil for all."
  [goal m related]
  (when-let [k (get-in m [:relation :key])]
    (when-let [ids (seq (get goal k))]
      (mapv #(if-let [e (get related %)] (entity-label e) "Unavailable") ids))))

(defn- numeric-entry
  [{:keys [goal measurement] :as entry} records cutoff-date]
  (let [progress (calc/numeric-progress goal measurement records cutoff-date)]
    (assoc entry
           :progress progress
           :history (calc/history goal measurement records cutoff-date history-days)
           :recent (when (= :open-ended (:goal/timing goal))
                     (calc/recent-activity goal measurement records cutoff-date
                                           recent-days))
           ;; No source has coverage metadata yet, so every comparison reports
           ;; unknown history rather than inferring zero activity.
           :comparison (when (not= :open-ended (:goal/timing goal))
                         (calc/comparison goal measurement records
                                          (:logged progress) (:window progress)
                                          1 nil)))))

(defn- book-entry
  [db user-id {:keys [goal related] :as entry} cutoff-date]
  (let [book-id (first (:goal/book-ids goal))
        book    (get related book-id)
        logs    (if book (queries/reading-logs-for-book db user-id book-id) [])
        records (mapv (fn [l]
                        {:id        (:xt/id l)
                         :at        (:reading-log/beginning l)
                         :end       (:reading-log/end l)
                         :open?     (nil? (:reading-log/end l))
                         :relations #{book-id}})
                      logs)]
    (assoc entry
           :book book
           :book-progress (calc/book-progress goal (or book {}) logs cutoff-date)
           :history (calc/history goal reading-duration records cutoff-date
                                  history-days))))

(defn dashboard
  "Everything the goals page renders, computed as of `cutoff-date` (today in
   the user's time zone; only earlier days count).

   Returns `{:active [entry] :archived [goal] :hidden-count n}`. Goals whose
   selected records the user's visibility settings hide are left out of both
   lists and counted instead."
  [db user-id {:keys [cutoff-date user-settings]}]
  (let [goals    (queries/goals-for-user db user-id)
        related  (related-entities db user-id goals)
        settings (or user-settings (queries/get-user-settings db user-id))
        visible? (fn [g]
                   (not-any? (fn [k]
                               (some #(let [e (get related %)]
                                        (or (nil? e) (hidden? e settings)))
                                     (get g k)))
                             registry/relation-keys))
        {archived true, active false} (group-by #(true? (:goal/archived %))
                                                (filter visible? goals))
        entries  (for [g active
                       :let [m (registry/measurement g)]
                       :when m]
                   {:goal        g
                    :measurement m
                    :related     related
                    :scope       (scope g m related)})
        {books true, numeric false} (group-by #(= :completion
                                                  (get-in % [:measurement :aggregation]))
                                              entries)
        records  (into {}
                       (for [[source req] (source-requests numeric cutoff-date)]
                         [source (queries/goal-source-records
                                  db user-id (assoc req :user-settings settings))]))]
    {:cutoff-date  cutoff-date
     :active       (vec (concat
                         (map #(numeric-entry % (get records (get-in % [:measurement :source]))
                                              cutoff-date)
                              numeric)
                         (map #(book-entry db user-id % cutoff-date) books)))
     :archived     (vec (sort-by :goal/label archived))
     :hidden-count (- (count goals) (count (filter visible? goals)))}))
