(ns tech.jgood.gleanmo.goals.calc
  "Pure goal-progress calculations over minimal source records.

   Nothing here reads the database. `tech.jgood.gleanmo.goals.dashboard`
   gathers records (maps of `:at`, `:end`, `:open?`, `:relations`, and the
   measured attributes) and these functions turn them into totals, rates,
   chart series, and states. Semantics follow roadmap/081-goals-dashboard.md
   and the version 08–10 data contracts in
   mockups/2026-09-motivating-dashboards/codex/.

   Totals include eligible records through one captured request instant.
   Pace calculations retain the completed-day cutoff in each goal's zone.
   A LocalDate input denotes midnight starting that date, for date-only reads."
  (:import
   [java.time DayOfWeek Duration Instant LocalDate YearMonth ZoneId]
   [java.time.temporal ChronoUnit IsoFields TemporalAdjusters]))

;; ---------------------------------------------------------------------------
;; Time helpers
;; ---------------------------------------------------------------------------

(defn zone-of
  "The goal's ZoneId, falling back to UTC for a value validation would reject."
  ^ZoneId [goal]
  (try (ZoneId/of (:goal/time-zone goal))
       (catch Exception _ (ZoneId/of "UTC"))))

(defn day-start
  "Local midnight starting `d`, as an instant. Uses the zone's rules, so a DST
   day is 23 or 25 hours long rather than a fixed 86,400 seconds."
  ^Instant [^LocalDate d ^ZoneId zone]
  (.toInstant (.atStartOfDay d zone)))

(defn local-date
  "Calendar date of an Instant in the supplied ZoneId."
  ^LocalDate [^Instant i ^ZoneId zone]
  (.toLocalDate (.atZone i zone)))

(defn plus-days
  "Shift a LocalDate by a signed whole-day count."
  ^LocalDate [^LocalDate d n]
  (.plusDays d (long n)))

(defn days-between
  "Whole days from `a` to `b` (exclusive of `b`)."
  ^long [^LocalDate a ^LocalDate b]
  (.between ChronoUnit/DAYS a b))

(defn- min-date [^LocalDate a ^LocalDate b] (if (.isBefore a b) a b))
(defn- max-date [^LocalDate a ^LocalDate b] (if (.isAfter a b) a b))
(defn- min-inst [^Instant a ^Instant b] (if (.isBefore a b) a b))
(defn- max-inst [^Instant a ^Instant b] (if (.isAfter a b) a b))

(defn accounting-clock
  "Resolve an Instant, or a date-only midnight snapshot, in the goal's zone.
   Returns the current instant, local date, completed-day cutoff, and last
   observed date. At exact midnight, the last observed date is yesterday."
  [goal as-of]
  (let [zone (zone-of goal)
        now (if (instance? LocalDate as-of) (day-start as-of zone) as-of)
        today (local-date now zone)
        midnight (day-start today zone)]
    {:now now :today today :midnight midnight
     :observed-through (if (= now midnight) (plus-days today -1) today)}))

