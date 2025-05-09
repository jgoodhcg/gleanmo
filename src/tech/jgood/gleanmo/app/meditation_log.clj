(ns tech.jgood.gleanmo.app.meditation-log
  (:require
   [com.biffweb :as biff]
   [potpuri.core :as pot]
   [tech.jgood.gleanmo.app.shared :refer [get-user-time-zone side-bar]]
   [tech.jgood.gleanmo.crud.routes :as crud]
   [tech.jgood.gleanmo.db.queries :as db]
   [tech.jgood.gleanmo.schema :refer [schema]]
   [tech.jgood.gleanmo.ui :as ui]
   [tick.core :as t])
  (:import
   [java.time ZoneId]))

(def crud-routes
  (crud/gen-routes {:entity-key :meditation-log,
                    :entity-str "meditation-log",
                    :plural-str "meditation logs",
                    :schema     schema}))

(defn- date-str->instant
  "Convert a date string (YYYY-MM-DD) to an instant at the start of that day in the given time zone"
  [date-str zone-id]
  (when (and date-str (not-empty date-str))
    (-> (t/date date-str)
        (t/at (t/midnight))
        (t/in zone-id)
        t/instant)))

(defn- date-str->end-of-day-instant
  "Convert a date string (YYYY-MM-DD) to an instant at the end of that day in the given time zone"
  [date-str zone-id]
  (when (and date-str (not-empty date-str))
    (-> (t/date date-str)
        (t/at (t/new-time 23 59 59))
        (t/in zone-id)
        t/instant)))

(defn meditation-stats
  [{:keys [session biff/db params],
    :as   context}]
  (let [{:user/keys [email]} (db/get-entity-by-id db (:uid session))
        time-zone            (get-user-time-zone context)
        zone-id              (ZoneId/of (or time-zone "US/Eastern"))
        start-date-str       (:start-date params)
        end-date-str         (:end-date params)
        start-date           (date-str->instant start-date-str zone-id)
        ;; end-date if supplied or right now
        end-date             (date-str->end-of-day-instant end-date-str zone-id)
        all-logs             (all-for-user-query context)
        ;; Apply date range filtering if provided
        logs                 (cond->> all-logs
                               start-date (filter
                                           #(t/>= (:meditation-log/beginning %)
                                                  start-date))
                               end-date   (filter
                                           #(t/<= (:meditation-log/beginning %)
                                                  end-date)))
        filtering-active?    (or start-date end-date)
        ;; Count total meditation logs
        total-logs           (count logs)
        ;; Filter logs with both beginning and end timestamps (completed
        ;; logs)
        completed-logs       (->> logs
                                  (filter #(and (:meditation-log/beginning %)
                                                (:meditation-log/end %))))
        completed-count      (count completed-logs)
        ;; Calculate duration for each completed log
        durations            (map (fn [log]
                                    (-> (t/duration
                                         {:tick/beginning
                                          (:meditation-log/beginning log),
                                          :tick/end (:meditation-log/end log)})
                                        t/minutes))
                                  completed-logs)
        ;; Calculate average duration (in minutes)
        avg-duration         (->>
                              (if (pos? completed-count)
                                (/ (reduce + durations) completed-count)
                                0)
                              double
                              (format "%.1f min"))
        ;; Calculate average daily duration (minutes per day)
        total-duration       (if (pos? completed-count) (reduce + durations) 0)
        first-date           (when (pos? completed-count)
                               (or start-date
                                   (:meditation-log/beginning
                                    (last completed-logs))))
        last-date            (when (pos? completed-count)
                               (or end-date
                                   (t/now)))
        days-interval        (when (and first-date last-date)
                               (-> (t/duration
                                    {:tick/beginning first-date,
                                     :tick/end       last-date})
                                   t/days
                                   (max 1))) ;; Ensure at least 1 day to
                                             ;; avoid division by zero
        avg-daily-duration   (->>
                              (if (and (pos? completed-count) days-interval)
                                (/ total-duration days-interval)
                                0)
                              double
                              (format "%.1f min/day"))
        days-display         (when days-interval
                               (format "(%d day%s)"
                                       days-interval
                                       (if (= days-interval 1) "" "s")))]

    (ui/page
     {}
     (side-bar
      (pot/map-of email)
      [:div.flex.flex-col
       [:h1.text-2xl.font-bold.mb-4 "Meditation Statistics"]

         ;; Date Range Filter Form
       (biff/form
        {:hx-post   "/app/dv/meditation-stats",
         :hx-swap   "outerHTML",
         :hx-target "#meditation-stats-container",
         :hx-select "#meditation-stats-container",
         :id        "meditation-stats-form",
         :class     "bg-white p-6 rounded-lg shadow mb-6"}
        [:div.flex.flex-col.space-y-4
         [:h2.text-lg.font-semibold "Filter by Date Range"]

         [:div.grid.grid-cols-1.md:grid-cols-2.gap-4
             ;; Start Date input
          [:div
           [:label.block.text-sm.font-medium.leading-6.text-gray-900
            {:for "start-date"} "Start Date"]
           [:div.mt-2
            [:input.rounded-md.shadow-sm.block.w-full.border-0.py-1.5.text-gray-900.focus:ring-2.focus:ring-blue-600
             {:type "date", :name "start-date", :value start-date-str}]]]

             ;; End Date input
          [:div
           [:label.block.text-sm.font-medium.leading-6.text-gray-900
            {:for "end-date"} "End Date"]
           [:div.mt-2
            [:input.rounded-md.shadow-sm.block.w-full.border-0.py-1.5.text-gray-900.focus:ring-2.focus:ring-blue-600
             {:type "date", :name "end-date", :value end-date-str}]]]]

            ;; Submit and Clear buttons
         [:div.flex.space-x-4
          [:button.bg-blue-500.hover:bg-blue-700.text-white.font-bold.py-2.px-4.rounded
           {:type "submit"} "Apply Filter"]
          [:a.bg-gray-300.hover:bg-gray-400.text-black.font-bold.py-2.px-4.rounded.text-center
           {:href "/app/dv/meditation-stats"} "Clear Filter"]]])

       [:div#meditation-stats-container
          ;; Filter status indicator
        (when filtering-active?
          [:div.bg-blue-50.border-l-4.border-blue-400.p-4.mb-6
           [:p.text-blue-700
            "Showing statistics for "
            (cond
              (and start-date-str end-date-str) (str "period from "
                                                     start-date-str
                                                     " to "
                                                     end-date-str)
              start-date-str (str "period starting " start-date-str)
              end-date-str   (str "period ending " end-date-str)
              :else          "")]])

        [:div.grid.grid-cols-1.gap-4.md:grid-cols-4.mb-6
         [:div.bg-white.p-6.rounded-lg.shadow
          [:h3.text-sm.font-medium.text-gray-500 "Total Meditation Logs"]
          [:p.text-3xl.font-bold total-logs]]

         [:div.bg-white.p-6.rounded-lg.shadow
          [:h3.text-sm.font-medium.text-gray-500 "Completed Meditation Logs"]
          [:p.text-3xl.font-bold completed-count]]

         [:div.bg-white.p-6.rounded-lg.shadow
          [:h3.text-sm.font-medium.text-gray-500 "Average Duration"]
          [:p.text-3xl.font-bold avg-duration]]

         [:div.bg-white.p-6.rounded-lg.shadow
          [:h3.text-sm.font-medium.text-gray-500 "Daily Average"]
          [:div.flex.flex-col
           [:p.text-3xl.font-bold avg-daily-duration]
           (when days-display
             [:p.text-sm.text-gray-500 days-display])]]]]]))))
