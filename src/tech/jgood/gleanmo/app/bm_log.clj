(ns tech.jgood.gleanmo.app.bm-log
  (:require
   [clojure.pprint :refer [pprint]]
   [potpuri.core :as pot]
   [tech.jgood.gleanmo.app.shared :refer [side-bar]]
   [tech.jgood.gleanmo.crud.routes :as crud]
   [tech.jgood.gleanmo.db.queries :as queries]
   [tech.jgood.gleanmo.schema :refer [schema]]
   [tech.jgood.gleanmo.schema.bm-schema :as bm-schema]
   [tech.jgood.gleanmo.ui :as ui]
   [tech.jgood.gleanmo.viz.routes :as viz-routes]
   [tick.core :as t]))

(def crud-routes
  (crud/gen-routes {:entity-key :bm-log,
                    :entity-str "bm-log",
                    :plural-str "bm logs",
                    :schema     schema}))

;; Generate visualization routes  
(def viz-routes
  (viz-routes/gen-routes {:entity-key :bm-log
                          :entity-schema bm-schema/bm-log
                          :entity-str "bm-log" 
                          :plural-str "bm-logs"}))

(defn bristol-chart
  "Renders a simple bar chart for Bristol type counts with stacked segments"
  [bristol-counts]
  (let [types          [[:b1 "1"] [:b2 "2"] [:b3 "3"] [:b4 "4"] [:b5 "5"]
                        [:b6 "6"] [:b7 "7"]]
        segment-height 8 ; Height per occurrence in pixels
        bristol-colors {:b1 "bg-neon-azure",
                        :b2 "bg-neon-azure",
                        :b3 "bg-neon-azure",
                        :b4 "bg-neon-lime",
                        :b5 "bg-neon-azure",
                        :b6 "bg-neon-azure",
                        :b7 "bg-neon-pink"}]
    [:div.mt-3
     [:p.text-xs.text-gray-400.mb-1 "Bristol Types:"]
     [:div
      {:class "flex gap-1 items-end", :style {:min-height "8rem"}}
      (for [[type-key label] types]
        (let [count (get bristol-counts type-key 0)
              color (get bristol-colors type-key "bg-gray-400")]
          [:div.flex-1.flex.flex-col.items-center.justify-end
           {:key type-key}
           ;; Stack of segments for each occurrence
           [:div.w-full.flex.flex-col-reverse.gap-1
            (for [i (range count)]
              [:div.w-full.opacity-70.h-1
               {:key i, :class color}])]
           [:p.text-xs.text-gray-500.mt-1
            label]]))]]))

