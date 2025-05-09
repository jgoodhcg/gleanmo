(ns tech.jgood.gleanmo.app.habit-log
  (:require
   [cheshire.core :as json]
   [clojure.set :as set]
   [clojure.string :as str]
   [clojure.pprint :refer [pprint]]
   [potpuri.core :as pot]
   [tech.jgood.gleanmo.app.shared :refer [param-true? side-bar]]
   [tech.jgood.gleanmo.crud.routes :as crud]
   [tech.jgood.gleanmo.db.queries :as db]
   [tech.jgood.gleanmo.prediction.heuristics :as pred]
   [tech.jgood.gleanmo.schema :refer [schema]]
   [tech.jgood.gleanmo.schema.habit-schema :as hc]
   [tech.jgood.gleanmo.ui :as ui]
   [tick.core :as t]
   [xtdb.api :as xt])
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
  (let [{:user/keys [email time-zone]}
        (xt/entity db (:uid session))
        sensitive       (some-> params
                                :sensitive
                                param-true?)
        archived        (some-> params
                                :archived
                                param-true?)
        habit-id        (when-not (str/blank? (:habit-id params))
                          (UUID/fromString (:habit-id params)))
        habit           (when habit-id
                          (xt/entity db habit-id))
        all-habit-logs  (->> (db/all-for-user-query
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
        all-habits      (->> (db/all-for-user-query
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
    (pprint (pot/map-of all-habits))
    (ui/page
     {}
     (side-bar
      (pot/map-of email)
      [:div.flex.flex-col
       [:h1.text-2xl.font-bold.mb-4 "Habit Log Dates"]

         ;; Habit selection dropdown
       [:div.mb-6
        [:label.block.text-sm.font-medium.text-gray-700 "Select a habit:"]
        [:div.mt-1
         [:select.block.w-full.rounded-md.border-gray-300.shadow-sm.focus:border-blue-500.focus:ring.focus:ring-blue-500.focus:ring-opacity-50
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
         [:div.bg-gray-50.rounded-lg.p-4.mb-6
          [:h3.text-lg.font-medium.text-gray-900.mb-3 "Predicted Next Dates"]

          [:div.space-y-3
           (for [{:keys [date description]} predictions]
             [:div.border-l-4.border-blue-500.pl-4.py-2.bg-blue-50.rounded-r-md
              [:p.text-sm.font-medium.text-blue-800 date]
              [:p.text-xs.text-gray-600 description]])]

          [:h3.text-log.font-medium.text-gray-800.my-2 "Habit Statistics"]
          (let [avg-interval (pred/calculate-average-interval dates-only)
                most-common  (pred/calculate-most-common-interval dates-only)
                weekly-day   (pred/detect-weekly-pattern dates-only)
                monthly-day  (pred/detect-monthly-pattern dates-only)]
            [:div.grid.grid-cols-1.gap-3.md:grid-cols-2
             (when avg-interval
               [:div.bg-white.p-3.rounded.shadow-sm
                [:p.text-xs.uppercase.text-gray-500 "Average Interval"]
                [:p.text-lg.font-medium
                 (if (= 1 avg-interval)
                   "Daily"
                   (if (= 7 avg-interval)
                     "Weekly"
                     (str avg-interval " days")))]])
             (when most-common
               [:div.bg-white.p-3.rounded.shadow-sm
                [:p.text-xs.uppercase.text-gray-500 "Most Frequent Interval"]
                [:p.text-lg.font-medium
                 (if (= 1 most-common)
                   "Daily"
                   (if (= 7 most-common)
                     "Weekly"
                     (str most-common " days")))]])
             (when weekly-day
               [:div.bg-white.p-3.rounded.shadow-sm
                [:p.text-xs.uppercase.text-gray-500 "Weekly Pattern"]
                [:p.text-lg.font-medium
                 (case (.getValue weekly-day)
                   1 "Every Monday"
                   2 "Every Tuesday"
                   3 "Every Wednesday"
                   4 "Every Thursday"
                   5 "Every Friday"
                   6 "Every Saturday"
                   7 "Every Sunday")]])
             (when monthly-day
               [:div.bg-white.p-3.rounded.shadow-sm
                [:p.text-xs.uppercase.text-gray-500 "Monthly Pattern"]
                [:p.text-lg.font-medium
                 (str "Day " monthly-day " of each month")]])])])

         ;; Copy dates button (only visible when habit is selected and
         ;; dates exist)
       (when (and habit-id (seq habit-log-dates))
         [:div.mb-4
          [:button.bg-blue-500.hover:bg-blue-700.text-white.font-bold.py-2.px-4.rounded.flex.items-center
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
         [:div.border.rounded-lg.overflow-hidden
          [:table.min-w-full.divide-y.divide-gray-200
           [:thead.bg-gray-50
            [:tr
             [:th.px-6.py-3.text-left.text-xs.font-medium.text-gray-500.uppercase.tracking-wider
              "Date"]
             [:th.px-6.py-3.text-left.text-xs.font-medium.text-gray-500.uppercase.tracking-wider
              "Action"]]]
           [:tbody.bg-white.divide-y.divide-gray-200
            (for [{:keys [date id]} habit-log-dates]
              [:tr.hover:bg-gray-50
               [:td.px-6.py-4.whitespace-nowrap.text-sm.text-gray-900 date]
               [:td.px-6.py-4.whitespace-nowrap.text-sm.font-medium
                [:a.text-blue-600.hover:text-blue-900
                 {:href (str "/app/crud/form/habit-log/edit/" id)}
                 "View Details"]]])]]]

           ;; No habit selected or no dates found
         [:div.text-center.py-8.text-gray-500
          (if habit-id
            "No log entries found for this habit."
            "Please select a habit to view log dates.")])]))))

(defn data-viz
  [{:keys [session biff/db params],
    :as   context}]
  ;; Retrieve the user entity, including the time zone
  (let [{:user/keys [email time-zone]}
        (xt/entity db (:uid session))
        sensitive       (some-> params
                                :sensitive
                                param-true?)
        archived        (some-> params
                                :archived
                                param-true?)
        ;; Normalize :habit-ids to always be a sequence
        params-ids      (let [ids (:habit-ids params)]
                          (if (string? ids)
                            [ids] ;; Wrap single string in a vector
                            ids)) ;; Leave as-is if already a sequence
        filtered-habits (->> params-ids
                             (map (fn [s]
                                    (when (not (str/blank? s))
                                      (UUID/fromString s))))
                             (remove nil?)
                             set)
        all-habit-logs  (->> (db/all-for-user-query
                              {:entity-type-str "habit-log",
                               :schema hc/habit-log,
                               :filter-references true}
                              (merge context (pot/map-of sensitive archived)))
                             (sort-by :habit-log/timestamp)
                             reverse)
        all-habits      (->> (db/all-for-user-query
                              {:entity-type-str "habit",
                               :schema hc/habit,
                               :filter-references false}
                              (merge context (pot/map-of sensitive archived))))
        ;; Aggregate habit logs per day in the user's time zone
        counts-per-day  (->>
                         all-habit-logs
                         (filter (fn [log]
                                    ;; NOTE should this condition be moved
                                    ;; to a cond->> ?
                                   (if (seq filtered-habits)
                                     (seq (set/intersection
                                           filtered-habits
                                           (:habit-log/habit-ids log)))
                                     true)))
                          ;; Map each item to the date in the user's time
                          ;; zone
                         (map
                          (fn [item]
                            (let [timestamp      (:habit-log/timestamp item)
                                  item-time-zone (or (:habit-log/time-zone
                                                      item)
                                                     time-zone)
                                  zoned-date     (-> timestamp
                                                     (t/in (t/zone
                                                            item-time-zone)))
                                  local-date     (t/date zoned-date)]
                              local-date)))
                          ;; Count the frequency of each date
                         frequencies
                          ;; Convert to a sequence of maps with 'date' and
                          ;; 'value'
                         (map (fn [[date count]]
                                {:date  (t/format (t/formatter "yyyy-MM-dd")
                                                  date),
                                 :value count}))
                          ;; Convert to a vector
                         vec)
        base-url        "/app/dv/habit-logs"]
    ;; Render the page with Cal-Heatmap and habit filter buttons
    (ui/page
     {::ui/cal-heatmap true}
     (side-bar
      (pot/map-of email)
      [:div.flex.flex-col
       [:a.link {:href (str base-url)} "clear filters"]
       (if sensitive
         [:a.link
          {:href (str base-url
                      "?"
                      (when archived "archived=true"))} "remove sensitive"]
         [:a.link
          {:href (str base-url
                      "?"
                      "sensitive=true"
                      (when archived "&archived=true"))} "sensitive"])
       (if archived
         [:a.link
          {:href (str base-url
                      "?"
                      (when sensitive "sensitive=true"))} "remove archived"]
         [:a.link
          {:href (str base-url
                      "?"
                      "archived=true"
                      (when archived "&sensitive=true"))} "archived"])

         ;; Filter buttons section
       [:div#habit-filters.flex.flex-wrap.my-4.w-96
          ;; Render a button for each unique habit
        (for [{id :xt/id, name :habit/name} all-habits]
          (let [url-habits-s  (->> filtered-habits
                                   (remove (fn [fid] (= fid id)))
                                   (map str)
                                   (map (fn [s] (str "habit-ids=" s "&")))
                                   (str/join ""))
                url-habits-ns (->> filtered-habits
                                   (map str)
                                   (concat [(str id)])
                                   (map (fn [s] (str "habit-ids=" s "&")))
                                   (str/join ""))
                selected      (seq (set/intersection filtered-habits #{id}))]
            (if selected
              [:a.px-4.py-2.rounded.m-1.hover:underline.text-blue-500.outline.outline-blue-500.outline-2
               {:href (str base-url
                           "?"
                           url-habits-s
                           (when sensitive "&sensitive=true")
                           (when archived "&archived=true"))}
               name]
              [:a.px-4.py-2.bg-gray-200.rounded.m-1.hover:underline.text-blue-500.outline.outline-gray-300.outline-2
               {:href (str base-url
                           "?"
                           url-habits-ns
                           (when sensitive "&sensitive=true")
                           (when archived "&archived=true"))}
               name])))]
         ;; Container for the heatmap
       [:div#cal-heatmap.my-4]
         ;; Hidden data element for heatmap data
       [:div#cal-heatmap-data.hidden
        (json/generate-string counts-per-day {:pretty true})]
         ;; Script to initialize heatmap rendering
       [:script "renderCalHeatmap();"]

       #_(for [item all-items]
           (habit-log/list-item item))]))))
