(ns tech.jgood.gleanmo.app.goals
  "Goals: a list grouped by family (cumulative dated, periodic, other) and a
   page per goal. Cumulative dated goals get the ruler page
   (roadmap/090-goal-live-today.md); other kinds keep the detail card.

   Totals include today through one captured request instant. Rates use
   elapsed days in each goal's saved zone. Mutations here
   (book chart preferences, pin, archive, delete) write and 303 back, so the
   page always renders from a fresh snapshot."
  (:require
   [cheshire.core :as json]
   [clojure.string :as str]
   [com.biffweb :as biff]
   [tech.jgood.gleanmo.app.deferred :as deferred]
   [tech.jgood.gleanmo.app.goal-editor :as editor]
   [tech.jgood.gleanmo.app.layout :as layout]
   [tech.jgood.gleanmo.app.shared :as shared]
   [tech.jgood.gleanmo.crud.routes :as crud]
   [tech.jgood.gleanmo.db.mutations :as mutations]
   [tech.jgood.gleanmo.db.queries :as queries]
   [tech.jgood.gleanmo.duration :as duration]
   [tech.jgood.gleanmo.goals.calc :as calc]
   [tech.jgood.gleanmo.goals.dashboard :as dashboard]
   [tech.jgood.gleanmo.goals.registry :as registry]
   [tech.jgood.gleanmo.schema :refer [schema]]
   [tech.jgood.gleanmo.ui :as ui])
  (:import
   [java.time Instant LocalDate]
   [java.time.format DateTimeFormatter]
   [java.util Locale]))

(def crud-routes
  (crud/gen-routes {:entity-key :goal,
                    :entity-str "goal",
                    :plural-str "goals",
                    :schema     schema}))

;; ---------------------------------------------------------------------------
;; Formatting
;; ---------------------------------------------------------------------------

(def ^:private cyan "#06b6d4")
(def ^:private violet "#a78bfa")
(def ^:private muted "#929da9")
(def ^:private grid-line "#242b33")
(def ^:private green "#39cf83")

(defn- fmt-date
  [^LocalDate d pattern]
  (when d (.format d (DateTimeFormatter/ofPattern pattern Locale/US))))

(defn- short-date [d] (fmt-date d "MMM d"))
(defn- long-date [d] (fmt-date d "MMMM d, yyyy"))

(defn- date-span
  "Compact range with explicit years across year boundaries or an open end."
  [^LocalDate start ^LocalDate end]
  (let [pattern (if (and start end (= (.getYear start) (.getYear end)))
                  "MMM d" "MMM d, yyyy")]
    (str (fmt-date start pattern) " – " (if end (fmt-date end pattern) "today"))))

(defn- fmt-num
  "At most one decimal place, grouped thousands, no trailing .0."
  [x]
  (when (some? x)
    (let [r (/ (Math/round (* 10.0 (double x))) 10.0)]
      (if (== r (Math/rint r))
        (format "%,d" (long r))
        (format "%,.1f" r)))))

(defn- fmt-small
  "Two decimals below one, so small daily rates don't read as zero."
  [x]
  (if (and (some? x) (< (Math/abs (double x)) 1) (not (zero? x)))
    (format "%.2f" (double x))
    (fmt-num x)))

(defn- duration-total? [m]
  (and (= :seconds (:unit m)) (= :total (:aggregation m))))

(defn- unit-word
  [m v]
  (let [w (get registry/unit-labels (:unit m) "")]
    (if (and (some? v) (== 1 (double v)) (str/ends-with? w "s"))
      (subs w 0 (dec (count w)))
      w)))

(defn amount
  "A canonical value of measurement `m` in words."
  [m v]
  (cond
    (nil? v)            "—"
    (duration-total? m) (let [h (/ (double v) 3600)]
                          (if (< h 1)
                            (str (fmt-num (/ (double v) 60)) " min")
                            (str (fmt-num h) " h")))
    (= :seconds (:unit m)) (str (fmt-num v) " s")
    (= :kg (:unit m))   (str (fmt-num v) " kg")
    :else               (str (fmt-num v) " " (unit-word m v))))

(defn- rate
  [m per-day]
  (cond
    (nil? per-day)      "—"
    (duration-total? m) (str (fmt-small (/ (double per-day) 60)) " min/day")
    :else               (str (fmt-small per-day) " "
                             (get registry/unit-labels (:unit m)) "/day")))

