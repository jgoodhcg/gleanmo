(ns tech.jgood.gleanmo.goals.calc
  "Pure goal-progress calculations over minimal source records.

   Nothing here reads the database. `tech.jgood.gleanmo.goals.dashboard`
   gathers records (maps of `:at`, `:end`, `:open?`, `:relations`, and the
   measured attributes) and these functions turn them into totals, rates,
   chart series, and states. Semantics follow roadmap/081-goals-dashboard.md
   and the version 08–10 data contracts in
   mockups/2026-09-motivating-dashboards/codex/.

   Totals include eligible records through one captured request instant.
   Pace calculations retain the elapsed-day cutoff in each goal's zone.
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
   Returns the current instant, local date, elapsed-day cutoff, and last
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

   `:through` is the last elapsed day counted, `:elapsed-days` how many
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
        elapsed   (max 0 (inc (days-between start through)))
        total     (when end (inc (days-between start end)))]
    {:start          start
     :end            end
     :through        through
     :partial?       partial?
     :elapsed-days   elapsed
     :total-days     total
     :remaining-days (when total (- total elapsed))
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
  "`[[date value] ...]` at day boundaries: the value after each elapsed day,
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

(def recent-pace-days
  "Days in the recent-pace projection window, today included. 14 is a chosen
   default (roadmap/090-goal-live-today.md), not a derived value."
  14)

(defn- projection
  "Where `rate` per day, held from tomorrow through `end`, takes `amount`.
   `:reaches-on` is the day the target would be reached, when that happens
   within the period and the target is not already reached."
  [amount rate target ^LocalDate today ^LocalDate end]
  (when (and rate end)
    (let [days  (max 0 (days-between today end))
          total (+ amount (* rate days))
          short (- target amount)]
      {:rate       rate
       :total      total
       :progress   (/ total target)
       :reaches-on (when (and (pos? short) (pos? rate))
                     (let [on (plus-days today (long (Math/ceil (/ short rate))))]
                       (when-not (.isAfter on end) on)))})))

(defn numeric-progress
  "Progress through as-of, an Instant or a date-only midnight snapshot.
   Totals and charts include today's eligible activity. Pace uses records
   completed by local midnight, attributed only to elapsed days.
   Required pace and even-pace difference use that same elapsed-day amount.
   Logged data is authoritative; days without logs contribute zero.
   Best goals have no rates and remain unset until a performance is recorded.

   Live values sit beside that baseline so a log made today moves them
   (roadmap/090-goal-live-today.md). `:today-amount` is what today added to
   the total. The live average counts today as a whole day, so it only rises
   as the day goes on. `:needed-today` is the required rate at day start less
   today's amount; negative means ahead. Only dated goals have a required
   rate, even pace, or projection: a weekly goal resets on Monday with no
   carry-forward, so a catch-up rate cannot be acted on."
  [goal m records as-of]
  (let [zone (zone-of goal)
        {:keys [now today midnight]} (accounting-clock goal as-of)
        {:keys [start end through status elapsed-days remaining-days total-days]
         :as w} (window goal today)
        from (day-start start zone)
        to (if end (min-inst (day-start (plus-days end 1) zone) now) now)
        elapsed-to (min-inst to midnight)
        records (scoped-records goal m records)
        daily (if (.isBefore from to)
                (daily-values m records zone from to now) (sorted-map))
        elapsed-daily (if (.isBefore from elapsed-to)
                        (daily-values m records zone from elapsed-to midnight)
                        (sorted-map))
        best? (= :best (:aggregation m))
        target (double (:goal/target goal))
        logged (observed-amount daily best?)
        elapsed-logged (observed-amount elapsed-daily best?)
        amount (or logged 0.0)
        elapsed-amount (or elapsed-logged 0.0)
        reached? (>= amount target)
        timing (:goal/timing goal)
        paced? (and (not best?) (= :dated timing))
        active? (= :active status)
        average (when (and (not best?) (pos? elapsed-days))
                  (/ elapsed-amount elapsed-days))
        required (when (and paced? active? (not reached?)
                            (pos? (or remaining-days 0)))
                   (/ (max 0.0 (- target elapsed-amount)) remaining-days))
        today-amount (when (and (not best?) active?)
                       (max 0.0 (- amount elapsed-amount)))
        live-average (when (and today-amount (not= :weekly timing))
                       (/ amount (inc elapsed-days)))
        required-after (when (and required (> remaining-days 1))
                         (/ (- target amount) (dec remaining-days)))
        recent-average (when (and paced? active? (not reached?)
                                  (>= elapsed-days recent-pace-days))
                         (/ (reduce + 0.0
                                    (vals (subseq daily
                                                  >= (plus-days today (- 1 recent-pace-days))
                                                  <= today)))
                            recent-pace-days))
        even-rate (when (and paced? total-days) (/ target total-days))
        step (threshold-step goal m)
        series (progress-series daily start through (or best? (nil? logged)))
        partial-today? (and (not (.isBefore today start))
                            (or (nil? end) (not (.isAfter today end)))
                            (.isAfter now midnight))]
    {:window w
     :daily daily
     :logged logged
     :elapsed-logged elapsed-logged
     :today-logged (get daily today)
     :target target
     :remaining (max 0.0 (- target amount))
     :progress (when (some? logged) (/ amount target))
     :reached? reached?
     :average average
     :required required
     :today-amount today-amount
     :live-average live-average
     :above-average? (when (and today-amount average) (> today-amount average))
     :needed-today (when required (- required today-amount))
     :required-after-today required-after
     :required-change (when required-after (- required required-after))
     :projection (when (and paced? active? live-average (not reached?))
                   (projection amount live-average target today end))
     :recent-projection (when recent-average
                          (projection amount recent-average target today end))
     :ratio (when (and required average (pos? average)) (/ required average))
     :pace-days (when (and paced? total-days (pos? elapsed-days))
                  (- (* (/ elapsed-amount target) total-days) elapsed-days))
     :even-pace (when (and paced? total-days) (/ elapsed-days total-days))
     :even-rate even-rate
     :even-tonight (when (and even-rate active?) (* even-rate (inc elapsed-days)))
     :gap-at-day-start (when (and even-rate active?)
                         (- elapsed-amount (* even-rate elapsed-days)))
     :next (when-not reached?
             (min target (* step (inc (Math/floor (/ (+ amount 1e-9) step))))))
     :series (cond-> series
               partial-today? (conj [(.toLocalDateTime (.atZone now zone)) logged]))}))

;; ---------------------------------------------------------------------------
;; Ruler (roadmap/090-goal-live-today.md, design direction 2026-10-06)
;; ---------------------------------------------------------------------------

(defn- ruler-unit
  "Canonical units per ruler unit, and whether the ruler steps in whole units.
   Duration totals measure in minutes; counts never step by fractions."
  [m]
  (if (and (= :seconds (:unit m)) (= :total (:aggregation m)))
    [60.0 true]
    [1.0 (boolean (:integer? m))]))

(defn ruler
  "A zoomed scale around a dated total's current amount, in units of one day
   of the pace needed (`D`), or nil when there is no pace to measure by.

   The window runs from a day of pace before this morning's total to 2.5 days
   past the current one, pulling in the next round number and even pace
   tonight when they are within 4 days and padding them by 0.3 of a day; it
   never extends past the target by more than that pad. Logging therefore
   slides and widens it, and it always contains the current total.

   Marks, sorted by value: `:keep` (hold steady), `:even` (back on plan:
   even pace tonight), `:round` (the next multiple of a nice step near 3 days of pace,
   dropped below the target or within a quarter day of a pace mark), and
   `:target` when in the window. Pace marks round up to an amount that can
   be logged; `:distance` is measured from that shown value. `:side` is
   `:below` or `:above` for a mark outside the window.

   Every value is in the measurement's canonical units."
  [{:keys [logged elapsed-logged required target even-tonight]} m]
  (when (and required (pos? required) even-tonight)
    (let [[factor whole?] (ruler-unit m)
          u        #(/ (double %) factor)
          whole    #(if whole? (max 1.0 (double (Math/round (double %)))) %)
          up       #(if whole? (Math/ceil (- % 1e-9)) (/ (Math/ceil (- (* % 10) 1e-9)) 10))
          before   (u (or elapsed-logged 0.0))
          logged   (u (or logged 0.0))
          target   (u target)
          d        (u required)
          keep     (+ before d)
          even     (u even-tonight)
          major    (whole (nice-step (* 3 d)))
          next-maj (* major (inc (Math/floor (/ (+ logged 1e-9) major))))
          near?    #(<= % (* 4 d))
          hi       (cond-> (+ logged (* 2.5 d))
                     (near? (- next-maj logged)) (max (+ next-maj (* 0.3 d)))
                     (near? (Math/abs (- even logged))) (max (+ even (* 0.3 d))))
          hi       (min hi (+ target (* 0.3 d)))
          lo       (cond-> (max 0.0 (- before d))
                     (near? (Math/abs (- even logged)))
                     (-> (min (- even (* 0.3 d))) (max 0.0)))
          minor    (whole (nice-step (/ (- hi lo) 8)))
          clash?   #(or (< (Math/abs (- % keep)) (* 0.25 d))
                        (< (Math/abs (- % even)) (* 0.25 d)))
          mark     (fn [kind raw shown]
                     {:kind     kind
                      :value    (* factor shown)
                      :distance (* factor (- shown logged))
                      :side     (cond (< raw lo) :below (> raw hi) :above)})
          canon    #(* factor %)]
      {:lo         (canon lo)
       :hi         (canon hi)
       :major      (canon major)
       :minor      (canon minor)
       :ticks      (mapv canon (take-while #(<= % (+ hi 1e-9))
                                           (iterate #(+ % minor)
                                                    (* minor (Math/ceil (/ lo minor))))))
       :marks      (->> [(mark :keep keep (up keep))
                         (mark :even even (up even))
                         (when (and (< next-maj target) (not (clash? next-maj)))
                           (mark :round next-maj next-maj))
                         (when (<= target hi) (mark :target target target))]
                        (remove nil?)
                        (sort-by :value)
                        vec)})))

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

(defn recent-weeks
  "Totals of the last `n` complete Monday–Sunday weeks before the current
   one, oldest first, from `history` cells (`{:date :value}`). Days before
   the goal starts do not count; a week entirely before it is nil."
  [goal history n]
  (let [starts-on (:goal/starts-on goal)
        last-day  (:date (peek (vec history)))
        monday    (.with ^LocalDate last-day
                         (TemporalAdjusters/previousOrSame DayOfWeek/MONDAY))
        by-date   (into {} (map (juxt :date :value)) history)]
    (vec (for [k (range n 0 -1)
               :let [from (plus-days monday (* -7 k))
                     to   (plus-days from 6)]]
           (when-not (.isBefore to starts-on)
             (reduce + 0 (keep #(when-not (.isBefore ^LocalDate % starts-on) (get by-date %))
                               (dates from to))))))))

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
  [goal {:keys [start through elapsed-days]} years]
  (when (pos? (or elapsed-days 0))
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
            {:start s, :through (plus-days s (dec elapsed-days))})))
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
