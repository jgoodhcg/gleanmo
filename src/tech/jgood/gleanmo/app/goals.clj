(ns tech.jgood.gleanmo.app.goals
  "The goals dashboard: a bounded, sortable, filterable table of every goal,
   and one selected goal's chart, activity strip, and comparison or
   recent-reading panel.

   Totals include today through one captured request instant. Rates use
   completed days in each goal's saved zone. Mutations here
   (book chart preferences, archive, delete) write and 303 back, so the page
   always renders from a fresh snapshot."
  (:require
   [cheshire.core :as json]
   [clojure.string :as str]
   [com.biffweb :as biff]
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
                          (if (and (pos? h) (< h 1))
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

(defn- pace-text
  [days]
  (if (nil? days)
    "—"
    (str (if (neg? days) "−" "+") (fmt-num (Math/abs (double days))) " d")))

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
    (str (short-date (:goal/starts-on goal)) " – " (short-date (:goal/ends-on goal)))))

(defn- kind-of [{:keys [measurement]}] (name (:kind measurement)))

(defn- timing-key
  [goal]
  (case (:goal/timing goal) :open-ended "open" :weekly "weekly" "dated"))

;; ---------------------------------------------------------------------------
;; Table
;; ---------------------------------------------------------------------------

(defn- progress-bar
  [fraction tick open?]
  [:div {:class "relative mt-2 ml-auto h-1.5 w-full max-w-[10rem] rounded-full border border-dark bg-dark"}
   (when (some? fraction)
     [:span {:class (str "block h-full rounded-full " (if open? "bg-neon-lime" "bg-neon-cyan"))
             :style {:width (str (min 100.0 (* 100.0 (max 0.0 fraction))) "%")}}])
   (when tick
     [:i {:class       "absolute -top-1 h-3 w-0.5 bg-white"
          :aria-hidden "true"
          :style       {:left (str (min 100.0 (* 100.0 tick)) "%")}}])])

(defn- sort-attrs
  "data-sort-* attributes; absent values sort last client-side."
  [m]
  (into {}
        (keep (fn [[k v]] (when (some? v) [(keyword (str "data-sort-" (name k))) (str v)])))
        m))

(defn- goal-link
  [goal & body]
  (into [:a {:href           (str "/app/goals?goal=" (:xt/id goal))
             :data-goal-link true
             :hx-get         (str "/app/goals?goal=" (:xt/id goal))
             :hx-target      "#goal-detail-mount"
             :hx-select      "#goal-detail"
             :hx-swap        "innerHTML"
             :hx-push-url    "true"
             :class          "block text-left text-sm font-semibold text-white no-underline hover:text-neon-cyan"}]
        body))

(defn- name-cell
  [{:keys [goal] :as entry}]
  [:td {:class "px-4 py-3 text-left align-top"}
   (goal-link goal (:goal/label goal))
   [:span.block.text-xs.text-gray-400.mt-1
    (source-line entry) [:span.text-gray-300 (str " · " (timing-text goal))]]])

(defn- dash-cell [] [:td {:class "px-4 py-3 text-right text-gray-500"} "—"])

(defn- numeric-row
  [{:keys [goal measurement progress] :as entry} selected?]
  (let [{:keys [logged target average required ratio pace-days next reached?
                even-pace window progress]
         :as   p} progress
        m      measurement
        best?  (= :best (:aggregation m))
        open?  (= :open-ended (:goal/timing goal))
        warn?  (and ratio (> ratio 1))
        status (if warn? "text-neon-amber" "text-neon-lime")
        rate-v (fn [v] (when v (if (duration-total? m) (/ v 60) v)))]
    [:tr (merge {:data-goal-row true
                 :data-goal-id  (str (:xt/id goal))
                 :data-kind     (kind-of entry)
                 :data-timing   (timing-key goal)
                 :data-search   (str/lower-case (str (:goal/label goal) " " (source-line entry)))
                 :aria-selected (str selected?)
                 :class         "border-b border-dark cursor-pointer hover:bg-dark"}
                (sort-attrs {:label    (str/lower-case (:goal/label goal))
                             :progress (:progress p)
                             :rate     (rate-v average)
                             :needed   (if best? (when-not reached? (/ (:remaining p) target))
                                           (rate-v required))
                             :ratio    ratio
                             :pace     pace-days
                             :next     (when next (/ (- next (or logged 0)) target))}))
     (name-cell entry)
     [:td {:class "px-4 py-3 text-right align-top tabular-nums"}
      [:span.text-white (if (and logged (duration-total? m))
                          (fmt-num (/ logged 3600))
                          (if logged (fmt-num logged) "—"))]
      [:span.text-gray-400 (str " / " (amount m target))]
      (progress-bar progress
                    (when (and even-pace (= :active (:status window))) even-pace)
                    open?)]
     (if best? (dash-cell)
         [:td {:class "px-4 py-3 text-right align-top"} (rate m average)])
     (cond
       open?    (dash-cell)
       best?    [:td {:class "px-4 py-3 text-right align-top"}
                 (if reached? "Reached ✓" (str (amount m (:remaining p)) " to go"))]
       required [:td {:class (str "px-4 py-3 text-right align-top " status)}
                 (rate m required)]
       :else    (dash-cell))
     (if ratio
       [:td {:class (str "px-4 py-3 text-right align-top " status)}
        (str (when warn? "↑ ") (format "%.2f×" (double ratio)))]
       (dash-cell))
     [:td {:class "px-4 py-3 text-right align-top"} (pace-text pace-days)]
     [:td {:class "px-4 py-3 text-right align-top"}
      (if next
        [:span (amount m (- next (or logged 0)))
         [:span.text-xs.text-gray-400 (str " to " (amount m next))]]
        (if reached? [:span.text-neon-lime "Reached ✓"] "—"))]]))

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

(defn- book-row
  [{:keys [goal book-progress] :as entry} selected?]
  (let [state   (book-state entry)
        measure (chart-measure entry)
        {:keys [latest total]} (get-in book-progress [:measures measure])]
    [:tr (merge {:data-goal-row true
                 :data-goal-id  (str (:xt/id goal))
                 :data-kind     "completion"
                 :data-timing   (timing-key goal)
                 :data-search   (str/lower-case (str (:goal/label goal) " " (source-line entry)))
                 :aria-selected (str selected?)
                 :class         "border-b border-dark cursor-pointer hover:bg-dark"}
                (sort-attrs {:label    (str/lower-case (:goal/label goal))
                             :progress (if (= :completed state) 1 0)}))
     (name-cell entry)
     [:td {:class "px-4 py-3 text-right align-top"}
      [:div {:class (case state :completed "text-neon-lime" :overdue "text-neon-amber" "text-white")}
       (case state :completed "✓ Completed" :overdue "Overdue" "In progress")]
      [:div.text-xs.text-gray-400.mt-1
       (str (measure-labels measure) " · " (position-text measure (:value latest))
            " / " (position-text measure total))]]
     (dash-cell) (dash-cell) (dash-cell) (dash-cell) (dash-cell)]))

(defn- filter-button
  [attr value label pressed?]
  [:button {:type         "button"
            attr          value
            :aria-pressed (str pressed?)
            :class        "rounded px-2.5 py-1.5 text-xs text-gray-400 hover:bg-dark hover:text-white aria-pressed:bg-dark aria-pressed:text-neon-cyan"}
   label])

(def ^:private columns
  [["label" "Goal" "text-left"]
   ["progress" "Done / target" "text-right"]
   ["rate" "Average per completed day" "text-right"]
   ["needed" "Required" "text-right"]
   ["ratio" "Required ÷ average" "text-right"]
   ["pace" "Vs even pace" "text-right"]
   ["next" "Next threshold" "text-right"]])

(defn- goals-table
  [entries selected]
  [:section {:class "rounded-xl border border-dark bg-dark-surface overflow-hidden"
             :aria-label "All goals"
             :data-goals-table true}
   [:div {:class "flex flex-wrap items-center gap-3 border-b border-dark px-4 py-3"}
    [:h2.text-sm.font-semibold.text-white "All goals "
     [:span.text-xs.text-gray-400 {:data-goals-count true}
      (str (count entries) " / " (count entries))]]
    [:div.flex.flex-wrap.gap-1 {:role "group" :aria-label "Filter by measurement"}
     (for [[v label] [["all" "All"] ["duration" "Duration"] ["count" "Counts"]
                      ["completion" "Completion"] ["best" "Best performance"]]]
       (filter-button :data-goal-kind v label (= v "all")))]
    [:label.ml-auto
     [:span.sr-only "Filter goals by name or source"]
     [:input.form-input {:type "search" :placeholder "Filter goals…"
                         :class "w-48 py-1.5 text-xs"
                         :data-goals-search true
                         :data-original-value ""}]]]
   [:div {:class "flex flex-wrap items-center gap-3 border-b border-dark px-4 py-1.5 text-xs text-gray-400"}
    [:span "Timing"]
    [:div.flex.flex-wrap.gap-1 {:role "group" :aria-label "Filter by timing"}
     (for [[v label] [["all" "All"] ["dated" "Dated"] ["weekly" "Weekly reset"]
                      ["open" "No deadline"]]]
       (filter-button :data-goal-timing v label (= v "all")))]
    [:span.ml-auto.hidden.sm:inline "Each goal has its own clock"]]
   [:div {:class "goals-table-scroll max-h-72 overflow-auto" :tabindex "0"
          :aria-label "Scrollable goals table"}
    [:table {:class "goals-table w-full min-w-[60rem] border-separate border-spacing-0 text-xs"}
     [:thead {:class "sticky top-0 z-10 bg-dark-surface"}
      [:tr
       (for [[k label align] columns]
         [:th {:key k :data-sort-key k :aria-sort (if (= k "label") "ascending" "none")
               :class (str "border-b border-dark px-4 py-2 font-medium uppercase tracking-wider text-gray-400 " align)}
          [:button {:type "button" :class "uppercase tracking-wider"}
           (str label (if (= k "label") " ↑" " ↕"))]])]]
     [:tbody
      (for [e entries
            :let [sel? (= (get-in e [:goal :xt/id]) (get-in selected [:goal :xt/id]))]]
        (if (:book-progress e) (book-row e sel?) (numeric-row e sel?)))]]
    [:p {:class "p-8 text-center text-sm text-gray-400" :data-goals-empty true :hidden true}
     "No goals match. Try another name or goal type."]]
   [:div {:class "flex justify-between gap-3 border-t border-dark px-4 py-1.5 text-xs text-gray-400"}
    [:span [:i {:class "mr-1.5 inline-block h-2.5 w-0.5 bg-white align-middle"}]
     "Even pace requires complete coverage · each goal uses its saved time zone"]
    [:span.hidden.sm:inline "No deadline = no required pace · select a goal to explore"]]])

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

(defn- today-line
  [cutoff-date]
  {:silent true :symbol ["none" "none"]
   :lineStyle {:color "#45515d" :type "dashed"}
   :label {:formatter "Today" :color muted :fontSize 10}
   :data [{:xAxis (str cutoff-date)}]})

(defn- numeric-chart
  [{:keys [goal measurement progress]} cutoff-date]
  (let [{:keys [window series target required completed-logged]} progress
        {:keys [start end]} window
        m      measurement
        best?  (= :best (:aggregation m))
        open?  (= :open-ended (:goal/timing goal))
        paced? (and (not best?) (not open?) (some? (:even-pace progress)))
        x-end  (if (and end (not open?)) (calc/plus-days end 1)
                   (calc/plus-days cutoff-date 1))
        t      (chart-value m target)
        now?   (not (.isAfter ^LocalDate cutoff-date x-end))]
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
                          now?        (assoc :markLine (today-line cutoff-date)))]
                 paced? (conj (dashed "Even pace" "#6b7785"
                                      [[(str start) 0] [(str x-end) t]]))
                 (and paced? required)
                 (conj (dashed "Required at day start" violet
                               [[(str cutoff-date) (chart-value m completed-logged)] [(str x-end) t]]
                               :width 2))
                 (or best? open?)
                 (conj (dashed "Target" violet [[(str start) t] [(str x-end) t]])))))))

