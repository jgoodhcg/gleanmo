(ns tech.jgood.gleanmo.app.medication-history
  "Medication dosage history page — filter by medication and date range,
   view summary stats, dosage timeline chart, and a filtered log table."
  (:require
   [cheshire.core :as json]
   [clojure.string :as str]
   [tech.jgood.gleanmo.app.shared :refer [get-user-time-zone side-bar]]
   [tech.jgood.gleanmo.db.queries :as db]
   [tech.jgood.gleanmo.schema.medication-schema :as med-schema]
   [tech.jgood.gleanmo.ui :as ui]
   [tick.core :as t])
  (:import
   [java.time ZoneId]))

;; ---------------------------------------------------------------------------
;; Date helpers (mirror meditation-log pattern)
;; ---------------------------------------------------------------------------

(defn- date-str->instant
  "Convert YYYY-MM-DD to an instant at the start of that day in the given zone."
  [date-str zone-id]
  (when (and date-str (not-empty date-str))
    (-> (t/date date-str)
        (t/at (t/midnight))
        (t/in zone-id)
        t/instant)))

(defn- date-str->end-of-day-instant
  "Convert YYYY-MM-DD to an instant at 23:59:59 of that day in the given zone."
  [date-str zone-id]
  (when (and date-str (not-empty date-str))
    (-> (t/date date-str)
        (t/at (t/new-time 23 59 59))
        (t/in zone-id)
        t/instant)))

(defn- preset->dates
  "Turn a preset keyword (\"7d\", \"30d\", \"90d\", \"1yr\") into [start-date-str nil].
   Returns nil for unknown presets."
  [preset zone-id]
  (when (and preset (not-empty preset))
    (let [today    (java.time.LocalDate/now zone-id)
          start-ld (case preset
                     "7d"  (.minusDays today 7)
                     "30d" (.minusDays today 30)
                     "90d" (.minusDays today 90)
                     "1yr" (.minusYears today 1)
                     nil)]
      (when start-ld
        [(str start-ld) (str today)]))))

;; ---------------------------------------------------------------------------
;; Chart config
;; ---------------------------------------------------------------------------

(defn- generate-dosage-chart-config
  "Build an ECharts scatter+line config showing dosage over time."
  [logs medication-label unit-label zone-id]
  (let [sorted-logs (sort-by :medication-log/timestamp logs)
        data        (mapv (fn [log]
                            [(-> (:medication-log/timestamp log)
                                 (t/in zone-id)
                                 (->> (t/format (t/formatter "yyyy-MM-dd HH:mm"))))
                             (double (:medication-log/dosage log))])
                          sorted-logs)]
    {:backgroundColor "#0d1117"
     :title  {:text      (str medication-label " — Dosage Over Time")
              :left      "center"
              :textStyle {:color "#c9d1d9" :fontSize 16 :fontWeight "bold"}}
     :tooltip {:trigger         "axis"
               :backgroundColor "rgba(22, 27, 34, 0.95)"
               :borderColor     "#30363d"
               :textStyle       {:color "#c9d1d9"}}
     :grid {:left "12%" :right "5%" :bottom "15%" :top "15%"}
     :xAxis {:type      "category"
             :data      (mapv first data)
             :axisLabel {:color    "#8b949e"
                         :fontSize 10
                         :rotate   45}
             :axisLine  {:lineStyle {:color "#30363d"}}}
     :yAxis {:type      "value"
             :name      (str "Dosage (" (name unit-label) ")")
             :nameTextStyle {:color "#8b949e" :fontSize 12}
             :axisLabel {:color "#8b949e"}
             :axisLine  {:lineStyle {:color "#30363d"}}
             :splitLine {:lineStyle {:color "#21262d"}}}
     :series [{:type       "line"
               :data       (mapv second data)
               :smooth     true
               :symbol     "circle"
               :symbolSize 8
               :lineStyle  {:color "#32cd32" :width 2}
               :itemStyle  {:color "#32cd32"
                            :borderColor "#0d1117"
                            :borderWidth 2}
               :areaStyle  {:color {:type       "linear"
                                    :x 0 :y 0 :x2 0 :y2 1
                                    :colorStops [{:offset 0 :color "rgba(50,205,50,0.25)"}
                                                 {:offset 1 :color "rgba(50,205,50,0.02)"}]}}}]}))

