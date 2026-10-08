(ns tech.jgood.gleanmo.home.sample-charts
  "Static sample charts for the public landing page.

   Each one is a small, server-rendered SVG copy of a chart the app draws with
   ECharts: the activity calendar heatmap (viz pages), the goal progress line
   (goals page), and the book progress chart (book goals). The data is made up
   and fixed, so the page is the same on every render and loads no chart
   library. Colors match the app's chart palette in `app/goals.clj`."
  (:require
   [clojure.string :as str])
  (:import
   [java.time LocalDate]
   [java.time.format DateTimeFormatter]
   [java.time.temporal ChronoUnit]))

(def ^:private cyan "#06b6d4")
(def ^:private violet "#a78bfa")
(def ^:private green "#39cf83")
(def ^:private muted "#929da9")
(def ^:private grid-line "#242b33")
(def ^:private pace-gray "#6b7785")
(def ^:private empty-cell "#161b22")
(def ^:private cell-edge "#30363d")

(def ^:private text-attrs
  {:fill muted :font-size 9 :style {:font-family "inherit"}})

(defn- date [s] (LocalDate/parse s))

(defn- days-between [a b] (.between ChronoUnit/DAYS a b))

(def ^:private month-day (DateTimeFormatter/ofPattern "MMM d"))
(def ^:private month-only (DateTimeFormatter/ofPattern "MMM"))

;; ---------------------------------------------------------------------------
;; Line / scatter plot
;; ---------------------------------------------------------------------------

(def ^:private plot-w 320)
(def ^:private plot-h 190)
(def ^:private pad {:l 38 :r 10 :t 30 :b 22})

(defn- scale
  [{:keys [start end y-max]}]
  (let [x0 (:l pad) x1 (- plot-w (:r pad))
        y0 (- plot-h (:b pad)) y1 (:t pad)
        span (days-between start end)]
    {:x (fn [d] (+ x0 (* (- x1 x0) (/ (double (days-between start d)) span))))
     :y (fn [v] (- y0 (* (- y0 y1) (/ (double v) y-max))))
     :x0 x0 :x1 x1 :y0 y0 :y1 y1}))

(defn- fmt-num [v]
  (if (== v (Math/rint v)) (str (long v)) (str v)))

(defn- dash-array [dash]
  (case dash :dashed "5 4" :dotted "1 4" nil))

(defn- legend
  [entries]
  (into [:g]
        (map-indexed
         (fn [i {:keys [label color dash]}]
           (let [x (+ (:l pad) (* i 110))]
             [:g
              [:line {:x1 x :y1 10 :x2 (+ x 14) :y2 10 :stroke color
                      :stroke-width 2 :stroke-dasharray (dash-array dash)}]
              [:text (assoc text-attrs :x (+ x 18) :y 13) label]])))
        entries))

(defn- plot
  "A time-axis chart in the app's goal chart style. Every value is data:
   `lines` and `points` take `[date value]` pairs; `hlines` and `vlines` are
   labelled reference lines."
  [{:keys [title y-ticks x-ticks lines points hlines vlines legend-entries]
    :as spec}]
  (let [{:keys [x y x0 x1 y0 y1]} (scale spec)
        path (fn [pts] (str/join " " (map-indexed
                                      (fn [i [d v]]
                                        (str (if (zero? i) "M" "L")
                                             (format "%.1f %.1f" (x d) (y v))))
                                      pts)))]
    [:svg {:viewBox (str "0 0 " plot-w " " plot-h) :class "w-full h-auto"
           :role "img" :aria-label title}
     (legend legend-entries)
     ;; grid and y labels
     (for [v y-ticks]
       [:g {:key (str "y" v)}
        [:line {:x1 x0 :x2 x1 :y1 (y v) :y2 (y v) :stroke grid-line}]
        [:text (assoc text-attrs :x (- x0 6) :y (+ (y v) 3) :text-anchor "end")
         (fmt-num v)]])
     ;; x labels
     (for [d x-ticks]
       [:text (assoc text-attrs :key (str d) :x (x d) :y (+ y0 14)
                     :text-anchor "middle")
        (.format month-day d)])
     [:line {:x1 x0 :x2 x1 :y1 y0 :y2 y0 :stroke cell-edge}]
     ;; reference lines
     (for [{:keys [v label color]} hlines]
       [:g {:key (str "h" v)}
        [:line {:x1 x0 :x2 x1 :y1 (y v) :y2 (y v) :stroke color
                :stroke-dasharray "5 4"}]
        [:text (assoc text-attrs :x (+ x0 4) :y (- (y v) 4)) label]])
     (for [{:keys [d label color]} vlines]
       [:g {:key (str "v" d)}
        [:line {:x1 (x d) :x2 (x d) :y1 y1 :y2 y0 :stroke color
                :stroke-dasharray "5 4"}]
        [:text (assoc text-attrs :x (- (x d) 3) :y (+ y1 9) :fill color
                      :text-anchor "end")
         label]])
     ;; series
     (for [{:keys [pts color dash width area] :or {width 2}} lines]
       [:g {:key (str color dash)}
        (when area
          [:path {:d (str (path pts)
                          (format " L%.1f %.1f L%.1f %.1f Z"
                                  (x (first (last pts))) (y 0)
                                  (x (ffirst pts)) (y 0)))
                  :fill color :fill-opacity 0.06 :stroke "none"}])
        [:path {:d (path pts) :fill "none" :stroke color :stroke-width width
                :stroke-dasharray (dash-array dash) :stroke-linecap "round"}]])
     (for [[d v] points]
       [:circle {:key (str d v) :cx (x d) :cy (y v) :r 3.5 :fill cyan}])]))