(defn- book-chart
  [{:keys [goal book-progress]} measure cutoff-date]
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
                                    (assoc (today-line (:date completion))
                                           :lineStyle {:color green :type "dashed"}
                                           :label {:formatter "Finished" :color green
                                                   :fontSize 10})
                                    (today-line cutoff-date))}))
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

(defn- actions
  [goal]
  [:div {:class "flex flex-wrap justify-end items-center gap-3 text-xs"
         :data-goal-actions true}
   [:a.link {:href (str "/app/goal/" (:xt/id goal) "/edit")} "Edit"]
   (biff/form {:action (str "/app/goal/" (:xt/id goal) "/archive") :method "post"
               :class "inline"}
              [:input {:type "hidden" :name "archived" :value "true"}]
              [:button.link {:type "submit"} "Archive"])
   (biff/form {:action (str "/app/goal/" (:xt/id goal) "/delete") :method "post"
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
      (str (short-date start) "–" (short-date end) (when partial? " · partial week"))
      :else (str (if best? "best within " "by ") (long-date end)))))

(defn- numeric-summary
  [{:keys [goal measurement progress] :as entry}]
  (let [m      measurement
        {:keys [logged target average required ratio next remaining window]} progress
        best?  (= :best (:aggregation m))
        open?  (= :open-ended (:goal/timing goal))
        {:keys [completed-days remaining-days status]} window]
    [:div {:class "grid gap-6 p-4 sm:p-6 lg:grid-cols-[1.35fr_2fr]"}
     [:div
      [:p.mt-1.text-xs.text-gray-400
       (str (source-line entry) " · " (if best? "Best performance" (:label m)))]
      [:div {:class "mt-2 text-3xl font-semibold tabular-nums text-white"}
       (if (nil? logged)
         (if best? "No record yet" "Coverage unknown")
         (amount m logged))
       [:span {:class "ml-3 text-sm font-normal text-gray-400"}
        (str "of " (amount m target))]]
      [:p.mt-1.text-xs.text-gray-400
       (str (when-let [fraction (:progress progress)]
              (str (fmt-num (* 100 fraction)) "% " (if best? "of target" "recorded") " · "))
            (window-caption goal progress best?))]
      (when (= :unknown (:coverage progress))
        [:p.mt-2.text-xs.text-neon-amber
         "Coverage is incomplete or unknown. Recorded values are shown; pace estimates are unavailable."])]
     [:div {:class "grid grid-cols-1 gap-4 border-t border-dark pt-4 sm:grid-cols-3 lg:border-t-0 lg:pt-0 lg:items-center"}
      (cond
        (and open? (not best?))
        [:<>
         (stat "Still to go" (amount m remaining) "No deadline")
         (stat "Average per completed day" (rate m average)
               (str "Since " (short-date (:goal/starts-on goal)) " · "
                    completed-days " completed days"))
         (stat "Next milestone" (if next (amount m (- next (or logged 0))) "—")
               (when next (str "to " (amount m next))))]

        best?
        [:<>
         (stat "Best so far" (amount m logged) "Within this goal period")
         (stat "To your target" (amount m remaining) "One qualifying exercise line")
         (stat "Next threshold" (if next (amount m (- next (or logged 0))) "—")
               (when next (str "to " (amount m next))))]

        :else
        [:<>
         (stat "Average per completed day" (rate m average) (str completed-days " completed days"))
         (if required
           (stat "Required from today" (rate m required)
                 (str remaining-days " days left · based on completed days"
                      (when ratio (format " · %.2f× your average" (double ratio))))
                 (if (and ratio (> ratio 1)) "text-neon-amber" "text-neon-lime"))
           (stat "Required from today" "—"
                 (case status
                   :ended       "The goal period has ended"
                   :not-started "Not started yet"
                   (if (= :unknown (:coverage progress)) "Coverage unknown" "Target reached"))))
         (stat "Next threshold" (if next (amount m (- next (or logged 0))) "—")
               (when next (str "to " (amount m next))))])]]))

(defn- chart-title
  [{:keys [goal measurement progress]}]
  (let [{:keys [start end]} (:window progress)]
    (cond
      (= :best (:aggregation measurement))
      (str "Best performance · " (short-date start) " – "
           (if end (short-date end) "today"))
      (= :open-ended (:goal/timing goal))
      (str "Cumulative progress · " (short-date start) " – today · no deadline")
      (= :weekly (:goal/timing goal))
      (str "This calendar week · " (short-date start) "–" (short-date end))
      :else (str "Cumulative progress · " (short-date start) " – " (short-date end)))))

(defn- chart-readout
  [{:keys [goal measurement progress]}]
  (cond
    (= :best (:aggregation measurement)) "Best recorded within the goal period"
    (= :open-ended (:goal/timing goal)) "Progress keeps accumulating · no reset or required pace"
    (= :weekly (:goal/timing goal)) "Each week stands on its own · no carry-forward"
    (:pace-days progress)
    (let [d (:pace-days progress)]
      (str (fmt-num (Math/abs (double d))) " days " (if (neg? d) "behind" "ahead of")
           " even pace · completed days"))
    :else ""))

(defn- history-panel
  [{:keys [goal measurement history book-progress]}]
  (let [m      (if book-progress dashboard/reading-duration measurement)
        cells  history
        first* (:date (first cells))
        last*  (:date (last cells))
        mid    (:date (nth cells (quot (count cells) 2) nil))]
    [:section.min-w-0.p-4.sm:p-6
     [:div.mb-3.flex.items-baseline.justify-between.gap-3
      [:div
       [:h3.text-sm.font-semibold.text-white "Keep coming back"]
       [:p.mt-1.text-xs.text-gray-400
        (str (short-date first*) " – " (short-date last*) " · one column per day")]]
      [:span.text-xs.text-gray-400 "12 WEEKS"]]
     [:div {:class "grid gap-px grid-cols-[repeat(84,minmax(0,1fr))]"
            :role "list" :aria-label (str (:goal/label goal) " daily activity")}
      (for [{:keys [date value]} cells]
        (let [txt (str (short-date date) ": "
                       (if (and value (pos? value)) (amount m value) "no log"))]
          [:span {:key (str date) :role "listitem" :title txt :aria-label txt
                  :class (str "h-5 rounded-sm "
                              (if (and value (pos? value)) "bg-neon-cyan opacity-75" "bg-dark"))}]))]
     [:div.mt-1.flex.justify-between.text-xs.text-gray-500
      [:span (short-date first*)] [:span (short-date mid)] [:span (short-date last*)]]
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
         (:goal/time-zone goal) ". Rates use completed days through "
         (long-date (calc/plus-days cutoff-date -1))
         "; today's activity is excluded from pace calculations."
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
  "The selected goal's card. `#goal-detail` is what a row click swaps in."
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
               (short-date (:goal/starts-on (:goal entry))) " – "
               (if-let [e (get-in entry [:goal :goal/ends-on])] (short-date e) "today"))]
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
;; Handlers
;; ---------------------------------------------------------------------------

(defn goals-page
  "GET /app/goals — `?goal=<id>` selects the detail card."
  [{:keys [session biff/db params] :as ctx}]
  (let [now      (java.time.Instant/now)
        data     (dashboard/dashboard db (:uid session)
                                      {:now           now
                                       :user-settings (queries/resolve-user-settings ctx)})
        entries  (sort-by (comp str/lower-case :goal/label :goal) (:active data))
        want     (some-> (:goal params) parse-uuid)
        selected (or (some #(when (= want (get-in % [:goal :xt/id])) %) entries)
                     (first entries))]
    (ui/page
     (assoc ctx ::ui/echarts true)
     (layout/page-shell
      ctx {:width :wide}
      (layout/page-header
       {:title    "Goals"
        :subtitle "A little closer, every time."
        :actions  [:a.form-button-primary {:href "/app/goals/new"} "+ New goal"]})
      [:p.text-xs.text-gray-400
       "Totals include today · rates use completed days · each goal uses its saved time zone"]
      (if (empty? entries)
        (layout/empty-state
         {:message "No goals yet. A goal counts what you already log — time, sessions, reps, or finishing a book."
          :action  [:a.form-button-primary {:href "/app/goals/new"} "Create your first goal"]})
        [:div.space-y-4
         (goals-table entries selected)
         [:div#goal-detail-mount (goal-detail selected (:cutoff-date selected))]])
      (archived-section (:archived data))
      (when (pos? (:hidden-count data))
        [:p.text-xs.text-gray-500
         (str (:hidden-count data) " goal(s) hidden by your sensitive/archived settings.")])))))

(defn- owned-goal
  [{:keys [biff/db session path-params]}]
  (when-let [id (some-> (:id path-params) parse-uuid)]
    (queries/get-entity-for-user db id (:uid session) :goal)))

(defn- back-to
  [goal]
  {:status 303 :headers {"location" (str "/app/goals" (when goal (str "?goal=" (:xt/id goal))))}})

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
    ["/editor" {:get editor/refresh}]]
   ["/goal/:id" {}
    ["" {:post editor/update!}]
    ["/edit" {:get editor/edit-page}]
    ["/preferences" {:post save-preferences!}]
    ["/archive" {:post set-archived!}]
    ["/delete" {:post delete!}]]])