(defn- generate-mobile-dosage-chart-config
  "Mobile-optimised variant — taller, no label rotation."
  [logs medication-label unit-label zone-id]
  (let [base (generate-dosage-chart-config logs medication-label unit-label zone-id)]
    (-> base
        (assoc-in [:title :textStyle :fontSize] 14)
        (assoc-in [:grid :left] "15%")
        (assoc-in [:grid :bottom] "20%")
        (assoc-in [:xAxis :axisLabel :rotate] 60)
        (assoc-in [:xAxis :axisLabel :fontSize] 9))))

;; ---------------------------------------------------------------------------
;; Stat cards
;; ---------------------------------------------------------------------------

(defn- stat-card
  "Render a single dark-surface stat card."
  [label value & [sub-value]]
  [:div.bg-dark-surface.p-6.rounded-lg.border.border-dark
   [:h3.text-sm.font-medium.text-gray-400 label]
   [:p.text-3xl.font-bold.text-white value]
   (when sub-value
     [:p.text-sm.text-gray-400 sub-value])])

(defn- stat-card-neon
  "Stat card with neon-lime accent value."
  [label value & [sub-value]]
  [:div.bg-dark-surface.p-6.rounded-lg.border.border-dark
   [:h3.text-sm.font-medium.text-gray-400 label]
   [:p.text-3xl.font-bold {:style {:color "#32cd32"}} value]
   (when sub-value
     [:p.text-sm.text-gray-400 sub-value])])

;; ---------------------------------------------------------------------------
;; Log table
;; ---------------------------------------------------------------------------

(defn- format-timestamp
  "Format an instant to a human-readable local date-time string."
  [instant zone-id]
  (when instant
    (-> instant
        (t/in zone-id)
        (->> (t/format (t/formatter "yyyy-MM-dd HH:mm"))))))