;; ---------------------------------------------------------------------------
;; Sample data
;; ---------------------------------------------------------------------------

(defn- cumulative
  [start daily]
  (->> daily
       (reductions +)
       (map-indexed (fn [i v] [(.plusDays start (inc i)) v]))
       (cons [start 0])
       vec))

(defn exercise-goal
  "Goal progress: kettlebell swings toward a monthly total."
  []
  (let [start  (date "2026-09-01")
        end    (date "2026-10-01")
        target 1500
        logged (cumulative start [60 0 80 50 0 100 70 0 60 90 0 0 120 80
                                  50 0 100 60 0 90 70 110])
        [now total] (peek logged)]
    {:title  "Kettlebell swings"
     :detail (str "Goal: " target " reps in September · " total " so far")
     :chart  (plot {:title          "Kettlebell swings goal progress"
                    :start          start :end end :y-max target
                    :y-ticks        [0 500 1000 1500]
                    :x-ticks        (map #(date (str "2026-09-" %)) ["04" "13" "22"])
                    :legend-entries [{:label "Logged" :color cyan}
                                     {:label "Even pace" :color pace-gray :dash :dashed}
                                     {:label "Needed" :color violet :dash :dashed}]
                    :lines          [{:pts logged :color cyan :width 2.5 :area true}
                                     {:pts [[start 0] [end target]]
                                      :color pace-gray :dash :dashed :width 1.5}
                                     {:pts [[now total] [end target]]
                                      :color violet :dash :dashed}]
                    :vlines         [{:d now :label "Now" :color cyan}]})}))

(defn- book-chart
  [{:keys [title author unit total start end positions finished now y-ticks
           x-ticks]}]
  (let [pts (mapv (fn [[d v]] [(date d) v]) positions)]
    {:title  title
     :detail (str author " · " (if finished "finished" "reading"))
     :chart  (plot {:title          (str title " reading progress")
                    :start          (date start) :end (date end)
                    :y-max          (* 1.15 total)
                    :y-ticks        y-ticks
                    :x-ticks        (map date x-ticks)
                    :legend-entries [{:label (str "Position (" unit ")") :color cyan}]
                    :lines          [{:pts pts :color cyan}]
                    :points         pts
                    :hlines         [{:v total :label "Book total" :color violet}]
                    :vlines         [(if finished
                                       {:d (date finished) :label "Finished" :color green}
                                       {:d (date now) :label "Now" :color cyan})]})}))

(defn reading-in-progress
  []
  (book-chart {:title     "The Dispossessed"
               :author    "Ursula K. Le Guin"
               :unit      "pages"
               :total     387
               :start     "2026-08-15" :end "2026-09-21" :now "2026-09-18"
               :positions [["2026-08-18" 34] ["2026-08-20" 61] ["2026-08-23" 102]
                           ["2026-08-27" 140] ["2026-09-02" 188] ["2026-09-05" 230]
                           ["2026-09-12" 251] ["2026-09-16" 297]]
               :y-ticks   [0 100 200 300 400]
               :x-ticks   ["2026-08-20" "2026-09-01" "2026-09-13"]}))

(defn reading-finished
  []
  (book-chart {:title     "Kindred"
               :author    "Octavia E. Butler"
               :unit      "pages"
               :total     264
               :start     "2026-07-01" :end "2026-07-17"
               :finished  "2026-07-14"
               :positions [["2026-07-02" 40] ["2026-07-05" 88] ["2026-07-07" 131]
                           ["2026-07-10" 170] ["2026-07-12" 219] ["2026-07-14" 264]]
               :y-ticks   [0 100 200 300]
               :x-ticks   ["2026-07-03" "2026-07-09" "2026-07-15"]}))

(defn reading-audiobook
  []
  (book-chart {:title     "Exhalation"
               :author    "Ted Chiang · audiobook"
               :unit      "hours"
               :total     11.3
               :start     "2026-08-28" :end "2026-09-21" :now "2026-09-18"
               :positions [["2026-08-30" 0.9] ["2026-09-01" 2.1] ["2026-09-04" 3.0]
                           ["2026-09-06" 4.6] ["2026-09-09" 5.2] ["2026-09-13" 6.8]
                           ["2026-09-15" 7.5]]
               :y-ticks   [0 4 8 12]
               :x-ticks   ["2026-09-01" "2026-09-09" "2026-09-17"]}))

;; ---------------------------------------------------------------------------
;; Calendar heatmap
;; ---------------------------------------------------------------------------

(def habits ["Stretch" "Walk outside" "Read before bed"])

(def ^:private heatmap-start (date "2026-04-06")) ; a Monday
(def ^:private heatmap-weeks 26)

(def ^:private habit-counts
  "Habits done per day (0-3), seeded so every render is the same."
  (loop [seed 20260601 i 0 out []]
    (if (= i (* 7 heatmap-weeks))
      out
      (let [seed' (bit-and (+ (* seed 1103515245) 12345) 0x7fffffff)
            r     (/ (double seed') 0x7fffffff)]
        (recur seed' (inc i)
               (conj out (cond (< r 0.18) 0 (< r 0.45) 1 (< r 0.80) 2 :else 3)))))))

(defn- cell-fill [n]
  (if (zero? n) empty-cell cyan))

(defn- cell-opacity [n]
  (get [1 0.35 0.65 1] n))

(defn- heatmap-svg
  "The last `weeks` weeks of the sample data as an activity calendar: one
   column per week, one row per weekday."
  [weeks class]
  (let [cell 12 gap 3 step (+ cell gap)
        left 28 top 16
        skip (- heatmap-weeks weeks)
        start (.plusWeeks heatmap-start skip)
        habit-counts (drop (* 7 skip) habit-counts)
        w (+ left (* weeks step))
        h (+ top (* 7 step) 22)
        months (->> (range weeks)
                    (keep (fn [wk]
                            (let [d (.plusWeeks start wk)]
                              (when (<= (.getDayOfMonth d) 7)
                                [wk (.format month-only d)])))))]
    [:svg {:viewBox (str "0 0 " w " " h) :class class
           :role "img" :aria-label "Habit calendar heatmap"}
     (for [[wk label] months]
       [:text (assoc text-attrs :key label :x (+ left (* wk step)) :y 10) label])
     (for [[row label] [[0 "Mon"] [2 "Wed"] [4 "Fri"]]]
       [:text (assoc text-attrs :key label :x 0 :y (+ top (* row step) 10)) label])
     (for [[i n] (map-indexed vector habit-counts)]
       [:rect {:key i
               :x (+ left (* (quot i 7) step)) :y (+ top (* (rem i 7) step))
               :width cell :height cell :rx 2
               :fill (cell-fill n) :fill-opacity (cell-opacity n)
               :stroke cell-edge :stroke-width (if (zero? n) 1 0)}])
     (let [y (+ top (* 7 step) 8)
           x (- w (* 4 step) 52)]
       [:g
        [:text (assoc text-attrs :x (- x 4) :y (+ y 10) :text-anchor "end") "0"]
        (for [n (range 4)]
          [:rect {:key n :x (+ x (* n step)) :y y :width cell :height cell :rx 2
                  :fill (cell-fill n) :fill-opacity (cell-opacity n)
                  :stroke cell-edge :stroke-width (if (zero? n) 1 0)}])
        [:text (assoc text-attrs :x (+ x (* 4 step) 2) :y (+ y 10)) "3 habits"]])]))

(defn habit-heatmap
  "Activity calendar: 26 weeks on wide screens, the last 13 on phones, where
   26 columns would shrink the labels past reading."
  []
  {:title  "Habit log"
   :detail (str "Habits done per day: " (str/join ", " habits))
   :chart  [:div
            (heatmap-svg heatmap-weeks "hidden md:block w-full h-auto max-w-2xl")
            (heatmap-svg 13 "md:hidden w-full h-auto")]})