(defn dates
  "Every date from `from` through `through`, inclusive."
  [^LocalDate from ^LocalDate through]
  (take-while #(not (.isAfter ^LocalDate % through))
              (iterate #(plus-days % 1) from)))

;; ---------------------------------------------------------------------------
;; Goal period
;; ---------------------------------------------------------------------------

(defn window
  "The period a goal is measured over as of `cutoff-date`.

   Dated goals span starts-on..ends-on. Weekly goals measure the Monday–Sunday
   week containing today (or the goal's last week once it has ended), clipped
   to the goal's own dates; a clipped week keeps the full target and is marked
   `:partial?`. Open-ended goals run from starts-on with no end.

   `:through` is the last completed day counted, `:completed-days` how many
   days of the period are complete, `:remaining-days` how many are left
   (including today). Status is `:not-started` before the start date and
   `:ended` once the last day has completed."
  [{:goal/keys [timing starts-on ends-on]} ^LocalDate cutoff-date]
  (let [last-done (plus-days cutoff-date -1)
        [start end partial?]
        (case timing
          :weekly
          (let [ref    (cond
                         (and ends-on (.isAfter cutoff-date ends-on)) ends-on
                         (.isBefore cutoff-date starts-on)            starts-on
                         :else                                        cutoff-date)
                monday (.with ^LocalDate ref
                              (TemporalAdjusters/previousOrSame DayOfWeek/MONDAY))
                sunday (plus-days monday 6)
                s      (max-date monday starts-on)
                e      (if ends-on (min-date sunday ends-on) sunday)]
            [s e (or (not= s monday) (not= e sunday))])
          :dated [starts-on ends-on false]
          [starts-on nil false])
        through   (if end (min-date end last-done) last-done)
        completed (max 0 (inc (days-between start through)))
        total     (when end (inc (days-between start end)))]
    {:start          start
     :end            end
     :through        through
     :partial?       partial?
     :completed-days completed
     :total-days     total
     :remaining-days (when total (- total completed))
     :status         (cond
                       (.isBefore cutoff-date starts-on)             :not-started
                       (and ends-on (.isAfter cutoff-date ends-on)) :ended
                       :else                                         :active)}))

;; ---------------------------------------------------------------------------
;; Records → daily values
;; ---------------------------------------------------------------------------

(defn- interval
  [{:keys [at end]}]
  (when (and at end (.isBefore ^Instant at ^Instant end))
    [at end]))

(defn merge-intervals
  "Union of `[start end]` instant pairs, as sorted non-overlapping pairs."
  [intervals]
  (reduce (fn [acc [s e]]
            (if-let [[ps pe] (peek acc)]
              (if (.isAfter ^Instant s ^Instant pe)
                (conj acc [s e])
                (conj (pop acc) [ps (max-inst pe e)]))
              [[s e]]))
          []
          (sort-by first intervals)))

(defn daily-seconds
  "Sorted `{LocalDate seconds}` for the union of `intervals`, clipped to
   `[from, to)` and split at local midnight in `zone`."
  [intervals ^ZoneId zone ^Instant from ^Instant to]
  (->> intervals
       (keep (fn [[s e]]
               (let [s (max-inst s from)
                     e (min-inst e to)]
                 (when (.isBefore ^Instant s ^Instant e) [s e]))))
       merge-intervals
       (reduce (fn [acc [s e]]
                 (loop [acc acc
                        s   s]
                   (let [d       (local-date s zone)
                         seg-end (min-inst e (day-start (plus-days d 1) zone))
                         acc     (update acc d (fnil + 0.0)
                                         (/ (.toMillis (Duration/between s seg-end))
                                            1000.0))]
                     (if (.isBefore ^Instant seg-end ^Instant e)
                       (recur acc seg-end)
                       acc))))
               (sorted-map))))

(def ^:private kg-per-lb 0.45359237)

(defn weight-kg
  "Weight in kilograms, or nil when the unit is missing or unknown."
  [weight unit]
  (when (number? weight)
    (case unit
      :kg  (double weight)
      :lbs (* kg-per-lb weight)
      nil)))

(defn- point-value
  "The value one completed record contributes to a point measurement."
  [{:keys [measure aggregation]} {:keys [open? reps weight weight-unit
                                         duration attempts]}]
  (when-not open?
    (case measure
      :records  1
      :reps     (when (number? reps) reps)
      :attempts (if (number? attempts) attempts 1)
      :weight   (when (and (number? reps) (>= reps 1))
                  (weight-kg weight weight-unit))
      :duration (when (and (= :best aggregation) (number? duration)
                           (pos? duration))
                  duration)
      nil)))

(defn daily-values
  "Sorted `{LocalDate value}` of measurement `m` over `records` dated in
   `[from, to)`. Duration totals union and split intervals; point measures
   date a record by `:at` and combine by sum (totals) or max (best).
   Exclude open intervals and ends beyond cutoff before clipping; cutoff
   defaults to to, but can follow the end of an ended goal period."
  ([m records zone from to]
   (daily-values m records zone from to to))
  ([m records ^ZoneId zone ^Instant from ^Instant to ^Instant cutoff]
   (let [records (filter #(and (not (:open? %))
                               (or (nil? (:end %))
                                   (not (.isAfter ^Instant (:end %) cutoff))))
                         records)]
     (if (and (= :duration (:measure m)) (= :total (:aggregation m)))
       (daily-seconds (keep #(when-not (:open? %) (interval %)) records) zone from to)
       (let [combine (if (= :best (:aggregation m)) max +)]
         (reduce (fn [acc {:keys [at] :as r}]
                   (let [v (point-value m r)]
                     (if (and (some? v) at
                              (not (.isBefore ^Instant at from))
                              (.isBefore ^Instant at to))
                       (update acc (local-date at zone) #(if % (combine % v) v))
                       acc)))
                 (sorted-map)
                 records))))))

(defn scoped-records
  "Records inside the goal's relation filter; every record when it has none."
  [goal m records]
  (let [ids (some-> (get goal (get-in m [:relation :key])) set)]
    (if (seq ids)
      (filter #(some ids (:relations %)) records)
      records)))

;; ---------------------------------------------------------------------------
;; Numeric goals
;; ---------------------------------------------------------------------------

(defn- nice-step
  "The 1/2/5 × 10^k number nearest `x`."
  [x]
  (let [p (Math/pow 10 (Math/floor (Math/log10 x)))
        f (/ x p)]
    (* p (cond (< f 1.5) 1 (< f 3.5) 2 (< f 7.5) 5 :else 10))))

(defn threshold-step
  "The goal's threshold step, or about a tenth of its target in display units."
  [goal m]
  (or (:goal/threshold-step goal)
      (let [factor (double (get-in m [:input-unit :factor] 1))
            step   (nice-step (max 1e-9 (/ (double (:goal/target goal)) factor 10)))]
        (* factor (if (:integer? m) (max 1 (Math/round (double step))) step)))))

(defn progress-series
  "`[[date value] ...]` at day boundaries: the value after each completed day,
   starting from the period start. Totals accumulate; best is a running max
   that stays nil until the first qualifying record."
  [daily ^LocalDate start ^LocalDate through best?]
  (let [init [start (if best? nil 0.0)]]
    (if (.isBefore through start)
      [init]
      (vec (reductions (fn [[_ acc] d]
                         (let [v (get daily d)]
                           [(plus-days d 1)
                            (if best?
                              (cond (nil? v) acc (nil? acc) v :else (max acc v))
                              (+ acc (or v 0)))]))
                       init
                       (dates start through))))))

(defn- observed-amount
  [daily best?]
  (if best?
    (some->> (vals daily) seq (apply max) double)
    (double (reduce + 0 (vals daily)))))

(defn numeric-progress
  "Progress through as-of, an Instant or a date-only midnight snapshot.
   Totals and charts include today's eligible activity. Pace uses records
   completed by local midnight, attributed only to completed days.
   Required pace and even-pace difference use that same completed-day amount.
   Logged data is authoritative; days without logs contribute zero.
   Best goals have no rates and remain unset until a performance is recorded."
  [goal m records as-of]
  (let [zone (zone-of goal)
        {:keys [now today midnight]} (accounting-clock goal as-of)
        {:keys [start end through status completed-days remaining-days total-days]
         :as w} (window goal today)
        from (day-start start zone)
        to (if end (min-inst (day-start (plus-days end 1) zone) now) now)
        completed-to (min-inst to midnight)
        records (scoped-records goal m records)
        daily (if (.isBefore from to)
                (daily-values m records zone from to now) (sorted-map))
        completed-daily (if (.isBefore from completed-to)
                          (daily-values m records zone from completed-to midnight)
                          (sorted-map))
        best? (= :best (:aggregation m))
        target (double (:goal/target goal))
        logged (observed-amount daily best?)
        completed-logged (observed-amount completed-daily best?)
        amount (or logged 0.0)
        completed-amount (or completed-logged 0.0)
        reached? (>= amount target)
        paced? (and (not best?) (not= :open-ended (:goal/timing goal)))
        average (when (and (not best?) (pos? completed-days))
                  (/ completed-amount completed-days))
        required (when (and paced? (= :active status) (not reached?)
                            (pos? (or remaining-days 0)))
                   (/ (max 0.0 (- target completed-amount)) remaining-days))
        step (threshold-step goal m)
        series (progress-series daily start through (or best? (nil? logged)))
        partial-today? (and (not (.isBefore today start))
                            (or (nil? end) (not (.isAfter today end)))
                            (.isAfter now midnight))]
    {:window w
     :daily daily
     :logged logged
     :completed-logged completed-logged
     :today-logged (get daily today)
     :target target
     :remaining (max 0.0 (- target amount))
     :progress (when (some? logged) (/ amount target))
     :reached? reached?
     :average average
     :required required
     :ratio (when (and required average (pos? average)) (/ required average))
     :pace-days (when (and paced? total-days (pos? completed-days))
                  (- (* (/ completed-amount target) total-days) completed-days))
     :even-pace (when (and paced? total-days) (/ completed-days total-days))
     :next (when-not reached?
             (min target (* step (inc (Math/floor (/ (+ amount 1e-9) step))))))
     :series (cond-> series
               partial-today? (conj [(.toLocalDateTime (.atZone now zone)) logged]))}))

(defn recent-activity
  "Observed total or maximum over `days` calendar days including today.
   At a midnight snapshot, the window ends yesterday. Days before starts-on
   do not count. Best performances never add."
  [goal m records as-of days]
  (let [zone (zone-of goal)
        {:keys [now observed-through]} (accounting-clock goal as-of)
        from (max-date (:goal/starts-on goal) (plus-days observed-through (- 1 days)))
        daily (if (.isBefore (day-start from zone) now)
                (daily-values m (scoped-records goal m records) zone
                              (day-start from zone) now now)
                {})]
    {:from from
     :through observed-through
     :amount (if (= :best (:aggregation m))
               (some->> (vals daily) seq (apply max))
               (reduce + 0 (vals daily)))
     :active-days (count (filter pos? (vals daily)))}))

(defn history
  "Daily presence over `days` calendar days including today, as of one instant.
   Date-only midnight snapshots end yesterday. Includes activity before the
   goal started; missing recorded values remain nil."
  [goal m records as-of days]
  (let [zone (zone-of goal)
        {:keys [now observed-through]} (accounting-clock goal as-of)
        from (plus-days observed-through (- 1 days))
        daily (daily-values m (scoped-records goal m records) zone
                            (day-start from zone) now now)]
    (mapv (fn [d] {:date d :value (get daily d)})
          (dates from observed-through))))

;; ---------------------------------------------------------------------------
;; Book completion
;; ---------------------------------------------------------------------------

(def position-measures
  "Book position measures: [log start key, log end key, book total key]."
  {:pages    [:reading-log/start-page :reading-log/end-page :book/total-pages]
   :chapters [:reading-log/start-chapter :reading-log/end-chapter
              :book/total-chapters]
   :audio    [:reading-log/start-audio-position-seconds
              :reading-log/end-audio-position-seconds
              :book/audiobook-duration-seconds]})

(defn position-series
  "Recorded ending positions of one measure across `logs` (already ordered).

   Only logs recording the measure produce points; nothing is inferred from
   other measures or elapsed time. Adjacent measured logs join with a
   `:solid` segment, and a `:dotted` one when logs without the measure fall
   between them. `:latest` is the last recorded position — not the maximum,
   so a correction or reread that moves backward is kept."
  [logs end-key total zone]
  (loop [[log & more] logs
         prev         nil
         gap?         false
         points       []
         segments     []]
    (if-not log
      (let [latest (peek points)]
        {:points   points
         :segments segments
         :latest   latest
         :total    total
         :percent  (when (and latest (number? total) (pos? total))
                     (/ (double (:value latest)) total))})
      (let [v (get log end-key)]
        (if (nil? v)
          (recur more prev (boolean prev) points segments)
          (let [pt {:at     (:reading-log/end log)
                    :date   (local-date (:reading-log/end log) zone)
                    :value  v
                    :log-id (:xt/id log)}]
            (recur more pt false (conj points pt)
                   (cond-> segments
                     prev (conj {:from prev, :to pt,
                                 :style (if gap? :dotted :solid)})))))))))

(defn book-progress
  "Completion and position histories for a book-completion goal.

   Eligible logs are the book's completed logs whose end falls from the goal's
   start through the cutoff — deliberately past any deadline, so a late
   finish stays visible. The first eligible log marked finished completes the
   goal on its end date; reaching a book total never does, and removing the
   flag later un-completes it."
  [goal book logs as-of]
  (let [zone     (zone-of goal)
        from     (day-start (:goal/starts-on goal) zone)
        {:keys [now today]} (accounting-clock goal as-of)
        cutoff now
        eligible (->> logs
                      (filter (fn [{:reading-log/keys [end]}]
                                (and end
                                     (not (.isBefore ^Instant end from))
                                     (not (.isAfter ^Instant end cutoff)))))
                      (sort-by (juxt :reading-log/end (comp str :xt/id)))
                      vec)
        finished (first (filter #(true? (:reading-log/finished? %)) eligible))
        ends-on  (:goal/ends-on goal)
        done-on  (some-> finished :reading-log/end (local-date zone))
        w        (window goal today)]
    {:window     w
     :logs       eligible
     :completion (when finished
                   {:date   done-on
                    :log-id (:xt/id finished)
                    :late?  (boolean (and ends-on (.isAfter ^LocalDate done-on ends-on)))})
     :overdue?   (boolean (and (nil? finished) ends-on
                               (.isAfter ^LocalDate today ends-on)))
     :time-spent (reduce + 0 (vals (daily-seconds
                                    (keep #(interval {:at  (:reading-log/beginning %)
                                                      :end (:reading-log/end %)})
                                          eligible)
                                    zone from cutoff)))
     :measures   (into {}
                       (for [[k [_ end-key total-key]] position-measures]
                         [k (position-series eligible end-key
                                             (get book total-key) zone)]))}))

;; ---------------------------------------------------------------------------
;; Prior-year comparison
;; ---------------------------------------------------------------------------

(defn- same-month-day
  "The same month and day `years` earlier; Feb 29 becomes Feb 28."
  ^LocalDate [^LocalDate d years]
  (let [y  (- (.getYear d) years)
        ym (YearMonth/of (int y) (.getMonthValue d))]
    (.atDay ym (min (.getDayOfMonth d) (.lengthOfMonth ym)))))

(defn comparison-window
  "The prior-year period matching the completed part of window `w`, or nil
   when none exists.

   Dated and best goals use matching month/day boundaries. Weekly goals align
   with the same ISO week number in the ISO week-year `years` back, covering
   the same weekdays; a week 53 with no counterpart has no comparison.
   Open-ended goals have none."
  [goal {:keys [start through completed-days]} years]
  (when (pos? (or completed-days 0))
    (case (:goal/timing goal)
      :open-ended nil
      :weekly
      (let [week   (.get ^LocalDate start IsoFields/WEEK_OF_WEEK_BASED_YEAR)
            year   (- (.get ^LocalDate start IsoFields/WEEK_BASED_YEAR) years)
            jan4   (LocalDate/of (int year) 1 4)
            weeks  (.getMaximum (.rangeRefinedBy IsoFields/WEEK_OF_WEEK_BASED_YEAR jan4))
            offset (.getValue (.getDayOfWeek ^LocalDate start))]
        (when (<= week weeks)
          (let [monday (-> jan4
                           (.with IsoFields/WEEK_OF_WEEK_BASED_YEAR (long week))
                           (.with (TemporalAdjusters/previousOrSame DayOfWeek/MONDAY)))
                s      (plus-days monday (dec offset))]
            {:start s, :through (plus-days s (dec completed-days))})))
      {:start   (same-month-day start years)
       :through (same-month-day through years)})))

(defn covered?
  "True when some known-complete coverage range for the source spans the whole
   window. Coverage is explicit metadata; an empty range of records never
   establishes it."
  [coverage {:keys [start through]}]
  (boolean (some (fn [{cs :start ce :end}]
                   (and (not (.isAfter ^LocalDate cs start))
                        (not (.isBefore ^LocalDate ce through))))
                 coverage)))

(defn comparison
  "This period against the matching period `years` back.

   Returns `{:status :unknown}` unless `coverage` establishes both the current and prior
   window, so missing history is never shown as zero activity."
  [goal m records current-amount w years coverage]
  (let [cw (comparison-window goal w years)]
    (if-not (and cw (covered? coverage w) (covered? coverage cw))
      {:status :unknown, :window cw}
      (let [zone  (zone-of goal)
            daily (daily-values m (scoped-records goal m records) zone
                                (day-start (:start cw) zone)
                                (day-start (plus-days (:through cw) 1) zone))
            prior (if (= :best (:aggregation m))
                    (some->> (vals daily) seq (apply max))
                    (reduce + 0 (vals daily)))]
        {:status  :known
         :window  cw
         :amount  prior
         :percent (when (and prior (pos? prior) current-amount)
                    (* 100.0 (- (/ current-amount prior) 1)))}))))