(defn bm-stats
  [{:keys [session biff/db], :as context}]
  (let [user-id            (:uid session)
        {:user/keys [time-zone]} (queries/get-entity-by-id db user-id)
        ;; Get all BM logs for the user
        all-bm-logs        (->> (queries/all-for-user-query
                                 {:entity-type-str "bm-log",
                                  :schema bm-schema/bm-log,
                                  :filter-references false}
                                 context)
                                (sort-by :bm-log/timestamp)
                                reverse)

        ;; Get current date and filter by days
        now                (t/instant)
        filter-by-days     (fn [days logs]
                             (let [cutoff (t/>> now
                                                (t/new-duration (- days)
                                                                :days))]
                               (filter (fn [log]
                                         (when-let [timestamp (:bm-log/timestamp
                                                               log)]
                                           (t/> timestamp cutoff)))
                                       logs)))

        logs-7             (filter-by-days 7 all-bm-logs)
        logs-14            (filter-by-days 14 all-bm-logs)
        logs-28            (filter-by-days 28 all-bm-logs)

        count-7            (count logs-7)
        count-14           (count logs-14)
        count-28           (count logs-28)

        ;; Bristol type counts
        get-bristol-counts (fn [logs]
                             (let [counts (frequencies (map :bm-log/bristol
                                                            logs))]
                               {:b1 (get counts :b1-hard-clumps 0),
                                :b2 (get counts :b2-lumpy-log 0),
                                :b3 (get counts :b3-cracked-log 0),
                                :b4 (get counts :b4-smooth-log 0),
                                :b5 (get counts :b5-soft-blobs 0),
                                :b6 (get counts :b6-mushy-ragged 0),
                                :b7 (get counts :b7-liquid 0)}))

        bristol-7          (get-bristol-counts logs-7)
        bristol-14         (get-bristol-counts logs-14)
        bristol-28         (get-bristol-counts logs-28)

        ;; All-time calculations
        count-all          (count all-bm-logs)
        bristol-all        (get-bristol-counts all-bm-logs)

        ;; Calculate time span representation and per-day average for
        ;; all-time data
        all-time-data      (when (seq all-bm-logs)
                             (let [oldest-log       (last all-bm-logs)
                                   oldest-timestamp (:bm-log/timestamp
                                                     oldest-log)
                                   days-span        (t/days (t/between
                                                             oldest-timestamp
                                                             now))
                                   per-day          (if (> days-span 0)
                                                      (double (/ count-all
                                                                 days-span))
                                                      0.0)]
                               {:time-span-repr (cond
                                                  (< days-span 7) (str days-span
                                                                       " days")
                                                  (< days-span 30)
                                                  (str (Math/round
                                                        (/ days-span 7.0))
                                                       " weeks")
                                                  (< days-span 365)
                                                  (str (Math/round
                                                        (/ days-span 30.4))
                                                       " months")
                                                  :else (str (format "%.1f"
                                                                     (/
                                                                      days-span
                                                                      365.0))
                                                             " years")),
                                :per-day        (format "%.2f" per-day)}))]

    (ui/page
     {}
     (side-bar
      context
      [:div.flex.flex-col
       [:h1.text-2xl.font-bold.mb-6 "BM Log Statistics"]

         ;; Simple Frequency Statistics
       [:div.mb-8
        [:h2.text-xl.font-semibold.mb-4.text-neon "BM Log Frequency"]
          ;; First row: 7, 14, 28 days
        [:div.grid.grid-cols-1.md:grid-cols-3.gap-4.mb-4

         [:div.bg-dark-light.p-4.rounded-lg.border.border-neon
          [:h3.text-lg.font-medium.text-neon "Last 7 Days"]
          [:p.text-3xl.font-bold.text-white count-7]
          [:p.text-sm.text-gray-400
           (str (format "%.1f" (/ count-7 7.0)) " per day")]
          (bristol-chart bristol-7)]

         [:div.bg-dark-light.p-4.rounded-lg.border.border-neon
          [:h3.text-lg.font-medium.text-neon "Last 14 Days"]
          [:p.text-3xl.font-bold.text-white count-14]
          [:p.text-sm.text-gray-400
           (str (format "%.1f" (/ count-14 14.0)) " per day")]
          (bristol-chart bristol-14)]

         [:div.bg-dark-light.p-4.rounded-lg.border.border-neon
          [:h3.text-lg.font-medium.text-neon "Last 28 Days"]
          [:p.text-3xl.font-bold.text-white count-28]
          [:p.text-sm.text-gray-400
           (str (format "%.1f" (/ count-28 28.0)) " per day")]
          (bristol-chart bristol-28)]]

          ;; Second row: All Time (larger card)
        [:div.grid.grid-cols-1.lg:grid-cols-2.gap-4
         [:div.bg-dark-light.p-6.rounded-lg.border.border-neon.lg:col-span-2
          [:h3.text-xl.font-medium.text-neon "All Time"]
          [:div.flex.flex-col.lg:flex-row.lg:items-start.lg:justify-between
           [:div.mb-4.lg:mb-0
            [:p.text-4xl.font-bold.text-white count-all]
            [:p.text-sm.text-gray-400
             (or (:time-span-repr all-time-data) "No data")]
            [:p.text-sm.text-gray-400
             (str (:per-day all-time-data) " per day")]]
           [:div.flex-1.lg:ml-8.lg:max-w-md
            (bristol-chart bristol-all)]]]]]

         ;; Link to CRUD interface
       [:div.mt-8
        [:a.text-neon.hover:text-white.underline
         {:href "/app/crud/list/bm-log"}
         "View all BM logs →"]]]))))
