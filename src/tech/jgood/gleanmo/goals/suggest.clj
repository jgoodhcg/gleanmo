(ns tech.jgood.gleanmo.goals.suggest
  "Suggested goal targets from the user's own history.

   Pure, like `goals/calc.clj`: the caller reads records for `history-window`
   and passes them in. Values use the same daily calculation as goal
   progress, so a suggestion counts history exactly as the goal will count
   its own period. Only completed days are read.

   - Dated totals: the recent daily rate over the goal's length, plus
     `total-stretch`.
   - Weekly totals: the `weekly-percentile` week since activity began.
   - Best performances: the recent best, plus `best-stretch`.

   Open-ended and book-completion goals have no suggestion."
  (:require
   [tech.jgood.gleanmo.goals.calc :as calc])
  (:import
   [java.time DayOfWeek LocalDate]
   [java.time.temporal TemporalAdjusters]))

(def history-days
  "Days of history a dated or best suggestion reads."
  365)

(def history-weeks
  "Whole weeks of history a weekly suggestion reads."
  52)

(def total-stretch 1.15)
(def best-stretch 1.05)
(def weekly-percentile 0.6)

(def min-history-days
  "A dated suggestion divides by at least this many days, so a few recent
   logs do not project to an extreme yearly rate."
  28)

(defn- monday-of ^LocalDate [^LocalDate d]
  (.with d (TemporalAdjusters/previousOrSame DayOfWeek/MONDAY)))

(defn- kind
  [goal m]
  (let [timing (:goal/timing goal)]
    (cond
      (= :completion (:aggregation m))                      nil
      (= :best (:aggregation m))                            :best
      (= :weekly timing)                                    :weekly
      (and (= :total (:aggregation m)) (= :dated timing))   :dated)))

(defn supported?
  "True when the goal's measurement and timing have a suggestion."
  [goal m]
  (some? (kind goal m)))

(defn history-window
  "`{:since :until}` instants of the history a suggestion reads, ending at
   the start of today (weekly: the start of this week) in the goal's zone."
  [goal as-of]
  (let [zone      (calc/zone-of goal)
        {:keys [today]} (calc/accounting-clock goal as-of)
        weekly?   (= :weekly (:goal/timing goal))
        until     (if weekly? (monday-of today) today)
        since     (calc/plus-days until (- (if weekly?
                                             (* 7 history-weeks)
                                             history-days)))]
    {:since-date since
     :until-date until
     :since      (calc/day-start since zone)
     :until      (calc/day-start until zone)}))

(defn- round-display
  "`x` in display units rounded to two significant figures, `:up` or
   `:nearest`. Integer units round to at least whole numbers."
  [x integer? mode]
  (when (pos? x)
    (let [p (Math/pow 10 (- (Math/floor (Math/log10 x)) 1))
          p (if integer? (max 1.0 p) p)
          r (* p ((if (= :up mode) #(Math/ceil (- % 1e-9)) #(Math/round (double %)))
                  (/ x p)))]
      ;; Remove floating noise such as 7.300000000000001.
      (when (pos? r)
        (Double/parseDouble (format "%.6g" r))))))

(defn- dated
  [goal {:keys [since-date until-date]} daily]
  (when-let [first-date (first (keys daily))]
    (let [span   (calc/days-between (if (.isAfter ^LocalDate first-date since-date)
                                      first-date
                                      since-date)
                                    until-date)
          start  (:goal/starts-on goal)
          end    (:goal/ends-on goal)
          period (if (and start end (not (.isBefore ^LocalDate end start)))
                   (inc (calc/days-between start end))
                   history-days)
          amount (reduce + 0 (vals daily))]
      (when (pos? amount)
        (cond-> {:kind        :dated
                 :raw         (* (/ amount (max span min-history-days)) period total-stretch)
                 :amount      amount
                 :days        (max span min-history-days)
                 :period-days period}
          ;; With no end date yet, the target assumes a one-year goal; the
          ;; editor fills that end date when the suggestion is used, so the
          ;; target and the goal's length stay matched.
          (and start (nil? end))
          (assoc :assumed-ends-on (calc/plus-days start (dec history-days))))))))

(defn- weekly
  [{:keys [until-date]} daily]
  (when-let [first-date (first (keys daily))]
    (let [weeks  (->> (iterate #(calc/plus-days % 7) (monday-of first-date))
                      (take-while #(.isBefore ^LocalDate % until-date)))
          totals (reduce (fn [acc [d v]] (update acc (monday-of d) + v))
                         (zipmap weeks (repeat 0.0))
                         daily)
          sorted (vec (sort (vals totals)))
          n      (count sorted)]
      (when (pos? n)
        (let [p (nth sorted (dec (long (Math/ceil (* weekly-percentile n)))))]
          (when (pos? p)
            {:kind   :weekly
             :raw    p
             :weeks  n
             :totals sorted}))))))

(defn- best
  [daily]
  (when-let [b (some->> (vals daily) seq (apply max))]
    {:kind   :best
     :raw    (* b best-stretch)
     :amount b
     :days   history-days}))

(defn suggestion
  "A suggested target for `goal` from `records` (history-window records of
   the goal's source), or nil when the goal has none or history is too short.

   `:target` is canonical (seconds, count, or kg) and `:display` is the same
   value in the editor's input unit. Weekly suggestions carry `:hits`, the
   weeks that reached the target, out of `:weeks`. Dated suggestions for a
   goal without an end date carry `:assumed-ends-on`, the end of the
   one-year goal the target assumes."
  [goal m records as-of]
  (when-let [k (kind goal m)]
    (let [zone    (calc/zone-of goal)
          w       (history-window goal as-of)
          daily   (calc/daily-values m (calc/scoped-records goal m records)
                                     zone (:since w) (:until w))
          factor  (double (get-in m [:input-unit :factor] 1))
          integer (and (:integer? m) (== 1.0 factor))
          s       (case k
                    :dated  (dated goal w daily)
                    :weekly (weekly w daily)
                    :best   (best daily))]
      (when-let [display (some-> (:raw s)
                                 (/ factor)
                                 (round-display integer (if (= :best k) :up :nearest)))]
        (let [target (* display factor)]
          (cond-> (-> s
                      (dissoc :raw :totals)
                      (assoc :target target, :display display))
            (= :weekly k)
            (assoc :hits (count (filter #(>= % (- target 1e-9)) (:totals s))))))))))
