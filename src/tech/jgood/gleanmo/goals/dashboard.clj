(ns tech.jgood.gleanmo.goals.dashboard
  "Orchestration for the goals dashboard.

   Batches each source's overlapping goal windows and preserves gaps through
   `db/queries.clj`, then computes every goal's progress with the pure
   functions in `goals/calc.clj`. Book goals read their one book's logs,
   bound to that book."
  (:require
   [tech.jgood.gleanmo.db.queries :as queries]
   [tech.jgood.gleanmo.goals.calc :as calc]
   [tech.jgood.gleanmo.goals.registry :as registry]
   [tech.jgood.gleanmo.schema.meta :as sm])
  (:import
   [java.time Instant]))

(def history-days
  "Length of the activity strip: twelve weeks of calendar days including today."
  84)

(def recent-days
  "Length of the open-ended goal's recent-rhythm panel."
  28)

(def reading-duration
  (registry/measurement {:goal/source      :reading-log
                         :goal/measure     :duration
                         :goal/aggregation :total}))

(defn- max-inst [^Instant a ^Instant b] (if (.isAfter a b) a b))

(defn- request-windows
  "Actual progress and activity windows for one goal, without the unused gap."
  [goal as-of]
  (let [zone (calc/zone-of goal)
        {:keys [now today observed-through]} (calc/accounting-clock goal as-of)
        {:keys [start end]} (calc/window goal today)
        until (if end
                (let [period-end (calc/day-start (calc/plus-days end 1) zone)]
                  (if (.isBefore period-end now) period-end now))
                now)]
    (filter #(neg? (compare (:since %) (:until %)))
            [{:since (calc/day-start start zone) :until until}
             {:since (calc/day-start (calc/plus-days observed-through (- 1 history-days)) zone)
              :until now}])))

(defn source-requests
  "Bounded requests per source. Merge overlapping ranges, preserving gaps.
   Each entry carries the captured request instant in :now."
  [entries]
  (into {}
        (for [[source es] (group-by (comp :source :measurement) entries)]
          (let [windows (mapcat #(request-windows (:goal %) (:now %)) es)
                filters (map #(get (:goal %) (get-in % [:measurement :relation :key])) es)
                ranges (reduce (fn [acc w]
                                 (if-let [prev (peek acc)]
                                   (if (pos? (compare (:since w) (:until prev)))
                                     (conj acc w)
                                     (conj (pop acc) (update prev :until max-inst (:until w))))
                                   [w]))
                               [] (sort-by :since windows))]
            [source (mapv #(assoc % :source source
                                  :overlap? (boolean (some (fn [e]
                                                             (= :duration (get-in e [:measurement :kind]))) es))
                                  :relation-ids (when (every? seq filters)
                                                  (reduce into #{} filters)))
                          ranges)]))))

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
  "Display label or title of an entity; Untitled when neither exists."
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
  [{:keys [goal measurement] :as entry} records now]
  (let [progress (calc/numeric-progress goal measurement records now)]
    (assoc entry
           :progress progress
           :history (calc/history goal measurement records now history-days)
           :recent (when (= :open-ended (:goal/timing goal))
                     (calc/recent-activity goal measurement records now
                                           recent-days))
           ;; No source has coverage metadata yet, so every comparison reports
           ;; unknown history rather than inferring zero activity.
           :comparison (when (not= :open-ended (:goal/timing goal))
                         (calc/comparison goal measurement records
                                          (:completed-logged progress) (:window progress)
                                          1 nil)))))

(defn- book-entry
  [db user-id {:keys [goal related] :as entry} now settings]
  (let [book-id (first (:goal/book-ids goal))
        book    (get related book-id)
        logs    (if book (queries/reading-logs-for-book db user-id book-id settings) [])
        records (mapv (fn [l]
                        {:id        (:xt/id l)
                         :at        (:reading-log/beginning l)
                         :end       (:reading-log/end l)
                         :open?     (nil? (:reading-log/end l))
                         :relations #{book-id}})
                      logs)]
    (assoc entry
           :book book
           :book-progress (calc/book-progress goal (or book {}) logs now)
           :history (calc/history goal reading-duration records now
                                  history-days))))

(defn dashboard
  "Everything the goals page renders from one captured `now` instant.
   Each goal includes today and computes completed-day pace in its saved zone.

   Returns `{:active [entry] :archived [goal] :hidden-count n}`. Goals whose
   selected records the user's visibility settings hide are left out of both
   lists and counted instead."
  [db user-id {:keys [now user-settings]}]
  {:pre [(instance? Instant now) (map? user-settings)]}
  (let [goals    (queries/goals-for-user db user-id)
        related  (related-entities db user-id goals)
        settings user-settings
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
                   {:now         now
                    :cutoff-date (calc/local-date now (calc/zone-of g))
                    :goal        g
                    :measurement m
                    :related     related
                    :scope       (scope g m related)})
        {books true, numeric false} (group-by #(= :completion
                                                  (get-in % [:measurement :aggregation]))
                                              entries)
        records  (into {}
                       (for [[source requests] (source-requests numeric)]
                         [source (->> requests
                                      (mapcat #(queries/goal-source-records
                                                db user-id (assoc % :user-settings settings)))
                                      (reduce (fn [acc r] (assoc acc (:id r) r)) {})
                                      vals)]))]
    {:now          now
     :active       (vec (concat
                         (map #(numeric-entry % (get records (get-in % [:measurement :source]))
                                              now)
                              numeric)
                         (map #(book-entry db user-id % now settings) books)))
     :archived     (vec (sort-by :goal/label archived))
     :hidden-count (- (count goals) (count (filter visible? goals)))}))
