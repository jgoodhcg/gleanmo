(ns tech.jgood.gleanmo.app.habit-log
  (:require
   [cheshire.core :as json]
   [clojure.set :as set]
   [clojure.string :as str]
   [clojure.pprint :refer [pprint]]
   [potpuri.core :as pot]
   [tech.jgood.gleanmo.app.shared :refer [param-true? side-bar]]
   [tech.jgood.gleanmo.crud.routes :as crud]
   [tech.jgood.gleanmo.db.queries :as queries]
   [tech.jgood.gleanmo.prediction.heuristics :as pred]
   [tech.jgood.gleanmo.schema :refer [schema]]
   [tech.jgood.gleanmo.schema.habit-schema :as hc]
   [tech.jgood.gleanmo.ui :as ui]
   [tech.jgood.gleanmo.viz.routes :as viz-routes]
   [tick.core :as t])
  (:import
   [java.util UUID]))

(def crud-routes
  (crud/gen-routes {:entity-key :habit-log,
                    :entity-str "habit-log",
                    :plural-str "habit-logs",
                    :schema     schema}))

(defn habit-dates
  [{:keys [session biff/db params],
    :as   context}]
  ;; Retrieve the user entity, including the time zone
  (let [user-id         (:uid session)
        {:user/keys [time-zone]}
        (queries/get-entity-by-id db user-id)
        sensitive       (some-> params
                                :sensitive
                                param-true?)
        archived        (some-> params
                                :archived
                                param-true?)
        habit-id        (when-not (str/blank? (:habit-id params))
                          (UUID/fromString (:habit-id params)))
        habit           (when habit-id
                          (queries/get-entity-by-id db habit-id))
        all-habit-logs  (->> (queries/all-for-user-query
                              {:entity-type-str "habit-log",
                               :schema hc/habit-log,
                               :filter-references true}
                              (merge context (pot/map-of sensitive archived)))
                             (sort-by :habit-log/timestamp)
                             (filter (fn [log]
                                       (when habit-id
                                         (contains? (:habit-log/habit-ids log)
                                                    habit-id))))
                             reverse)
        all-habits      (->> (queries/all-for-user-query
                              {:entity-type-str "habit",
                               :schema hc/habit,
                               :filter-references false}
                              (merge context (pot/map-of sensitive archived))))
        ;; Extract dates of habit logs
        habit-log-dates (->>
                         all-habit-logs
                         (map
                          (fn [item]
                            (let [timestamp      (:habit-log/timestamp item)
                                  item-time-zone (or (:habit-log/time-zone
                                                      item)
                                                     time-zone)
                                  zoned-date     (-> timestamp
                                                     (t/in (t/zone
                                                            item-time-zone)))
                                  formatted-date (t/format
                                                  (t/formatter "yyyy-MM-dd")
                                                  (t/date zoned-date))]
                              {:date formatted-date,
                               :id   (:xt/id item)})))
                         vec)
        dates-only-text (->> habit-log-dates
                             (map :date)
                             (str/join "\n"))
        dates-only      (->> habit-log-dates
                             (map :date)
                             vec)
        ;; Generate predictions if we have enough dates
        predictions     (when (and habit-id (>= (count dates-only) 2))
                          (pred/get-predictions dates-only))
        has-predictions (and predictions (seq predictions))
        base-url        "/app/dv/habit-dates"]
    ;; Render the page with list of dates when habit was logged
    (ui/page
     {}
     (side-bar
      context
      [:div.flex.flex-col
       [:h1.text-2xl.font-bold.mb-4 "Habit Log Dates"]

         ;; Habit selection dropdown
       [:div.mb-6
        [:label.block.text-sm.font-medium.text-gray-400 "Select a habit:"]
        [:div.mt-1
         [:select.form-select
          {:onchange "window.location.href=this.value;"}
          [:option {:value (str base-url)} "-- Select habit --"]
          (for [{id :xt/id, name :habit/label} all-habits]
            [:option
             {:value    (str base-url
                             "?habit-id="
                             id
                             (when sensitive "&sensitive=true")
                             (when archived "&archived=true")),
              :selected (= id habit-id)}
             name])]]]

         ;; Display habit name if selected
       (when habit
         [:div.mb-6
          [:h2.text-xl.font-semibold (:habit/name habit)]])

         ;; Statistics and Predictions
       (when (and habit-id has-predictions)
         [:div.bg-dark-surface.rounded-lg.p-4.mb-6.border.border-dark
          [:h3.text-lg.font-medium.text-white.mb-3 "Predicted Next Dates"]

          [:div.space-y-3
           (for [{:keys [date description]} predictions]
             [:div.border-l-4.border-neon-lime.pl-4.py-2.bg-dark-light.rounded-r-md
              [:p.text-sm.font-medium.text-neon-lime date]
              [:p.text-xs.text-gray-400 description]])]

          [:h3.text-lg.font-medium.text-white.my-2 "Habit Statistics"]
          (let [avg-interval (pred/calculate-average-interval dates-only)
                most-common  (pred/calculate-most-common-interval dates-only)
                weekly-day   (pred/detect-weekly-pattern dates-only)
                monthly-day  (pred/detect-monthly-pattern dates-only)]
            [:div.grid.grid-cols-1.gap-3.md:grid-cols-2
             (when avg-interval
               [:div.bg-dark-surface.p-3.rounded.border.border-dark
                [:p.text-xs.uppercase.text-gray-400 "Average Interval"]
                [:p.text-lg.font-medium.text-white
                 (if (= 1 avg-interval)
                   "Daily"
                   (if (= 7 avg-interval)
                     "Weekly"
                     (str avg-interval " days")))]])
             (when most-common
               [:div.bg-dark-surface.p-3.rounded.border.border-dark
                [:p.text-xs.uppercase.text-gray-400 "Most Frequent Interval"]
                [:p.text-lg.font-medium.text-white
                 (if (= 1 most-common)
                   "Daily"
                   (if (= 7 most-common)
                     "Weekly"
                     (str most-common " days")))]])
             (when weekly-day
               [:div.bg-dark-surface.p-3.rounded.border.border-dark
                [:p.text-xs.uppercase.text-gray-400 "Weekly Pattern"]
                [:p.text-lg.font-medium.text-white
                 (case (.getValue weekly-day)
                   1 "Every Monday"
                   2 "Every Tuesday"
                   3 "Every Wednesday"
                   4 "Every Thursday"
                   5 "Every Friday"
                   6 "Every Saturday"
                   7 "Every Sunday")]])
             (when monthly-day
               [:div.bg-dark-surface.p-3.rounded.border.border-dark
                [:p.text-xs.uppercase.text-gray-400 "Monthly Pattern"]
                [:p.text-lg.font-medium.text-white
                 (str "Day " monthly-day " of each month")]])])])

         ;; Copy dates button (only visible when habit is selected and
         ;; dates exist)
       (when (and habit-id (seq habit-log-dates))
         [:div.mb-4
          [:button.bg-neon-cyan.hover:bg-opacity-80.text-black.font-bold.py-2.px-4.rounded.flex.items-center
           {:id      "copy-dates-btn",
            :onclick (str "copyToClipboard(`" dates-only-text "`)")}
           [:svg.w-4.h-4.mr-2
            {:xmlns   "http://www.w3.org/2000/svg",
             :fill    "none",
             :viewBox "0 0 24 24",
             :stroke  "currentColor"}
            [:path
             {:stroke-linecap "round",
              :stroke-linejoin "round",
              :stroke-width "2",
              :d
              "M8 5H6a2 2 0 00-2 2v12a2 2 0 002 2h10a2 2 0 002-2v-1M8 5a2 2 0 002 2h2a2 2 0 002-2M8 5a2 2 0 012-2h2a2 2 0 012 2m0 0h2a2 2 0 012 2v3m2 4H10m0 0l3-3m-3 3l3 3"}]]
           "Copy Dates"]])

         ;; Hidden textarea containing just the dates for copying
         ;; (invisible element)
       (when (and habit-id (seq habit-log-dates))
         [:textarea.hidden#dates-text dates-only-text])

         ;; List of dates
       (if (and habit-id (seq habit-log-dates))
         [:div.table-container
          [:table.min-w-full.table-fixed
           [:thead.table-header
            [:tr
             [:th.table-header-cell "Date"]
             [:th.table-header-cell "Action"]]]
           [:tbody.table-body
            (for [{:keys [date id]} habit-log-dates]
              [:tr.table-row
               [:td.table-cell date]
               [:td.table-cell
                [:a.text-neon-cyan.hover:text-white
                 {:href (str "/app/crud/form/habit-log/edit/" id)}
                 "View Details"]]])]]]

           ;; No habit selected or no dates found
         [:div.text-center.py-8.text-gray-400
          (if habit-id
            "No log entries found for this habit."
            "Please select a habit to view log dates.")])]))))

;; Generate visualization routes
(def viz-routes
  (viz-routes/gen-routes {:entity-key :habit-log
                          :entity-schema hc/habit-log  
                          :entity-str "habit-log"
                          :plural-str "habit-logs"}))