(defn- rate-change
  "A signed change in a daily rate, e.g. \" (+0.6)\" or \" (−1.2)\", in the
   units `rate` shows; nil when it rounds to zero."
  [m delta]
  (let [v   (if (duration-total? m) (/ (double delta) 60) (double delta))
        txt (fmt-small (Math/abs v))]
    (when-not (re-matches #"[0.]+" txt)
      (str " (" (if (neg? v) "−" "+") txt ")"))))

(defn- source-line
  [{:keys [measurement scope]}]
  (str (get-in registry/sources [(:source measurement) :label]) " · "
       (cond
         (seq scope)             (str/join ", " scope)
         (:relation measurement) (str "All " (str/lower-case
                                              (get-in measurement [:relation :label])))
         :else                   (:label measurement))))

(defn- timing-text
  [goal]
  (case (:goal/timing goal)
    :open-ended (str "No deadline · since " (short-date (:goal/starts-on goal)))
    :weekly     (str "Weekly · resets Monday"
                     (when-let [e (:goal/ends-on goal)] (str " · until " (short-date e))))
    (date-span (:goal/starts-on goal) (:goal/ends-on goal))))

(defn- pinned? [goal] (true? (:goal/pinned goal)))

(defn goal-href
  "The goal's own page."
  [goal]
  (str "/app/goal/" (:xt/id goal)))

;; ---------------------------------------------------------------------------
;; Goal families (roadmap/090-goal-live-today.md, design direction 2026-10-06)
;; ---------------------------------------------------------------------------

(defn- cumulative?
  "A dated total: extra today covers a missed day, so it gets the ruler."
  [{:keys [goal measurement book-progress]}]
  (and (nil? book-progress)
       (= :dated (:goal/timing goal))
       (= :total (:aggregation measurement))))

(defn- periodic?
  "A weekly total: each week stands alone."
  [{:keys [goal book-progress]}]
  (and (nil? book-progress) (= :weekly (:goal/timing goal))))

;; Ruler units: counts as themselves, duration totals in minutes, so a day's
;; pace reads as a small whole number rather than a fraction of an hour.

(defn- ruler-factor [m] (if (duration-total? m) 60.0 1.0))

(defn- qty
  "A canonical value in ruler units with `dp` decimals, or at most one."
  ([m v] (when (some? v) (fmt-num (/ (double v) (ruler-factor m)))))
  ([m v dp]
   (when (some? v)
     (format (str "%,." dp "f") (/ (double v) (ruler-factor m))))))

(defn- qty-unit
  [m]
  (if (duration-total? m) "min" (get registry/unit-labels (:unit m))))

(defn- signed-qty
  "A whole-unit signed amount: −15, +3, or 0."
  [m v]
  (when (some? v)
    (let [s (qty m (Math/abs (double v)) 0)]
      (if (re-matches #"[0,]+" s) "0" (str (if (neg? v) "−" "+") s)))))

(defn- pace-needed-whole
  "The pace needed rounded up to an amount that can be logged."
  [m required]
  (format "%,d" (long (Math/ceil (- (/ (double required) (ruler-factor m)) 1e-9)))))

(def ^:private log-entity
  "Sources logged through their parent's screen."
  {:exercise-line :exercise-session, :boulder-attempt :boulder-session})

(defn- log-href
  "Where Log goes for a measurement: the source's quick-action destination."
  [m]
  (let [entity (get log-entity (:source m) (:source m))]
    (or (some #(when (= entity (:entity %)) (:href %)) shared/quick-action-items)
        "/app/log")))

(defn- data-attrs
  "`<prefix><key>` attributes for the non-nil values of `m`."
  [prefix m]
  (into {}
        (keep (fn [[k v]] (when (some? v) [(keyword (str prefix (name k))) (str v)])))
        m))

(defn- book-state
  [{:keys [book-progress]}]
  (cond
    (get-in book-progress [:completion]) :completed
    (:overdue? book-progress)            :overdue
    :else                                :in-progress))

(defn- chart-measure
  [{:keys [goal]}]
  (or (:goal/progress-measure goal) :pages))

(def ^:private measure-labels {:pages "Pages" :chapters "Chapters" :audio "Audio"})

(defn- position-text
  [measure v]
  (cond
    (nil? v)            "—"
    (= :audio measure)  (duration/format-hms v)
    :else               (fmt-num v)))

;; ---------------------------------------------------------------------------
;; Charts (ECharts option JSON; main.js applies the `gleanmo` formatting hint)
;; ---------------------------------------------------------------------------

(defn- chart-value
  [m v]
  (when (some? v)
    (if (duration-total? m) (/ (double v) 3600) (double v))))

(defn- chart-unit
  [m]
  (cond (duration-total? m) "h"
        (= :seconds (:unit m)) "s"
        :else (get registry/unit-labels (:unit m))))

(def ^:private axis-style
  {:axisLabel {:color muted :fontSize 10 :hideOverlap true}
   :axisLine  {:lineStyle {:color "#30363d"}}
   :axisTick  {:show false}})

(defn- base-chart
  [unit]
  {:gleanmo   {:unit unit}
   :animation false
   :grid      {:left 48 :right 24 :top 36 :bottom 28}
   :tooltip   {:trigger "axis"}
   :legend    {:top 0 :right 0 :itemWidth 16 :itemHeight 2
               :textStyle {:color muted :fontSize 10}}})

(defn- dashed [name color data & {:keys [width type] :or {width 1.5 type "dashed"}}]
  {:name name :type "line" :data data :showSymbol false :silent true
   :lineStyle {:color color :type type :width width} :itemStyle {:color color}})

(defn- date-line
  [date label]
  {:silent true :symbol ["none" "none"]
   :lineStyle {:color "#45515d" :type "dashed"}
   :label {:formatter label :color muted :fontSize 10}
   :data [{:xAxis (str date)}]})

(defn- projection-label
  "Where a projection lands, for its legend entry: the date it reaches the
   target, or the total and share of target at the end of the period."
  [m {:keys [total progress reaches-on]}]
  (if reaches-on
    (str "target " (short-date reaches-on))
    (str (amount m total) " (" (fmt-num (* 100 progress)) "%)")))

(defn- numeric-chart
  [{:keys [goal measurement progress now]} cutoff-date]
  (let [{:keys [window series target required elapsed-logged logged
                projection recent-projection]} progress
        {:keys [start end]} window
        m      measurement
        best?  (= :best (:aggregation m))
        open?  (= :open-ended (:goal/timing goal))
        weekly? (= :weekly (:goal/timing goal))
        paced? (and (not best?) (not open?) (some? (:even-pace progress)))
        x-end  (if (and end (not open?)) (calc/plus-days end 1)
                   (calc/plus-days cutoff-date 1))
        t      (chart-value m target)
        local-now (.toLocalDateTime (.atZone ^Instant now (calc/zone-of goal)))
        now?   (and (not (.isBefore local-now (.atStartOfDay ^LocalDate start)))
                    (.isBefore local-now (.atStartOfDay ^LocalDate x-end)))
        markers (assoc (date-line local-now "Now")
                       :data (cond-> [{:xAxis (str local-now)
                                       :label {:formatter "Now" :position "insideStartTop" :rotate 0}
                                       :lineStyle {:color cyan}}]
                               (and paced? required)
                               (conj {:xAxis (str cutoff-date)
                                      :label {:formatter "Projection start"}})))]
    (-> (base-chart (chart-unit m))
        (assoc :xAxis (merge axis-style {:type "time" :min (str start) :max (str x-end)
                                         :splitLine {:show false}})
               :yAxis (merge axis-style {:type "value" :min 0 :name (chart-unit m)
                                         :nameTextStyle {:color muted :fontSize 10}
                                         :splitLine {:lineStyle {:color grid-line}}})
               :series
               (cond-> [(cond-> {:name "Logged" :type "line" :showSymbol false
                                 :data (mapv (fn [[d v]] [(str d) (chart-value m v)]) series)
                                 :lineStyle {:color cyan :width 2.5}
                                 :itemStyle {:color cyan}}
                          best?       (assoc :step "end")
                          (not best?) (assoc :areaStyle {:color cyan :opacity 0.06})
                          now?        (assoc :markLine markers))]
                 paced? (conj (dashed "Even pace" "#6b7785"
                                      [[(str start) 0] [(str x-end) t]]))
                 (and paced? required)
                 (conj (dashed "Needed from today" violet
                               [[(str cutoff-date) (chart-value m elapsed-logged)] [(str x-end) t]]
                               :width 2))
                 (and now? projection)
                 (conj (dashed (str "At your average · " (projection-label m projection)) cyan
                               [[(str local-now) (chart-value m logged)]
                                [(str x-end) (chart-value m (:total projection))]]
                               :type "dotted" :width 2))
                 (and now? recent-projection)
                 (conj (dashed (str "At last " calc/recent-pace-days " days · "
                                    (projection-label m recent-projection))
                               green
                               [[(str local-now) (chart-value m logged)]
                                [(str x-end) (chart-value m (:total recent-projection))]]
                               :type "dotted" :width 2))
                 (or best? open? weekly?)
                 (conj (dashed "Target" violet [[(str start) t] [(str x-end) t]])))))))

(defn- book-chart
  [{:keys [goal book-progress now]} measure cutoff-date]
  (let [{:keys [measures completion]} book-progress
        {:keys [points segments total]} (get measures measure)
        start   (:goal/starts-on goal)
        ends-on (:goal/ends-on goal)
        tomorrow (calc/plus-days cutoff-date 1)
        x-end   (if ends-on
                  (let [e (calc/plus-days ends-on 1)]
                    (if (.isAfter ^LocalDate tomorrow e) tomorrow e))
                  tomorrow)
        top     (reduce max (or total 1) (map :value points))
        guide?  (and ends-on (:goal/even-pace-enabled goal) total (nil? completion))
        pt      (fn [{:keys [at value]}]
                  [(str (.toLocalDateTime (.atZone ^Instant at (calc/zone-of goal)))) value])
        seg     (fn [{:keys [from to style]}]
                  {:name "Recorded position" :type "line" :showSymbol false :silent true
                   :data [(pt from) (pt to)]
                   :lineStyle (cond-> {:color cyan :width 2}
                                (= :dotted style) (assoc :type [1 5] :cap "round"))
                   :itemStyle {:color cyan}})]
    (-> (base-chart nil)
        (assoc :gleanmo (if (= :audio measure) {:format "hms"} {:unit nil})
               :tooltip {:trigger "item"}
               :xAxis (merge axis-style {:type "time" :min (str start) :max (str x-end)
                                         :splitLine {:show false}})
               :yAxis (merge axis-style {:type "value" :min 0
                                         :max (Math/ceil (* 1.15 top))
                                         :name (if (= :audio measure) "Playback"
                                                   (measure-labels measure))
                                         :nameTextStyle {:color muted :fontSize 10}
                                         :splitLine {:lineStyle {:color grid-line}}})
               :series
               (cond-> (into [(cond-> {:name "Recorded position" :type "scatter"
                                       :symbolSize 8 :data (mapv pt points)
                                       :itemStyle {:color cyan}}
                                total
                                (assoc :markLine {:silent true :symbol ["none" "none"]
                                                  :lineStyle {:color violet :type "dashed"}
                                                  :label {:formatter "Book total"
                                                          :position "insideEndTop"
                                                          :color muted :fontSize 10}
                                                  :data [{:yAxis total}]}))]
                             (map seg segments))
                 guide?
                 (conj (dashed "Even-pace guide" "#77818d"
                               [[(str start) 0] [(str (calc/plus-days ends-on 1)) total]]
                               :type "dotted"))
                 true
                 (conj {:name "marker" :type "line" :data [] :silent true
                        :markLine (if completion
                                    (assoc (date-line (:date completion) "Finished")
                                           :lineStyle {:color green :type "dashed"}
                                           :label {:formatter "Finished" :color green
                                                   :fontSize 10})
                                    (date-line (.toLocalDateTime (.atZone ^Instant now (calc/zone-of goal)))
                                               "Now"))}))
               :legend {:top 0 :right 0 :itemWidth 16 :itemHeight 2
                        :textStyle {:color muted :fontSize 10}
                        :data (cond-> ["Recorded position"] guide? (conj "Even-pace guide"))}))))

(defn- chart-block
  [id cfg label]
  [:div
   [:div {:id              id
          :role            "img"
          :aria-label      label
          :class           "h-56 w-full md:h-64"
          :data-chart-data (str id "-data")}]
   [:div.hidden {:id (str id "-data")} (json/generate-string cfg)]])

;; ---------------------------------------------------------------------------
;; Detail
;; ---------------------------------------------------------------------------

(defn- stat
  [label value note & [cls]]
  [:div
   [:div.text-xs.text-gray-400 label]
   [:div {:class (str "mt-1.5 text-lg font-semibold tabular-nums " (or cls "text-white"))} value]
   (when note [:div.mt-1.text-xs.text-gray-400 note])])

(defn- badge
  [goal]
  [:span {:class (str "rounded border px-1.5 py-0.5 text-xs "
                      (if (= :open-ended (:goal/timing goal))
                        "border-neon-lime text-neon-lime"
                        "border-dark text-gray-400"))}
   (case (:goal/timing goal)
     :open-ended "No deadline"
     :weekly     "Weekly reset"
     "Dated target")])

(defn- pin-form
  "Pin toggle. `from` \"list\" returns to the goals list instead of the goal."
  [goal from & body]
  (biff/form {:action (str (goal-href goal) "/pin") :method "post" :class "inline"}
             [:input {:type "hidden" :name "pinned" :value (str (not (pinned? goal)))}]
             (when from [:input {:type "hidden" :name "from" :value from}])
             (into [:button {:type         "submit"
                             :data-goal-pin true
                             :aria-pressed (str (pinned? goal))
                             :aria-label   (str (if (pinned? goal) "Unpin " "Pin ")
                                                (:goal/label goal))
                             :class        (if (pinned? goal)
                                             "text-neon-cyan hover:text-white"
                                             "text-gray-400 hover:text-neon-cyan")}]
                   body)))

(defn- actions
  [goal]
  [:div {:class "flex flex-wrap justify-end items-center gap-3 text-xs"
         :data-goal-actions true}
   (pin-form goal nil (if (pinned? goal) "★ Pinned" "☆ Pin"))
   [:a.link {:href (str (goal-href goal) "/edit")} "Edit"]
   (biff/form {:action (str (goal-href goal) "/archive") :method "post"
               :class "inline"}
              [:input {:type "hidden" :name "archived" :value "true"}]
              [:button.link {:type "submit"} "Archive"])
   (biff/form {:action (str (goal-href goal) "/delete") :method "post"
               :class "inline"
               :onsubmit "return confirm('Delete this goal? Your logs are not affected.');"}
              [:button {:type "submit" :class "text-gray-400 hover:text-red-400"} "Delete"])])

(defn- window-caption
  [goal {:keys [window reached?]} best?]
  (let [{:keys [start end status partial?]} window]
    (cond
      (= :not-started status) (str "Counting starts " (long-date (:goal/starts-on goal)))
      reached?                "Target reached ✓"
      (= :ended status)       (str "Ended " (long-date (:goal/ends-on goal)))
      (= :open-ended (:goal/timing goal))
      (str "counting since " (long-date start))
      (= :weekly (:goal/timing goal))
      (str (date-span start end) (when partial? " · partial week"))
      :else (str (if best? "best within " "by ") (long-date end)))))

(defn- today-line
  "The live statistic under the headline total: today's amount against the
   required rate (dated), against the average (open-ended), or alone
   (weekly). Numeric data attributes carry the raw values for tests."
  [goal m {:keys [today-amount needed-today required average live-average
                  required-after-today above-average?]}]
  (when today-amount
    (let [ahead? (and needed-today (<= needed-today 0))]
      [:p (merge {:data-goal-live true
                  :class          (str "mt-3 text-sm tabular-nums "
                                       (if ahead? "text-neon-lime" "text-white"))}
                 (data-attrs "data-" {:today-amount   today-amount
                                      :needed-today   needed-today
                                      :average        average
                                      :live-average   live-average
                                      :required-after required-after-today}))
       (str "Today: " (amount m today-amount)
            (cond
              ahead?
              (str " · ✓ " (amount m (- needed-today)) " ahead")
              needed-today
              (str " of " (amount m required) " · " (amount m needed-today)
                   " to stay on pace")
              (and (= :open-ended (:goal/timing goal)) average)
              (str (if above-average? " · above " " · below ")
                   "your average of " (amount m average))))])))

(defn- numeric-summary
  [{:keys [goal measurement progress] :as entry}]
  (let [m      measurement
        {:keys [logged target average required ratio next remaining window
                live-average required-after-today required-change today-amount]} progress
        best?  (= :best (:aggregation m))
        open?  (= :open-ended (:goal/timing goal))
        weekly? (= :weekly (:goal/timing goal))
        {:keys [elapsed-days remaining-days status]} window
        average-note (fn [prefix]
                       (str prefix
                            (when live-average
                              (str " · with today " (rate m live-average)
                                   (when average
                                     (rate-change m (- live-average average)))))))]
    [:div {:class "grid gap-6 p-4 sm:p-6 lg:grid-cols-[1.35fr_2fr]"}
     [:div
      [:p.mt-1.text-xs.text-gray-400
       (str (source-line entry) " · " (if best? "Best performance" (:label m)))]
      [:div {:class "mt-2 text-3xl font-semibold tabular-nums text-white"}
       (if (nil? logged)
         "No record yet"
         (amount m logged))
       [:span {:class "ml-3 text-sm font-normal text-gray-400"}
        (str "of " (amount m target))]]
      [:p.mt-1.text-xs.text-gray-400
       (str (when-let [fraction (:progress progress)]
              (str (fmt-num (* 100 fraction)) "% " (if best? "of target" "recorded") " · "))
            (window-caption goal progress best?))]
      (today-line goal m progress)]
     [:div {:class "grid grid-cols-1 gap-4 border-t border-dark pt-4 sm:grid-cols-3 lg:border-t-0 lg:pt-0 lg:items-center"}
      (cond
        (and open? (not best?))
        [:<>
         (stat "Still to go" (amount m remaining) "No deadline")
         (stat "Average per elapsed day" (rate m average)
               (average-note (str "Since " (short-date (:goal/starts-on goal)) " · "
                                  elapsed-days " elapsed days")))
         (stat "Next milestone" (if next (amount m (- next (or logged 0))) "—")
               (when next (str "to " (amount m next))))]

        (and weekly? (not best?))
        [:<>
         (stat "Still to go this week" (amount m remaining)
               (if (:reached? progress) "Target reached this week" "Resets Monday"))
         (stat "Today" (if today-amount (amount m today-amount) "—")
               (str "This week so far: " (amount m (or logged 0))))
         (stat "Next threshold" (if next (amount m (- next (or logged 0))) "—")
               (when next (str "to " (amount m next))))]

        best?
        [:<>
         (stat "Best so far" (amount m logged) "Within this goal period")
         (stat "To your target" (amount m remaining) "One qualifying exercise line")
         (stat "Next threshold" (if next (amount m (- next (or logged 0))) "—")
               (when next (str "to " (amount m next))))]

        :else
        [:<>
         (stat "Average per elapsed day" (rate m average)
               (average-note (str elapsed-days " elapsed days")))
         (if required
           (stat "Required from today" (rate m required)
                 (str remaining-days " days left"
                      (if required-after-today
                        (str " · after today " (rate m required-after-today)
                             (rate-change m (- required-change)))
                        " · based on elapsed days")
                      (when ratio (format " · %.2f× your average" (double ratio))))
                 (if (and ratio (> ratio 1)) "text-neon-amber" "text-neon-lime"))
           (stat "Required from today" "—"
                 (case status
                   :ended       "The goal period has ended"
                   :not-started "Not started yet"
                   "Target reached")))
         (stat "Next threshold" (if next (amount m (- next (or logged 0))) "—")
               (when next (str "to " (amount m next))))])]]))

(defn- chart-title
  [{:keys [goal measurement progress]}]
  (let [{:keys [start end]} (:window progress)]
    (cond
      (= :best (:aggregation measurement))
      (str "Best performance · " (date-span start end))
      (= :open-ended (:goal/timing goal))
      (str "Cumulative progress · " (date-span start nil) " · no deadline")
      (= :weekly (:goal/timing goal))
      (str "This calendar week · " (date-span start end))
      :else (str "Cumulative progress · " (date-span start end)))))

(defn- chart-readout
  [{:keys [goal measurement]}]
  (cond
    (= :best (:aggregation measurement)) "Best recorded within the goal period"
    (= :open-ended (:goal/timing goal)) "Progress keeps accumulating · no reset or required pace"
    (= :weekly (:goal/timing goal)) "Each week stands on its own · no carry-forward"))

(defn- history-panel
  [{:keys [goal measurement history book-progress]}]
  (let [m      (if book-progress dashboard/reading-duration measurement)
        cells  history
        first* (:date (first cells))
        last*  (:date (last cells))
        mid    (:date (nth cells (quot (count cells) 2) nil))]
    [:section.min-w-0.p-4.sm:p-6 {:data-goal-activity true}
     [:div.mb-3.flex.items-baseline.justify-between.gap-3
      [:div
       [:h3.text-sm.font-semibold.text-white "Keep coming back"]
       [:p.mt-1.text-xs.text-gray-400
        (str (short-date first*) " – " (short-date last*) " · one column per day")]]
      [:span.text-xs.text-gray-400 "12 WEEKS"]]
     [:div {:class "grid gap-px grid-cols-[repeat(84,minmax(0,1fr))]"
            :role "list" :aria-label (str (:goal/label goal) " daily activity")
            :aria-describedby "goal-activity-help"}
      (for [{:keys [date value]} cells]
        (let [txt (str (short-date date) ": "
                       (if (and value (pos? value)) (amount m value) "no log"))]
          [:span {:key (str date) :role "listitem" :title txt :aria-label txt
                  :data-activity-cell true
                  :tabindex (if (= date last*) "0" "-1")
                  :class (str "h-5 cursor-pointer rounded-sm focus:outline-none focus:ring-2 focus:ring-neon-cyan focus:z-10 "
                              (if (and value (pos? value)) "bg-neon-cyan opacity-75" "bg-dark"))}]))]
     [:div.mt-1.flex.justify-between.text-xs.text-gray-500
      [:span (short-date first*)] [:span (short-date mid)] [:span (short-date last*)]]
     [:p.mt-2.text-xs.text-gray-300 {:data-activity-readout true}
      "Click a day to see its activity."]
     [:p.mt-1.text-xs.text-gray-400 {:id "goal-activity-help"}
      "Use Left/Right to move between days, or Home/End to jump to the first/last day."]
     [:p.mt-2.text-xs.text-gray-400
      (if book-progress
        "Reading-session time for this book, in any format."
        "Days with activity in this goal's scope, including days before it started.")]]))

(defn- side-panel-head
  [title subtitle tag]
  [:div.mb-3.flex.items-baseline.justify-between.gap-3
   [:div [:h3.text-sm.font-semibold.text-white title]
    (when subtitle [:p.mt-1.text-xs.text-gray-400 subtitle])]
   [:span.text-xs.text-gray-400 tag]])

(defn- rhythm-panel
  [{:keys [measurement recent]}]
  [:section.min-w-0.p-4.sm:p-6
   (side-panel-head (if (= :best (:aggregation measurement)) "Your recent best" "Your recent rhythm")
                    (str (short-date (:from recent)) " – " (short-date (:through recent)))
                    "LAST 28 DAYS")
   [:div.text-xs.text-gray-400
    (if (= :best (:aggregation measurement)) "Best in the last 28 days" "Recorded in the last 28 days")
    [:strong {:class "my-1 block text-2xl font-semibold tabular-nums text-white"}
     (amount measurement (:amount recent))]
    (str "Across " (:active-days recent) " active days")]
   [:p.mt-3.text-xs.text-gray-400
    (if (= :best (:aggregation measurement))
      "Your strongest recorded performance in this period."
      "Recorded activity in this period. Coverage may be incomplete.")]])

(defn- comparison-panel
  [{:keys [comparison measurement progress]}]
  [:section.min-w-0.p-4.sm:p-6
   (side-panel-head "Against your own ghost" "Same dates · prior year" "PRIOR YEARS")
   (if (= :known (:status comparison))
     (let [pct (:percent comparison)]
       [:div.text-sm
        [:div.flex.items-center.justify-between
         [:span.text-gray-300 (str "vs " (.getYear ^LocalDate (get-in comparison [:window :start])))]
         [:span.tabular-nums (if pct (str (if (neg? pct) "−" "+") (fmt-num (Math/abs pct)) "%") "—")]]
        [:p.mt-2.text-xs.text-gray-400
         (str "Then: " (amount measurement (:amount comparison)) " · now: "
              (amount measurement (:logged progress)))]])
     [:div.text-sm.text-gray-400
      [:strong {:class "my-1 block text-xl font-semibold text-white"} "No comparable history"]
      [:p.text-xs.leading-relaxed
       "Prior-year comparisons need history known to be complete for this source. Until that coverage is recorded, no comparison is shown — missing logs are never treated as zero."]])])

(defn- reading-trail
  [{:keys [goal book-progress]}]
  [:section.min-w-0.p-4.sm:p-6
   (side-panel-head "Recent reading" "Positions as recorded · each measure stays separate"
                    "ALL FORMATS")
   (if-let [logs (seq (take-last 3 (:logs book-progress)))]
     (for [l (reverse logs)]
       [:div {:key (str (:xt/id l))
              :class "grid grid-cols-[4rem_5rem_1fr] gap-2 border-b border-dark py-2 text-xs last:border-b-0"}
        [:span.text-gray-400 (short-date (some-> (:reading-log/end l)
                                                 (calc/local-date (calc/zone-of goal))))]
        [:span (or (some-> (:reading-log/format l) name str/capitalize) "—")]
        [:span
         (str/join " · "
                   (keep (fn [[k label end-key]]
                           (when-let [v (get l end-key)]
                             (str label " " (position-text k v))))
                         [[:pages "Page" :reading-log/end-page]
                          [:chapters "Chapter" :reading-log/end-chapter]
                          [:audio "Audio" :reading-log/end-audio-position-seconds]]))
         (when (true? (:reading-log/finished? l))
           [:span.text-neon-lime " · Finished ✓"])]])
     [:p.text-xs.text-gray-400 "No completed reading logs for this book since the goal started."])
   [:p.mt-3.text-xs.text-gray-400 "Book totals and every log's values are in “What counts” below."]])

(defn- what-counts
  [{:keys [goal measurement scope]} cutoff-date extra]
  [:details {:class "border-t border-dark px-4 sm:px-6"}
   [:summary {:class "cursor-pointer py-2.5 text-xs text-gray-400"} "What counts toward this goal"]
   [:p.mb-3.max-w-prose.text-xs.text-gray-400
    (str (:counts measurement)
         (when (seq scope) (str " Scope: " (str/join ", " scope) "."))
         (case (:goal/timing goal)
           :open-ended (str " Counting starts " (long-date (:goal/starts-on goal))
                            "; earlier activity is excluded. There is no end date and progress never resets.")
           :weekly     " Each Monday–Sunday week has the full target and resets on Monday; surplus never carries forward."
           (str " The goal period is " (long-date (:goal/starts-on goal)) " – "
                (long-date (:goal/ends-on goal)) ", inclusive."))
         " Totals and charts include today's eligible records through this page's refresh in "
         (:goal/time-zone goal) ". Baseline rates use elapsed days through "
         (long-date (calc/plus-days cutoff-date -1))
         ". Live values beside them count today as a whole day."
         " Intervals must have ended by the relevant cutoff before clipping to the goal period."
         " Open timers, future-ending intervals, deleted logs, and hidden logs are excluded.")]
   extra])

(defn- book-values
  [{:keys [goal book book-progress]}]
  (let [zone (calc/zone-of goal)]
    [:div.mb-4.overflow-x-auto
     [:table {:class "w-full min-w-[40rem] text-left text-xs"}
      [:caption.py-2.text-left.text-gray-300
       (str "Book totals: "
            (or (some-> (:book/total-pages book) fmt-num (str " pages")) "pages not set") " · "
            (or (some-> (:book/total-chapters book) fmt-num (str " chapters")) "chapters not set") " · "
            (or (some-> (:book/audiobook-duration-seconds book) duration/format-hms
                        (str " audiobook"))
                "audiobook duration not set"))]
      [:thead.text-gray-400
       [:tr (for [h ["Date" "Format" "Pages · start → end" "Chapters · start → end"
                     "Audio · start → end" "Time spent" "Finished"]]
              [:th.px-2.py-1.font-medium {:key h} h])]]
      [:tbody
       (for [l (:logs book-progress)
             :let [span (fn [k [s e]]
                          (if (or (get l s) (get l e))
                            (str (position-text k (get l s)) " → " (position-text k (get l e)))
                            "—"))]]
         [:tr.border-t.border-dark {:key (str (:xt/id l))}
          [:td.px-2.py-1 (short-date (calc/local-date (:reading-log/end l) zone))]
          [:td.px-2.py-1 (or (some-> (:reading-log/format l) name str/capitalize) "—")]
          [:td.px-2.py-1 (span :pages [:reading-log/start-page :reading-log/end-page])]
          [:td.px-2.py-1 (span :chapters [:reading-log/start-chapter :reading-log/end-chapter])]
          [:td.px-2.py-1 (span :audio [:reading-log/start-audio-position-seconds
                                       :reading-log/end-audio-position-seconds])]
          [:td.px-2.py-1 (amount dashboard/reading-duration
                                 (/ (.toMillis (java.time.Duration/between
                                                (:reading-log/beginning l)
                                                (:reading-log/end l)))
                                    1000.0))]
          [:td.px-2.py-1 (if (true? (:reading-log/finished? l)) "Yes" "—")]])]]]))

(defn- book-controls
  [{:keys [goal book-progress]} measure]
  (let [pref-action (str "/app/goal/" (:xt/id goal) "/preferences")]
    [:div.mb-2.flex.flex-wrap.items-center.gap-3.text-xs.text-gray-400
     [:span "Progress by"]
     (biff/form {:action pref-action :method "post" :class "flex gap-1"
                 :role "group" :aria-label "Book progress measure"
                 :data-goal-measure true}
                (for [[k label] measure-labels]
                  [:button {:type "submit" :name "progress-measure" :value (name k)
                            :aria-pressed (str (= k measure))
                            :class "rounded px-2.5 py-1.5 text-xs text-gray-400 hover:bg-dark hover:text-white aria-pressed:bg-dark aria-pressed:text-neon-cyan"}
                   label]))
     (when (and (= :dated (:goal/timing goal)) (nil? (:completion book-progress)))
       (biff/form {:action pref-action :method "post" :class "ml-auto flex items-center gap-2"}
                  [:input {:type "hidden" :name "even-pace" :value "false"}]
                  [:input {:type "checkbox" :id "book-even-pace" :name "even-pace" :value "true"
                           :checked (true? (:goal/even-pace-enabled goal))
                           :data-original-value (str (true? (:goal/even-pace-enabled goal)))
                           :onchange "this.form.requestSubmit ? this.form.requestSubmit() : this.form.submit()"}]
                  [:label {:for "book-even-pace"} "Even-pace guide"]
                  [:noscript [:button.link {:type "submit"} "Save"]]))]))

(defn- book-summary
  [{:keys [goal book-progress] :as entry} measure]
  (let [{:keys [completion time-spent logs measures]} book-progress
        {:keys [latest total percent]} (get measures measure)
        state (book-state entry)]
    [:div {:class "grid gap-6 p-4 sm:p-6 lg:grid-cols-[1.35fr_2fr]"}
     [:div
      [:p.mt-1.text-xs.text-gray-400 (str (source-line entry) " · Completion")]
      [:div {:class (str "mt-2 text-3xl font-semibold "
                         (case state :completed "text-neon-lime" :overdue "text-neon-amber" "text-white"))}
       (case state :completed "✓ Completed" :overdue "Overdue" "In progress")]
      [:p.mt-1.text-xs.text-gray-400
       (cond
         completion (str "Marked finished on " (long-date (:date completion))
                         (when (:late? completion) " · after the deadline"))
         (= :overdue state) (str "The deadline was " (long-date (:goal/ends-on goal))
                                 " · complete when a reading log is marked finished")
         :else "Complete when a reading log is marked finished")]]
     [:div {:class "grid grid-cols-1 gap-4 border-t border-dark pt-4 sm:grid-cols-3 lg:border-t-0 lg:pt-0 lg:items-center"}
      (stat (str "Latest " (str/lower-case (measure-labels measure)))
            [:span (position-text measure (:value latest))
             (when total [:small.ml-1.text-xs.text-gray-400 (str "/ " (position-text measure total))])]
            (cond
              (nil? latest) "No position recorded"
              percent (str (fmt-num (* 100 percent)) "% of "
                           (if (= :audio measure) "audio duration" (name measure))
                           " · logged " (short-date (:date latest)))
              :else (str "No book total recorded · logged " (short-date (:date latest)))))
      (stat "Time spent" (amount dashboard/reading-duration time-spent)
            (str (count logs) " reading sessions"))
      (stat "Completion" (if completion "Finished" "Not yet")
            (if completion "Confirmed by a reading log" "Position does not mark it finished"))]]))

(defn goal-detail
  "The detail card for goals without a page of their own yet."
  [entry cutoff-date]
  [:section#goal-detail
   {:class "rounded-xl border border-dark bg-dark-surface overflow-hidden"
    :aria-label "Selected goal"}
   [:header {:class "flex items-start justify-between gap-4 px-4 pt-4 sm:px-6 sm:pt-6"}
    [:div.flex.min-w-0.flex-wrap.items-center.gap-3
     [:h2.text-lg.font-semibold.text-white {:class "break-words"} (get-in entry [:goal :goal/label])]
     (badge (:goal entry))]
    (actions (:goal entry))]
   (if (:book-progress entry)
     (let [measure (chart-measure entry)
           {:keys [total]} (get-in entry [:book-progress :measures measure])]
       [:<>
        (book-summary entry measure)
        [:div.px-4.pb-2.sm:px-6
         (book-controls entry measure)
         [:div.mb-1.text-xs.text-gray-400
          (str (measure-labels measure) " position · "
               (date-span (get-in entry [:goal :goal/starts-on])
                          (get-in entry [:goal :goal/ends-on])))]
         (chart-block "goal-chart" (book-chart entry measure cutoff-date)
                      (str (get-in entry [:goal :goal/label]) ": recorded "
                           (str/lower-case (measure-labels measure)) " positions"))
         [:p.text-right.text-xs.text-gray-400
          (if total
            "Dotted links cross logs without this position recorded."
            "No book total recorded, so no total line, percentage, or guide.")]]
        [:div {:class "grid grid-cols-1 border-t border-dark md:grid-cols-[1.25fr_1fr] md:divide-x md:divide-dark-border"}
         (history-panel entry) (reading-trail entry)]
        (what-counts entry cutoff-date (book-values entry))])
     [:<>
      (numeric-summary entry)
      [:div.px-4.pb-2.sm:px-6
       [:div.mb-1.text-xs.text-gray-400 (chart-title entry)]
       (chart-block "goal-chart" (numeric-chart entry cutoff-date)
                    (str (get-in entry [:goal :goal/label]) ": "
                         (amount (:measurement entry) (get-in entry [:progress :logged]))
                         " of " (amount (:measurement entry)
                                        (get-in entry [:progress :target]))))
       [:p.text-right.text-xs.text-gray-400 (chart-readout entry)]]
      [:div {:class "grid grid-cols-1 border-t border-dark md:grid-cols-[1.25fr_1fr] md:divide-x md:divide-dark-border"}
       (history-panel entry)
       (if (= :open-ended (get-in entry [:goal :goal/timing]))
         (rhythm-panel entry)
         (comparison-panel entry))]
      (what-counts entry cutoff-date nil)])])

(defn- archived-section
  [goals]
  (when (seq goals)
    [:details {:class "rounded-xl border border-dark bg-dark-surface px-4 sm:px-6"}
     [:summary {:class "cursor-pointer py-3 text-sm text-gray-400"}
      (str "Archived goals (" (count goals) ")")]
     [:ul.pb-3
      (for [g goals]
        [:li {:key (str (:xt/id g))
              :class "flex items-center justify-between gap-3 border-t border-dark py-2 text-sm"}
         [:span.text-gray-300 (:goal/label g)]
         [:span.flex.items-center.gap-3.text-xs
          [:a.link {:href (str "/app/goal/" (:xt/id g) "/edit")} "Edit"]
          (biff/form {:action (str "/app/goal/" (:xt/id g) "/archive") :method "post"
                      :class "inline"}
                     [:input {:type "hidden" :name "archived" :value "false"}]
                     [:button.link {:type "submit"} "Restore"])]])]]))

;; ---------------------------------------------------------------------------
;; Home screen cards (roadmap/091-pinned-goals.md)
;; ---------------------------------------------------------------------------

(defn- card-figures
  "`[fraction headline status-line status-class]` for one pinned goal, from
   the same progress values and formatting the goals page uses."
  [{:keys [goal measurement progress book-progress] :as entry}]
  (if book-progress
    (let [measure (chart-measure entry)
          {:keys [latest total percent]} (get-in book-progress [:measures measure])]
      (case (book-state entry)
        :completed [1.0 "Finished" "✓ Completed" "text-neon-lime"]
        :overdue   [percent (str (measure-labels measure) " " (position-text measure (:value latest))
                                 (when total (str " / " (position-text measure total))))
                    "Overdue" "text-neon-amber"]
        [percent (str (measure-labels measure) " " (position-text measure (:value latest))
                      (when total (str " / " (position-text measure total))))
         "In progress" "text-gray-400"]))
    (let [m measurement
          {:keys [logged target remaining reached? needed-today today-amount window]} progress
          best?     (= :best (:aggregation m))
          headline  (str (if (nil? logged) "—" (amount m logged)) " of " (amount m target))]
      (into [(:progress progress) headline]
            (cond
              reached?                         ["Target reached ✓" "text-neon-lime"]
              (= :not-started (:status window)) [(str "Starts " (short-date (:start window)))
                                                 "text-gray-400"]
              (= :ended (:status window))      [(str "Ended " (short-date (:goal/ends-on goal)))
                                                "text-gray-400"]
              (and needed-today (<= needed-today 0))
              [(str "✓ " (amount m (- needed-today)) " ahead today") "text-neon-lime"]
              needed-today                     [(str (amount m needed-today) " to stay on pace today")
                                                "text-white"]
              (= :weekly (:goal/timing goal))  [(str (amount m remaining) " to go this week")
                                                "text-white"]
              best?                            [(str (amount m remaining) " to go") "text-white"]
              :else                            [(str "Today: " (amount m (or today-amount 0)))
                                                "text-white"])))))

(defn- pinned-goal-card
  [{:keys [goal] :as entry}]
  (let [[fraction headline status status-class] (card-figures entry)]
    [:a {:key          (str (:xt/id goal))
         :href         (goal-href goal)
         :data-home-goal (str (:xt/id goal))
         :class        "block rounded-lg bg-dark-surface px-4 py-3 no-underline transition-colors hover:bg-dark-light"
         :style        {:border "1px solid #1e2430"}}
     [:div.flex.items-baseline.justify-between.gap-3
      [:div.min-w-0.truncate.text-sm.font-semibold.text-white (:goal/label goal)]
      [:div.shrink-0.text-xs.tabular-nums.text-gray-400 headline]]
     [:div {:class "mt-2 h-1.5 w-full rounded-full bg-dark"}
      (when (some? fraction)
        [:span {:class "block h-full rounded-full bg-neon-cyan"
                :style {:width (str (min 100.0 (* 100.0 (max 0.0 fraction))) "%")}}])]
     [:div {:class (str "mt-2 text-xs tabular-nums " status-class)} status]]))

(defn pinned-goal-cards
  "The home screen's Goals section: one compact card per pinned goal, or
   nothing when no goal is pinned."
  [entries]
  (when (seq entries)
    [:section.space-y-3 {:data-home-goals true :aria-label "Pinned goals"}
     [:div.flex.items-center.gap-3
      [:div.text-xs.font-semibold.uppercase.tracking-wide.text-gray-300 "Goals"]
      [:div.h-px.flex-1.bg-dark-border]
      [:a.text-xs.text-gray-500.no-underline.hover:text-neon-cyan {:href "/app/goals"}
       "All goals"]]
     [:div.grid.grid-cols-1.md:grid-cols-3.gap-3
      (map pinned-goal-card entries)]]))

(defn- skeleton-bar
  [width i]
  [:span {:class (str "block h-3 rounded bg-dark-light animate-pulse " width)
          :style (deferred/pulse-delay i)}])

(defn- visible-pinned-goals
  [{:keys [session biff/db] :as ctx}]
  (let [user-id (:uid session)]
    (dashboard/visible-goals db user-id
                             (remove #(true? (:goal/archived %))
                                     (queries/pinned-goals-for-user db user-id))
                             (queries/resolve-user-settings ctx))))

(defn pinned-goal-entries
  "The home screen's pinned, unarchived goals, computed. With none pinned it
   costs one sparse-flag lookup."
  [{:keys [session biff/db] :as ctx}]
  (let [pinned (visible-pinned-goals ctx)]
    (when (seq pinned)
      (->> (dashboard/dashboard db (:uid session)
                                {:now           (Instant/now)
                                 :user-settings (queries/resolve-user-settings ctx)
                                 :goals         pinned})
           :active
           (sort-by (comp str/lower-case :goal/label :goal))))))

(defn pinned-goals-skeleton
  "The home Goals section before its goals are computed: each pinned goal's
   card with its real label and a pulsing bar for the figures. Nothing when
   no goal is pinned."
  [ctx]
  (when-let [pinned (seq (sort-by (comp str/lower-case :goal/label)
                                  (visible-pinned-goals ctx)))]
    [:section.space-y-3 {:aria-label "Pinned goals"}
     [:div.flex.items-center.gap-3
      [:div.text-xs.font-semibold.uppercase.tracking-wide.text-gray-300 "Goals"]
      [:div.h-px.flex-1.bg-dark-border]
      [:a.text-xs.text-gray-500.no-underline.hover:text-neon-cyan {:href "/app/goals"}
       "All goals"]]
     [:div.grid.grid-cols-1.md:grid-cols-3.gap-3
      (map-indexed
       (fn [i goal]
         [:a {:key   (str (:xt/id goal))
              :href  (goal-href goal)
              :class "block rounded-lg bg-dark-surface px-4 py-3 no-underline transition-colors hover:bg-dark-light"
              :style {:border "1px solid #1e2430"}}
          [:div.flex.items-baseline.justify-between.gap-3
           [:div.min-w-0.truncate.text-sm.font-semibold.text-white (:goal/label goal)]
           (skeleton-bar "w-20 shrink-0" i)]
          [:div {:class "mt-2 h-1.5 w-full rounded-full bg-dark"}]
          [:div.mt-2 (skeleton-bar "w-32" i)]])
       pinned)]]))

;; ---------------------------------------------------------------------------
;; Goals list (roadmap/091-pinned-goals.md, 2026-10-06)
;; ---------------------------------------------------------------------------

(def ^:private row-grid
  "One template for every row in every group, so columns line up across
   groups. On a phone everything but the pin stacks in the second column."
  (str "grid grid-cols-[1.5rem_minmax(0,1fr)] items-center gap-x-3 gap-y-1 "
       "border-t border-dark py-3 cursor-pointer "
       "md:grid-cols-[1.5rem_minmax(0,22rem)_minmax(0,1fr)_minmax(0,19rem)] md:gap-x-6"))

(def ^:private in-column "col-start-2 min-w-0 md:col-start-auto")

(defn- list-bar
  [fraction color]
  [:div {:class "mt-1.5 h-1.5 w-full rounded-full bg-dark"}
   (when (some? fraction)
     [:span {:class (str "block h-full rounded-full " color)
             :style {:width (str (min 100.0 (* 100.0 (max 0.0 fraction))) "%")}}])])

(defn- figure
  "A labelled number in a list row: quiet label, bright value."
  [label value]
  [:span.text-gray-400 label " " [:b.font-semibold.text-white value]])

(defn- list-row
  [{:keys [goal]} group meta progress figures]
  [:div {:class         row-grid
         :data-goal-row true
         :data-goal-id  (str (:xt/id goal))
         :data-group    group}
   [:div {:class "col-start-1 row-start-1 md:col-start-auto md:row-start-auto"}
    (pin-form goal "list" (if (pinned? goal) "★" "☆"))]
   [:div {:class in-column}
    [:a {:href           (goal-href goal)
         :data-goal-link true
         :class          "block truncate text-sm font-semibold text-white no-underline hover:text-neon-cyan"}
     (:goal/label goal)]
    [:div.truncate.text-xs.text-gray-400 meta]]
   [:div {:class in-column} progress]
   (into [:div {:class (str in-column " flex flex-wrap gap-x-4 text-xs tabular-nums md:justify-end")}]
         figures)])

(defn- logged-of
  "\"245 / 5,000 reps\": the total in the target's units."
  [m logged target]
  (str (if (duration-total? m) (amount m (or logged 0)) (fmt-num (or logged 0)))
       " / " (amount m target)))

(defn- cumulative-row
  [{:keys [goal measurement progress] :as entry}]
  (let [m measurement
        {:keys [logged target today-amount required gap-at-day-start window]} progress
        [_ _ status status-class] (card-figures entry)]
    (list-row entry "cumulative"
              (str (source-line entry) " · ends " (fmt-date (:goal/ends-on goal) "MMM d, yyyy"))
              [:<>
               [:div.text-xs.text-gray-400.tabular-nums (logged-of m logged target)]
               (list-bar (:progress progress) "bg-neon-cyan")]
              (if required
                [[:span (data-attrs "data-" {:today today-amount :required required})
                  (figure "today" (str (qty m today-amount 0) "/"
                                       (pace-needed-whole m required)))]
                 [:span {:data-gap (str gap-at-day-start)}
                  (figure "gap" (signed-qty m gap-at-day-start))]
                 [:span.text-gray-400 (str (:remaining-days window) " days left")]]
                [[:span {:class status-class} status]]))))

(defn- periodic-row
  [{:keys [measurement progress weeks] :as entry}]
  (let [m measurement
        {:keys [logged target today-amount]} progress]
    (list-row entry "periodic"
              (str (source-line entry) " · " (amount m target) " per week, resets Monday")
              [:<>
               [:div.text-xs.text-gray-400.tabular-nums
                (str (logged-of m logged target) " this week")]
               (list-bar (:progress progress) "bg-neon-violet")]
              [(figure "today" (amount m today-amount))
               (figure "last 4 weeks"
                       (str/join " · " (map #(if % (amount m %) "—") weeks)))])))

(defn- other-row
  [{:keys [goal] :as entry}]
  (let [[fraction headline status status-class] (card-figures entry)]
    (list-row entry "other"
              (str (source-line entry) " · " (timing-text goal))
              [:<>
               [:div.text-xs.text-gray-400.tabular-nums headline]
               (list-bar fraction "bg-neon-cyan")]
              [[:span {:class status-class} status]])))

(def ^:private groups
  [[:cumulative "Cumulative · with an end date" cumulative-row]
   [:periodic "Periodic · each period stands alone" periodic-row]
   [:other "No deadline, best, and books" other-row]])

(defn- goals-list
  "Entries grouped by family. `render` draws one row from its group key, the
   group's row fn, the entry, and its index in the group; by default it calls
   the row fn."
  ([entries]
   (goals-list entries (fn [_ row entry _] (row entry))))
  ([entries render]
   (let [by-group (group-by #(cond (cumulative? %) :cumulative
                                   (periodic? %)   :periodic
                                   :else           :other)
                            entries)]
     [:div {:data-goals-list true}
      (for [[k title row] groups
            :let [rows (get by-group k)]
            :when (seq rows)]
        [:section {:key (name k) :data-goal-group (name k) :aria-label title
                   :class "mt-8 first:mt-0"}
         [:h2 {:class "mb-1 text-xs uppercase tracking-wider text-gray-400"} title]
         (map-indexed #(render k row %2 %1) rows)])])))

;; ---------------------------------------------------------------------------
;; Goals list shell (roadmap/098-perceived-performance.md)
;;
;; Labels, families, and pins come from the goal documents alone, which cost
;; milliseconds; progress needs every goal's source records, which cost
;; seconds. The shell draws each row from the former with skeleton bars where
;; the latter will go.
;; ---------------------------------------------------------------------------

(defn- pending-entries
  "Visible active goals as list entries without progress, in list order. A
   book goal carries a placeholder `:book-progress` so it groups as it will
   once computed."
  [{:keys [session biff/db] :as ctx}]
  (let [user-id (:uid session)
        goals   (dashboard/visible-goals db user-id
                                         (queries/goals-for-user db user-id)
                                         (queries/resolve-user-settings ctx))]
    (->> goals
         (remove #(true? (:goal/archived %)))
         (keep (fn [g]
                 (when-let [m (registry/measurement g)]
                   (cond-> {:goal g :measurement m}
                     (= :completion (:aggregation m)) (assoc :book-progress ::pending)))))
         (sort-by (juxt (comp not pinned? :goal)
                        (comp str/lower-case :goal/label :goal))))))

(defn- skeleton-row
  "A list row with its real pin and label, and pulsing bars for the rest."
  [group-key _ entry i]
  (list-row entry (name group-key)
            [:span.mt-1.block (skeleton-bar "w-48" i)]
            [:<> (skeleton-bar "w-24" i) (list-bar nil "")]
            [(skeleton-bar "w-40" i)]))

;; ---------------------------------------------------------------------------
;; Goal page · cumulative dated goals (roadmap/090, design direction 2026-10-06)
;; ---------------------------------------------------------------------------

(defn- ruler-pos
  [{:keys [lo hi]} v]
  (max 0.0 (min 100.0 (* 100.0 (/ (- (double v) lo) (- hi lo))))))

(defn- left [p] {:left (str p "%")})

(def ^:private mark-names
  {:keep "hold steady", :even "back on plan", :target "target"})

(def ^:private mark-colors
  {:keep "text-neon-amber", :even "text-white", :round "text-neon-cyan",
   :target "text-neon-lime"})

(defn- distance-text
  [m d]
  (let [a (qty m (Math/abs (double d)))]
    (cond (re-matches #"[0.,]+" a) "here"
          (pos? d)                 (str a " to go")
          :else                    (str a " past"))))

(defn- edge-class
  "Labels near an edge hang inward so they stay on the ruler."
  [p]
  (cond (> p 85) " right" (< p 15) " left" :else ""))

(defn- ruler-view
  "The zoomed ruler. main.js drops overlapping mark labels into lanes."
  [m {:keys [logged elapsed-logged today-amount]} {:keys [ticks marks] :as r}]
  (let [pos    #(ruler-pos r %)
        before (pos (or elapsed-logged 0))
        now    (pos (or logged 0))]
    [:div {:class           "goal-ruler"
           :data-goal-ruler true
           :data-lo         (str (:lo r))
           :data-hi         (str (:hi r))}
     [:div.goal-ruler-axis]
     (for [t ticks]
       [:span.goal-ruler-minor {:key (str t) :style (left (pos t))}])
     [:div {:class "goal-ruler-seg bg-neon-cyan"
            :style {:left "0%" :width (str before "%")}}]
     [:div {:class "goal-ruler-seg bg-neon-lime"
            :style {:left (str before "%") :width (str (- now before) "%")}}]
     (for [{:keys [kind value distance side]} marks
           :let [p (case side :below 0.0 :above 100.0 (pos value))]]
       [:div {:key             (name kind)
              :data-ruler-mark (name kind)
              :data-value      (str value)
              :class           (str "goal-ruler-mark down " (name kind)
                                    (case side
                                      :below " off left"
                                      :above " off right"
                                      (edge-class p)))
              :style           (left p)}
        [:i]
        [:span
         (when (= :below side) "← ")
         [:span {:class (str "tabular-nums " (mark-colors kind))} (qty m value)]
         (when-let [n (mark-names kind)] [:span.text-gray-400 (str " " n)])
         (when (= :above side) " →")
         [:br]
         [:span.text-gray-400.tabular-nums (distance-text m distance)]]])
     [:div {:class           (str "goal-ruler-mark you up" (edge-class now))
            :data-ruler-mark "you"
            :style           (left now)}
      [:i]
      [:span.text-white
       [:b.tabular-nums (qty m logged)]
       [:span.text-gray-400 (str " +" (qty m (or today-amount 0)) " today")]]]]))

(defn- overall-bar
  "The whole goal, with the ruler's window outlined on it."
  [m {:keys [logged target]} r]
  (let [pct #(min 100.0 (* 100.0 (/ (max 0.0 (double %)) target)))]
    [:div {:class "mt-5 grid grid-cols-[auto_minmax(0,1fr)_auto] items-center gap-3 text-xs"}
     [:b.text-white.tabular-nums
      (if (duration-total? m) (amount m (or logged 0)) (fmt-num (or logged 0)))]
     [:span {:class "relative block h-2 rounded-full bg-dark"}
      [:span {:class "block h-full rounded-full bg-neon-cyan"
              :style {:width (str (pct (or logged 0)) "%")}}]
      (when r
        [:span {:class "goal-zoom"
                :style {:left  (str (pct (:lo r)) "%")
                        :width (str (max 0.0 (- (pct (:hi r)) (pct (:lo r)))) "%")}}])]
     [:span.text-gray-400.tabular-nums (amount m target)]]))

(defn- key-row
  [k label value & [cls]]
  [:<>
   [:dt.text-gray-400 label]
   [:dd {:class    (str "m-0 text-right font-semibold " (or cls "text-white"))
         :data-key k}
    (or value "—")]])

(defn- quiet-row
  [label value]
  [:div.goal-extra-row [:dt label] [:dd.m-0.text-right value]])

(defn- cumulative-page
  [{:keys [goal measurement progress] :as entry} cutoff-date]
  (let [m measurement
        {:keys [window logged target today-amount average required gap-at-day-start
                even-rate live-average]} progress
        r (calc/ruler progress m)]
    [:article {:data-goal-page "cumulative" :data-goal-id (str (:xt/id goal))}
     [:nav.text-xs.text-gray-400 [:a.link {:href "/app/goals"} "Goals"] " /"]
     [:div.mt-1.flex.flex-wrap.items-baseline.justify-between.gap-3
      [:h1.text-xl.font-semibold.text-white.break-words (:goal/label goal)]
      (actions goal)]
     [:p.mt-0.5.text-xs.text-gray-400 (str (source-line entry) " · " (:label m))]
     (overall-bar m progress r)
     (when r (ruler-view m progress r))
     [:div.goal-info
      [:dl {:class "goal-key tabular-nums" :data-goal-key true}
       (key-row "today" "today" (when today-amount (qty m today-amount 0)))
       (key-row "average" "avg (elapsed days)" (qty m average 1))
       (key-row "required" "pace needed (from today)" (qty m required 1) "text-neon-amber")
       (key-row "gap" "gap at day start" (signed-qty m gap-at-day-start))
       (key-row "to-even" "needed today to reach even pace"
                (some #(when (= :even (:kind %)) (qty m (max 0.0 (:distance %)) 0))
                      (:marks r)))]
      [:dl {:class "goal-extra tabular-nums"}
       (quiet-row "period" (date-span (:start window) (:end window)))
       (quiet-row "days left" (str (or (:remaining-days window) 0)))
       (quiet-row "even pace (whole period)" (rate m even-rate))
       (quiet-row "avg (incl. today)" (rate m live-average))
       (when r
         (quiet-row "ruler" (str (qty m (:lo r) 0) "–" (qty m (:hi r) 0) " " (qty-unit m))))]
      [:div.goal-log
       [:a.form-button-primary {:href (log-href m) :data-goal-log true} "+ Log"]]]
     [:div.mt-8
      (chart-block "goal-chart" (numeric-chart entry cutoff-date)
                   (str (:goal/label goal) ": " (amount m logged) " of " (amount m target)))]]))

(defn- goal-page-body
  "One goal: the ruler page for cumulative dated goals, the existing detail
   card for every other kind."
  [entry]
  (if (cumulative? entry)
    (cumulative-page entry (:cutoff-date entry))
    [:div
     [:nav.mb-2.text-xs.text-gray-400 [:a.link {:href "/app/goals"} "Goals"] " /"]
     (goal-detail entry (:cutoff-date entry))]))

;; ---------------------------------------------------------------------------
;; Handlers
;; ---------------------------------------------------------------------------

(defn- page-notes
  [{:keys [archived hidden-count]}]
  [:<>
   (archived-section archived)
   (when (pos? hidden-count)
     [:p.text-xs.text-gray-500
      (str hidden-count " goal(s) hidden by your sensitive/archived settings.")])])

(def ^:private goals-regions
  "The goals page regions the full page fills in the shell."
  ["goals-list" "goals-notes"])

(defn- empty-goals
  []
  (layout/empty-state
   {:message "No goals yet. A goal counts what you already log — time, sessions, reps, or finishing a book."
    :action  [:a.form-button-primary {:href "/app/goals/new"} "Create your first goal"]}))

(defn- goals-layout
  [ctx & content]
  (ui/page
   ctx
   (apply layout/page-shell
          ctx {:width :wide}
          (layout/page-header
           {:title    "Goals"
            :subtitle "A little closer, every time."
            :actions  [:a.form-button-primary {:href "/app/goals/new"} "+ New goal"]})
          content)))

(defn- goals-shell
  [ctx]
  (let [entries (pending-entries ctx)]
    (goals-layout
     ctx
     [:div (deferred/skeleton-attrs "goals-list")
      (if (empty? entries)
        (empty-goals)
        (goals-list entries skeleton-row))]
     [:div {:id "goals-notes"}]
     (deferred/loader ctx {:region-ids goals-regions}))))

(defn- goals-full
  [{:keys [session biff/db] :as ctx}]
  (let [data    (dashboard/dashboard db (:uid session)
                                     {:now           (Instant/now)
                                      :user-settings (queries/resolve-user-settings ctx)})
        entries (sort-by (juxt (comp not pinned? :goal)
                               (comp str/lower-case :goal/label :goal))
                         (:active data))]
    (goals-layout
     ctx
     [:div {:id "goals-list"}
      (if (empty? entries)
        (empty-goals)
        (goals-list entries))]
     [:div {:id "goals-notes"} (page-notes data)])))

(def ^:private goals-page*
  (deferred/handler {:shell goals-shell :full goals-full}))

(defn goals-page
  "GET /app/goals — every goal, grouped by family. The shell lists each goal
   by label with skeleton progress, and `?full=1` fills it in (see
   `deferred`). `?goal=<id>`, the old selected-goal URL, redirects to that
   goal's page."
  [{:keys [params] :as ctx}]
  (if-let [id (some-> (:goal params) parse-uuid)]
    {:status 303 :headers {"location" (str "/app/goal/" id)}}
    (goals-page* ctx)))

(defn- owned-goal
  [{:keys [biff/db session path-params]}]
  (when-let [id (some-> (:id path-params) parse-uuid)]
    (queries/get-entity-for-user db id (:uid session) :goal)))

(defn goal-page
  "GET /app/goal/:id — one goal, computed alone. Archived, hidden, or
   missing goals go back to the list."
  [{:keys [session biff/db] :as ctx}]
  (let [goal  (owned-goal ctx)
        entry (when goal
                (first (:active (dashboard/dashboard
                                 db (:uid session)
                                 {:now           (Instant/now)
                                  :user-settings (queries/resolve-user-settings ctx)
                                  :goals         [goal]}))))]
    (if-not entry
      {:status 303 :headers {"location" "/app/goals"}}
      (ui/page
       (assoc ctx ::ui/echarts true)
       (layout/page-shell ctx {:width :wide} (goal-page-body entry))))))

(defn- back-to
  [goal]
  {:status 303 :headers {"location" (if goal (goal-href goal) "/app/goals")}})

(defn save-preferences!
  "POST /app/goal/:id/preferences — the book chart measure and even-pace
   guide, saved through the validated goal mutation."
  [{:keys [params] :as ctx}]
  (if-let [goal (owned-goal ctx)]
    (let [measure (some-> (:progress-measure params) not-empty keyword)
          pace    (:even-pace params)
          pace    (if (sequential? pace) (last pace) pace)
          data    (cond-> {}
                    (contains? calc/position-measures measure)
                    (assoc :goal/progress-measure measure)
                    (some? pace)
                    (assoc :goal/even-pace-enabled (shared/param-true? pace)))]
      (when (seq data)
        (mutations/update-entity! ctx {:entity-key :goal :entity-id (:xt/id goal) :data data}))
      (back-to goal))
    (back-to nil)))

(defn set-archived!
  "POST /app/goal/:id/archive"
  [{:keys [params] :as ctx}]
  (if-let [goal (owned-goal ctx)]
    (let [archived? (shared/param-true? (:archived params))]
      (mutations/update-entity! ctx {:entity-key :goal :entity-id (:xt/id goal)
                                     :data {:goal/archived (if archived? true :db/dissoc)}})
      (back-to (when-not archived? goal)))
    (back-to nil)))

(defn set-pinned!
  "POST /app/goal/:id/pin — pinning leads the goals list, adds a home card,
   and lists the goal in the sidebar. Unpinning dissocs the flag rather than
   storing false. `from=list` returns to the list."
  [{:keys [params] :as ctx}]
  (if-let [goal (owned-goal ctx)]
    (let [pin? (shared/param-true? (:pinned params))]
      (mutations/update-entity! ctx {:entity-key :goal :entity-id (:xt/id goal)
                                     :data {:goal/pinned (if pin? true :db/dissoc)}})
      (back-to (when-not (= "list" (:from params)) goal)))
    (back-to nil)))

(defn delete!
  "POST /app/goal/:id/delete — soft delete; logs are untouched."
  [ctx]
  (when-let [goal (owned-goal ctx)]
    (mutations/soft-delete-entity! ctx {:entity-key :goal :entity-id (:xt/id goal)}))
  (back-to nil))

;; Per-goal routes live under /goal/:id rather than /goals/:id: reitit
;; rejects a router where /goals/new or /goals/editor overlaps /goals/:id,
;; and a rejected router silently leaves the dev server on the old one.
(def routes
  [""
   ["/goals" {}
    ["" {:get goals-page, :post editor/create!}]
    ["/new" {:get editor/new-page}]
    ["/editor" {:get editor/refresh}]
    ["/suggestion" {:get editor/suggestion-fragment}]]
   ["/goal/:id" {}
    ["" {:get goal-page, :post editor/update!}]
    ["/edit" {:get editor/edit-page}]
    ["/preferences" {:post save-preferences!}]
    ["/pin" {:post set-pinned!}]
    ["/archive" {:post set-archived!}]
    ["/delete" {:post delete!}]]])