(defn- log-table
  "Render a table of medication log entries."
  [logs zone-id]
  (if (empty? logs)
    [:p.text-gray-400.mt-4 "No logs found for this selection."]
    [:div.table-container.mt-6
     [:table.min-w-full
      [:thead.table-header
       [:tr
        [:th.table-header-cell "Date"]
        [:th.table-header-cell "Dosage"]
        [:th.table-header-cell "Unit"]
        [:th.table-header-cell.hidden.md:table-cell "Injection Site"]
        [:th.table-header-cell.hidden.md:table-cell "Notes"]]]
      [:tbody.table-body
       (for [log (sort-by :medication-log/timestamp #(compare %2 %1) logs)]
         [:tr.table-row {:key (str (:xt/id log))}
          [:td.table-cell (format-timestamp (:medication-log/timestamp log) zone-id)]
          [:td.table-cell (str (:medication-log/dosage log))]
          [:td.table-cell (some-> (:medication-log/unit log) name)]
          [:td.table-cell.hidden.md:table-cell
           (some-> (:medication-log/injection-site log) name (str/replace "-" " "))]
          [:td.table-cell.hidden.md:table-cell
           (or (:medication-log/notes log) "")]])]]]))

;; ---------------------------------------------------------------------------
;; Main page handler
;; ---------------------------------------------------------------------------

(defn medication-history-page
  "GET /app/medication-history — full page with medication picker, date range,
   stats, dosage chart, and filtered log table."
  [{:keys [params] :as ctx}]
  (let [time-zone      (get-user-time-zone ctx)
        zone-id        (ZoneId/of (or time-zone "US/Eastern"))

        ;; Resolve date params — preset overrides manual dates
        preset         (:preset params)
        [preset-start preset-end] (preset->dates preset zone-id)
        start-date-str (or preset-start (:start-date params))
        end-date-str   (or preset-end (:end-date params))
        start-instant  (date-str->instant start-date-str zone-id)
        end-instant    (date-str->end-of-day-instant end-date-str zone-id)

        ;; Fetch all medications for the picker
        medications    (sort-by :medication/label
                                (db/all-for-user-query
                                 {:entity-type-str "medication"
                                  :schema          med-schema/medication}
                                 ctx))

        ;; Selected medication
        med-id-str     (:medication-id params)
        selected-med   (when (and med-id-str (not-empty med-id-str))
                         (try
                           (let [med-uuid (java.util.UUID/fromString med-id-str)]
                             (some #(when (= (:xt/id %) med-uuid) %) medications))
                           (catch Exception _ nil)))

        ;; Fetch and filter medication logs when a medication is selected
        all-logs       (when selected-med
                         (db/all-for-user-query
                          {:entity-type-str "medication-log"
                           :schema          med-schema/medication-log
                           :filter-references true}
                          ctx))
        filtered-logs  (when selected-med
                         (cond->> all-logs
                           ;; Filter to selected medication
                           true          (filter #(= (:medication-log/medication-id %)
                                                     (:xt/id selected-med)))
                           ;; Date range
                           start-instant (filter #(t/>= (:medication-log/timestamp %)
                                                        start-instant))
                           end-instant   (filter #(t/<= (:medication-log/timestamp %)
                                                        end-instant))))

        ;; Compute stats
        total-doses    (count (or filtered-logs []))
        dosages        (when (seq filtered-logs)
                         (map :medication-log/dosage filtered-logs))
        avg-dosage     (when (seq dosages)
                         (format "%.2f" (double (/ (reduce + dosages) (count dosages)))))
        primary-unit   (when (seq filtered-logs)
                         (->> filtered-logs
                              (map :medication-log/unit)
                              frequencies
                              (sort-by val >)
                              ffirst))
        most-recent    (when (seq filtered-logs)
                         (->> filtered-logs
                              (sort-by :medication-log/timestamp)
                              last
                              :medication-log/timestamp
                              (#(format-timestamp % zone-id))))
        ;; Days spanned
        days-spanned   (when (>= total-doses 2)
                         (let [sorted (sort-by :medication-log/timestamp filtered-logs)
                               first-ts (:medication-log/timestamp (first sorted))
                               last-ts  (:medication-log/timestamp (last sorted))]
                           (max 1 (t/days (t/duration {:tick/beginning first-ts
                                                       :tick/end       last-ts})))))

        ;; Active preset for button highlighting
        active-preset  (when preset preset)
        filtering?     (or start-instant end-instant)]

    (ui/page
     (assoc ctx ::ui/echarts (boolean selected-med))
     (side-bar
      ctx
      [:div.flex.flex-col.max-w-4xl.mx-auto
       [:h1.text-2xl.font-bold.mb-6 "Medication History"]

       ;; ── Filter form ──────────────────────────────────────────────
       [:div.bg-dark-surface.p-6.rounded-lg.border.border-dark.mb-6
        [:form {:method "get" :action "/app/medication-history"}
         [:div.flex.flex-col.space-y-4

          ;; Medication picker
          [:div
           [:label.form-label {:for "medication-id"} "Medication"]
           [:select.form-select
            {:name          "medication-id"
             :data-enhance  "choices"
             :data-placeholder "Select a medication..."
             :data-allow-clear "true"}
            [:option {:value ""} ""]
            (for [med medications]
              [:option {:value    (str (:xt/id med))
                        :selected (= (:xt/id med) (:xt/id selected-med))}
               (:medication/label med)])]]

          ;; Date range
          [:div.grid.grid-cols-1.md:grid-cols-2.gap-4
           [:div
            [:label.form-label {:for "start-date"} "Start Date"]
            [:input.form-input {:type "date" :name "start-date" :value start-date-str}]]
           [:div
            [:label.form-label {:for "end-date"} "End Date"]
            [:input.form-input {:type "date" :name "end-date" :value end-date-str}]]]

          ;; Preset buttons + actions
          [:div.flex.flex-wrap.items-center.gap-2
           (for [[label preset-val] [["7d" "7d"] ["30d" "30d"] ["90d" "90d"] ["1yr" "1yr"]]]
             [:a {:href  (str "/app/medication-history?"
                              (when selected-med (str "medication-id=" (:xt/id selected-med) "&"))
                              "preset=" preset-val)
                  :class (str "px-3 py-1 rounded text-sm font-medium border transition-all duration-200 "
                              (if (= active-preset preset-val)
                                "border-neon-gold bg-dark text-gold"
                                "border-dark bg-dark-surface text-gray-300 hover:border-gold hover:text-gold"))}
              label])
           [:div.flex-grow]
           [:button.form-button-primary.font-bold {:type "submit"} "Apply"]
           [:a.form-button-secondary.text-center
            {:href "/app/medication-history"} "Clear"]]]]]

       ;; ── Results ──────────────────────────────────────────────────
       (when selected-med
         [:div#results-container

          ;; Filter status
          (when filtering?
            [:div.bg-dark-surface.border-l-4.p-4.mb-6
             {:style {:border-left-color "#32cd32"}}
             [:p {:style {:color "#32cd32"}}
              "Showing "
              (cond
                (and start-date-str end-date-str)
                (str "logs from " start-date-str " to " end-date-str)
                start-date-str (str "logs from " start-date-str)
                end-date-str   (str "logs until " end-date-str))]])

          ;; Stat cards
          [:div.grid.grid-cols-2.md:grid-cols-4.gap-4.mb-6
           (stat-card "Total Doses" (str total-doses))
           (stat-card-neon "Avg Dosage"
                           (or avg-dosage "—")
                           (when primary-unit (name primary-unit)))
           (stat-card "Most Recent" (or most-recent "—"))
           (stat-card "Days Spanned"
                      (if days-spanned (str days-spanned) "—")
                      (when days-spanned
                        (format "(%s doses/day)"
                                (format "%.1f" (double (/ total-doses days-spanned))))))]

          ;; Dosage chart (only when we have data)
          (when (>= total-doses 2)
            (let [med-label    (:medication/label selected-med)
                  unit         (or primary-unit :mg)
                  desktop-cfg  (generate-dosage-chart-config filtered-logs med-label unit zone-id)
                  mobile-cfg   (generate-mobile-dosage-chart-config filtered-logs med-label unit zone-id)]
              [:div.mb-6
               ;; Desktop
               [:div.hidden.md:block
                [:div {:id    "dosage-chart-desktop"
                       :style {:height "350px" :width "100%"}
                       :data-chart-data "dosage-chart-desktop-data"}]
                [:div#dosage-chart-desktop-data.hidden
                 (json/generate-string desktop-cfg)]]
               ;; Mobile
               [:div.block.md:hidden
                [:div {:id    "dosage-chart-mobile"
                       :style {:height "300px" :width "100%"}
                       :data-chart-data "dosage-chart-mobile-data"}]
                [:div#dosage-chart-mobile-data.hidden
                 (json/generate-string mobile-cfg)]]]))

          ;; Log table
          [:h2.text-lg.font-semibold.mt-6.mb-2
           (str (:medication/label selected-med) " Logs"
                (when filtering? (str " (" total-doses ")")))]
          (log-table filtered-logs zone-id)])]))))

;; ---------------------------------------------------------------------------
;; Routes
;; ---------------------------------------------------------------------------

(def routes
  ["/medication-history" {:get medication-history-page}])
