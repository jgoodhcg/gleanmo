(ns tech.jgood.gleanmo.app.habit-log
  (:require
   [clojure.string :as str]
   [clojure.set :as set]
   [cheshire.core :as json]
   [com.biffweb :as biff :refer [q]]
   [potpuri.core :as pot]
   [tech.jgood.gleanmo.app.habit :as habit]
   [tech.jgood.gleanmo.app.shared :refer [ensure-vector format-date-time-local
                                          get-last-tx-time get-user-time-zone
                                          link-button local-date-time-fmt param-true? search-str-xform side-bar
                                          time-zone-select zoned-date-time-fmt]]
   [tech.jgood.gleanmo.prediction.heuristics :as pred]
   [tech.jgood.gleanmo.schema.meta :as sm]
   [tech.jgood.gleanmo.ui :as ui]
   [tech.jgood.gleanmo.crud.routes :as crud]
   [tech.jgood.gleanmo.schema :refer [schema]]
   [tick.core :as t]
   [xtdb.api :as xt])
  (:import
   [java.time ZoneId]
   [java.time LocalDateTime]
   [java.time ZonedDateTime]
   [java.util UUID]))

(def crud-routes
  (crud/gen-routes {:entity-key :habit-log
                    :entity-str "habit-log"
                    :plural-str "Habit Logs"
                    :schema     schema}))

(defn some-sensitive-habits [{habits :habit-log/habits}]
  (->> habits
       (map :habit/sensitive)
       (some true?)))

(defn some-archived-habits [{habits :habit-log/habits}]
  ;; TODO add ? to these
  ;; TODO need all-archived-habits?
  (->> habits
       (map :habit/archived)
       (some true?)))

(defn consolidate-habit-log [habit-log habits user-tz]
  (assoc habit-log
         :user/time-zone user-tz
         :habit-log/habits habits))

(defn all-for-user-query [{:keys [biff/db session sensitive archived]}]
  (let [raw-results (q db '{:find  [(pull ?habit-log [*]) (pull ?habit [*]) ?tz]
                            :where [[?habit-log :user/id user-id]
                                    [?habit :user/id user-id]
                                    [?habit-log ::sm/type :habit-log]
                                    [?habit-log :habit-log/timestamp]
                                    [?habit-log :habit-log/habit-ids ?habit-id]
                                    [?habit :xt/id ?habit-id]
                                    [?habit :habit/name ?habit-name]
                                    [?user :xt/id user-id]
                                    [?user :user/time-zone ?tz]
                                    (not [?habit ::sm/deleted-at])
                                    (not [?habit-log ::sm/deleted-at])]
                            :in    [user-id]} (:uid session))]
    (cond->> raw-results
      :always         (group-by (fn [[habit-log _ _]] (:xt/id habit-log))) ; Group by habit-log id
      :always         (map (fn [[log-id grouped-tuples]]
                             ;; Extract the habit-log map from the first tuple and tz from last
                             (let [habit-log-map (-> grouped-tuples first first)
                                   tz            (-> grouped-tuples first last)
                                   habits        (->> grouped-tuples
                                                      (map second))]
                               (consolidate-habit-log habit-log-map
                                                      habits
                                                      tz))))
      :always         (into [])
      :always         (sort-by :habit-log/timestamp)
      :always         (reverse)
      (not sensitive) (remove some-sensitive-habits)
      (not archived)  (remove some-archived-habits))))

(defn single-for-user-query [{:keys [biff/db session :xt/id]}]
  (let [user-id (:uid session)
        result (q db '{:find  [(pull ?habit-log [*]) (pull ?habit [*])]
                       :where [[?habit-log :xt/id log-id]
                               [?habit-log ::sm/type :habit-log]
                               [?habit-log :user/id user-id]
                               [?habit-log :habit-log/habit-ids ?habit-id]
                               [?habit :xt/id ?habit-id]
                               [?habit :habit/name ?habit-name]
                               (not [?habit ::sm/deleted-at])
                               (not [?habit-log ::sm/deleted-at])]
                       :in    [user-id log-id]} user-id id)]
    (consolidate-habit-log (-> result first first)
                           (->> result (map second))
                           (-> result first last))))

(defn list-item [{:habit-log/keys [timestamp habits notes time-zone]
                  user-time-zone  :user/time-zone
                  id              :xt/id}]
  (let [formatted-timestamp (when timestamp
                              (-> timestamp
                                  (t/instant)
                                  (t/in (t/zone (or time-zone user-time-zone)))
                                  (->> (t/format (t/formatter zoned-date-time-fmt)))))]
    [:a {:href (str "/app/habit-logs/" id "/edit")}
     [:div.hover:bg-gray-100.transition.duration-150.p-4.border-b.border-gray-200.cursor-pointer.w-full.md:w-96
      {:id (str "habit-log-list-item-" id)}
      [:span formatted-timestamp]
      (when notes [:p.text-sm.text-gray-600 notes])
      [:div.flex.flex-col.mt-2
       (for [{habit-name :habit/name} habits]
         [:span habit-name])]]]))

(defn any-sensitive? [habits]
  (->> habits
       (some (fn [{:keys [habit/sensitive]}] sensitive))))

(defn any-archived? [habits]
  (->> habits
       (some (fn [{:keys [habit/archived]}] archived))))

(defn new-form [{:keys [session biff/db params]
                 :as   ctx}]
  (let [sensitive            (some-> params :sensitive param-true?)
        archived             (some-> params :archived param-true?)
        persist-query-params (or sensitive archived)
        user-id              (:uid session)
        {:user/keys [email]} (xt/entity db user-id)
        ctx*                 (merge ctx (pot/map-of sensitive archived))
        habits               (habit/all-for-user-query ctx*)
        recent-logs          (->> (all-for-user-query ctx*)
                                  (take 3))
        time-zone            (get-user-time-zone ctx)
        time-zone            (if (some? time-zone) time-zone "US/Eastern")
        current-time         (format-date-time-local (t/now) time-zone)]
    (ui/page
     {}
     [:div
      (side-bar (pot/map-of email)
                [:div.my-4
                 (if sensitive
                   [:a.link {:href "/app/new/habit-log"} "hide sensitive"]
                   [:a.link {:href "/app/new/habit-log?sensitive=true"} "sensitive"])]
                [:div.w-full.md:w-96.space-y-8
                 (biff/form
                  {:hx-post   (str "/app/habit-logs"
                                   (when persist-query-params "?")
                                   (when sensitive "sensitive=true")
                                   (when archived  "&archived=true"))
                   :hx-swap   "outerHTML"
                   :hx-select "#log-habit-form"
                   :id        "log-habit-form"}

                  [:div
                   [:h2.text-base.font-semibold.leading-7.text-gray-900 "Log Habit"]]

                  [:div.grid.grid-cols-1.gap-y-6
                   ;; Time Zone selection
                   ;; TODO add search
                   (time-zone-select time-zone)

                   ;; Notes input
                   [:div
                    [:label.block.text-sm.font-medium.leading-6.text-gray-900 {:for "notes"} "Notes"]
                    [:div.mt-2
                     [:textarea.rounded-md.shadow-sm.block.w-full.border-0.py-1.5.text-gray-900.focus:ring-2.focus:ring-blue-600
                      {:name         "notes"
                       :rows         3
                       :placeholder  "Any additional notes..."
                       :autocomplete "off"}]]]

                   ;; Timestamp input
                   [:div
                    [:label.block.text-sm.font-medium.leading-6.text-gray-900 {:for "timestamp"} "Timestamp"]
                    [:div.mt-2
                     [:input.rounded-md.shadow-sm.block.w-full.border-0.py-1.5.text-gray-900.focus:ring-2.focus:ring-blue-600
                      {:type "datetime-local" :name "timestamp" :required true :value current-time}]]]

                   ;; TODO add search and filters
                   ;; Consider something better than a select list, maybe icons?
                   ;; Habits selection
                   [:div
                    [:label.block.text-sm.font-medium.leading-6.text-gray-900 {:for "habit-refs"} "Habits"]
                    [:div.mt-2
                     [:select.rounded-md.shadow-sm.block.w-full.border-0.py-1.5.text-gray-900.focus:ring-2.focus:ring-blue-600
                      {:name "habit-refs" :multiple true :required true :autocomplete "off"}
                      (map (fn [habit]
                             [:option {:value (:xt/id habit)}
                              (:habit/name habit)])
                           habits)]]]

                   ;; Submit button
                   [:div.mt-2.w-full
                    [:button.bg-blue-500.hover:bg-blue-700.text-white.font-bold.py-2.px-4.rounded.w-full
                     {:type "submit"} "Log Habit"]]

                   ;; Recent logs
                   [:div.mt-4
                    [:h2.text-base.font-semibold.leading-7.text-gray-900 "Recent logs"]
                    (->> recent-logs
                         (map (fn [z] (list-item z))))]])])])))

(defn create! [{:keys [session params] :as ctx}]
  (let [sensitive            (some-> params :sensitive param-true?)
        archived             (some-> params :archived param-true?)
        persist-query-params (or sensitive archived)
        id-strs              (-> params :habit-refs ensure-vector)
        time-zone            (-> params :time-zone)
        timestamp-str        (-> params :timestamp)
        notes                (-> params :notes)
        local-datetime       (LocalDateTime/parse timestamp-str)
        zone-id              (ZoneId/of time-zone)
        zdt                  (ZonedDateTime/of local-datetime zone-id)
        timestamp            (-> zdt (t/instant))
        habit-ids            (->> id-strs
                                  (map #(some-> % UUID/fromString))
                                  set)
        user-id              (:uid session)
        user-time-zone       (get-user-time-zone ctx)
        new-tz               (not= user-time-zone time-zone)
        now                  (t/now)]

    (biff/submit-tx ctx
                    (vec (remove nil?
                                 [(merge
                                   {:db/doc-type :habit-log
                                    ::sm/type :habit-log
                                    :user/id user-id
                                    :habit-log/timestamp timestamp
                                    :habit-log/time-zone time-zone
                                    :habit-log/habit-ids habit-ids
                                    ::sm/created-at now}
                                   (when (not (str/blank? notes))
                                     {:habit-log/notes notes}))
                                  (when new-tz
                                    {:db/op :update
                                     :db/doc-type :user
                                     :xt/id user-id
                                     :user/time-zone time-zone})])))

    {:status  303
     :headers {"location"
               (str "/app/new/habit-log"
                    (when persist-query-params "?")
                    (when sensitive "sensitive=true")
                    (when archived  "&archived=true"))}}))

(defn list-page
  "Accepts GET and POST. POST is for search form as body."
  [{:keys [session biff/db params]
    :as   ctx}]
  (let [user-id                        (:uid session)
        {:user/keys [email time-zone]} (xt/entity db user-id)
        sensitive                      (some-> params :sensitive param-true?)
        archived                       (some-> params :archived param-true?)
        habit-logs                     (->> (merge ctx (pot/map-of sensitive archived))
                                            (all-for-user-query)
                                            (take 15))
        search                         (or (some-> params :search search-str-xform)
                                           "")]
    (ui/page
     {}
     [:div
      (side-bar (pot/map-of email)
                [:div.my-4
                 (link-button {:href  "/app/new/habit-log"
                               :label "Create Habit Log"})]
                [:div.my-4
                 (if sensitive
                   [:a.link {:href "/app/habit-logs"} "hide sensitive"]
                   [:a.link {:href "/app/habit-logs?sensitive=true"} "sensitive"])]
                ;; TODO add filter options and search
                [:div {:id "habit-logs-list"}
                 (cond->> habit-logs
                   :always (map (fn [log] (list-item log))))])])))

(defn edit-form [{:keys [path-params
                         params
                         session
                         biff/db]
                  :as   ctx}]
  (let [log-id               (-> path-params :id UUID/fromString)
        sensitive            (some-> params :sensitive param-true?)
        archived             (some-> params :archived param-true?)
        user-id              (:uid session)
        {:user/keys [email]} (xt/entity db user-id)
        habit-log            (single-for-user-query (merge ctx {:xt/id log-id}))
        habits               (habit/all-for-user-query (merge ctx (pot/map-of sensitive archived)))
        time-zone            (or (get-in habit-log [:habit-log/time-zone])
                                 (get-user-time-zone ctx))
        formatted-timestamp  (-> (get-in habit-log [:habit-log/timestamp])
                                 (t/in (t/zone time-zone))
                                 (->> (t/format (t/formatter local-date-time-fmt))))
        latest-tx-time       (-> (get-last-tx-time (merge ctx {:xt/id log-id}))
                                 (t/in (t/zone time-zone))
                                 (->> (t/format (t/formatter zoned-date-time-fmt))))
        formatted-created-at (-> habit-log
                                 ::sm/created-at
                                 (t/in (t/zone time-zone))
                                 (->> (t/format (t/formatter zoned-date-time-fmt))))]
    (ui/page
     {}
     [:div
      (side-bar (pot/map-of email)
                [:div.my-4
                 (if sensitive
                   [:a.link {:href (str "/app/habit-logs/" log-id "/edit")} "hide sensitive"]
                   [:a.link {:href (str "/app/habit-logs/" log-id "/edit?sensitive=true")} "sensitive"])]
                [:div.w-full.md:w-96.space-y-8
                 (biff/form
                  {:hx-post   (str "/app/habit-logs/" log-id)
                   :hx-swap   "outerHTML"
                   :hx-select "#edit-habit-log-form"
                   :id        "edit-habit-log-form"}

                  [:input {:type "hidden" :name "id" :value log-id}]

                  [:div
                   [:h2.text-base.font-semibold.leading-7.text-gray-900 "Edit Habit Log"]
                   [:p.mt-1.text-sm.leading-6.text-gray-600 "Edit your habit log entry."]]

                  ;; Time Zone selection
                  (time-zone-select time-zone)

;; Notes input
                  [:div
                   [:label.block.text-sm.font-medium.leading-6.text-gray-900 {:for "notes"} "Notes"]
                   [:div.mt-2
                    [:textarea.rounded-md.shadow-sm.block.w-full.border-0.py-1.5.text-gray-900.focus:ring-2.focus:ring-blue-600
                     {:name         "notes"
                      :rows         3
                      :placeholder  "Any additional notes..."
                      :autocomplete "off"} (get-in habit-log [:habit-log/notes])]]]

                  ;; Timestamp input
                  [:div
                   [:label.block.text-sm.font-medium.leading-6.text-gray-900 {:for "timestamp"} "Timestamp"]
                   [:div.mt-2
                    [:input.rounded-md.shadow-sm.block.w-full.border-0.py-1.5.text-gray-900.focus:ring-2.focus:ring-blue-600
                     {:type "datetime-local" :name "timestamp" :required true :value formatted-timestamp}]]]

                  ;; Habits selection
                  [:div
                   [:label.block.text-sm.font-medium.leading-6.text-gray-900 {:for "habit-refs"} "Habits"]
                   [:div.mt-2
                    [:select.rounded-md.shadow-sm.block.w-full.border-0.py-1.5.text-gray-900.focus:ring-2.focus:ring-blue-600
                     {:name "habit-refs" :multiple true :required true :autocomplete "off"}
                     (map (fn [habit]
                            [:option {:value    (:xt/id habit)
                                      :selected (contains? (set (get-in habit-log [:habit-log/habit-ids])) (:xt/id habit))}
                             (:habit/name habit)])
                          habits)]]]

                  ;; Submit button
                  [:div.mt-2.w-full
                   [:button.bg-blue-500.hover:bg-blue-700.text-white.font-bold.py-2.px-4.rounded.w-full
                    {:type "submit"} "Update Habit Log"]]

                  [:div.mt-4.flex.flex-col
                   [:span.text-gray-500 (str "last updated: " latest-tx-time)]
                   [:span.text-gray-500 (str "created at: " formatted-created-at)]])

                 ;; delete form
                 (biff/form
                  {:action (str "/app/habit-logs/" log-id "/delete") :method "post"}
                  [:div.w-full.md:w-96.p-2.my-4
                   [:input.text-center.bg-red-100.hover:bg-red-500.hover:text-white.text-black.font-bold.py-2.px-4.rounded.w-full
                    {:type "submit" :value "Delete"}]])])])))

(defn edit! [{:keys [session params] :as ctx}]
  (let [id-strs        (-> params :habit-refs ensure-vector)
        time-zone      (-> params :time-zone)
        timestamp-str  (-> params :timestamp)
        notes          (-> params :notes)
        local-datetime (LocalDateTime/parse timestamp-str)
        zone-id        (ZoneId/of time-zone)
        zdt            (ZonedDateTime/of local-datetime zone-id)
        timestamp      (-> zdt (t/instant))
        habit-ids      (->> id-strs
                            (map #(some-> % UUID/fromString))
                            set)
        log-id         (-> params :id UUID/fromString)
        user-id        (:uid session)
        user-time-zone (get-user-time-zone ctx)
        new-tz         (not= user-time-zone time-zone)
        ops            (->> [(merge {:db/op               :update
                                     :db/doc-type         :habit-log
                                     ::sm/type        :habit-log
                                     :xt/id               log-id
                                     :habit-log/timestamp timestamp
                                     :habit-log/time-zone time-zone
                                     :habit-log/habit-ids habit-ids}
                                    (when (not (str/blank? notes))
                                      {:habit-log/notes notes}))
                             (when new-tz
                               {:db/op          :update
                                :db/doc-type    :user
                                :xt/id          user-id
                                :user/time-zone time-zone})]
                            (remove nil?))]

    (biff/submit-tx ctx ops)

    {:status  303
     :headers {"location" (str "/app/habit-logs/" log-id "/edit")}}))

(defn view [{:keys [path-params session biff/db] :as ctx}]
  (let [log-id               (-> path-params :id UUID/fromString)
        user-id              (:uid session)
        {:user/keys [email]} (xt/entity db user-id)
        habit-log            (single-for-user-query (merge ctx {:xt/id log-id}))]
    (ui/page
     {}
     (side-bar (pot/map-of email)
               [:div (list-item habit-log)]))))

(defn soft-delete! [{:keys [path-params params]
                     :as   ctx}]
  (let [log-id (-> path-params :id UUID/fromString)
        log    (single-for-user-query (merge ctx {:xt/id log-id}))
        now    (t/now)]
    (if (some? log)
      (do
        (biff/submit-tx ctx
                        [{:db/op              :update
                          :db/doc-type        :habit-log
                          :xt/id              log-id
                          ::sm/deleted-at now}])
        {:status  303
         :headers {"location" "/app/habit-logs"}})
      {:status 403
       :body   "Not authorized to edit that habit log"})))

(defn habit-dates [{:keys [session biff/db params]
                    :as   context}]
  ;; Retrieve the user entity, including the time zone
  (let [{:user/keys [email time-zone] :as user}
        (xt/entity db (:uid session))
        sensitive       (some-> params :sensitive param-true?)
        archived        (some-> params :archived param-true?)
        habit-id        (when-not (str/blank? (:habit-id params))
                          (UUID/fromString (:habit-id params)))
        habit           (when habit-id
                          (xt/entity db habit-id))
        all-habit-logs  (->> (all-for-user-query (merge context (pot/map-of sensitive archived)))
                             (sort-by :habit-log/timestamp)
                             (filter (fn [log]
                                       (when habit-id
                                         (contains? (:habit-log/habit-ids log) habit-id))))
                             reverse)
        all-habits      (->> (habit/all-for-user-query (merge context (pot/map-of sensitive archived))))
        ;; Extract dates of habit logs
        habit-log-dates (->> all-habit-logs
                             (map (fn [item]
                                    (let [timestamp      (:habit-log/timestamp item)
                                          item-time-zone (or (:habit-log/time-zone item) time-zone)
                                          zoned-date     (-> timestamp
                                                             (t/in (t/zone item-time-zone)))
                                          formatted-date (t/format (t/formatter "yyyy-MM-dd") (t/date zoned-date))]
                                      {:date formatted-date
                                       :id   (:xt/id item)})))
                             vec)
        dates-only-text (->> habit-log-dates
                             (map :date)
                             (str/join "\n"))
        dates-only      (->> habit-log-dates (map :date) vec)
        ;; Generate predictions if we have enough dates
        predictions     (when (and habit-id (>= (count dates-only) 2))
                          (pred/get-predictions dates-only))
        has-predictions (and predictions (seq predictions))
        base-url        "/app/dv/habit-dates"]
    ;; Render the page with list of dates when habit was logged
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
          (for [{id :xt/id name :habit/name} all-habits]
            [:option
             {:value    (str base-url "?habit-id=" id
                             (when sensitive "&sensitive=true")
                             (when archived "&archived=true"))
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
                [:p.text-lg.font-medium (str "Day " monthly-day " of each month")]])])])

       ;; Copy dates button (only visible when habit is selected and dates exist)
       (when (and habit-id (seq habit-log-dates))
         [:div.mb-4
          [:button.bg-blue-500.hover:bg-blue-700.text-white.font-bold.py-2.px-4.rounded.flex.items-center
           {:id      "copy-dates-btn"
            :onclick (str "copyToClipboard(`" dates-only-text "`)")}
           [:svg.w-4.h-4.mr-2 {:xmlns "http://www.w3.org/2000/svg" :fill "none" :viewBox "0 0 24 24" :stroke "currentColor"}
            [:path {:stroke-linecap  "round"
                    :stroke-linejoin "round"
                    :stroke-width    "2"
                    :d               "M8 5H6a2 2 0 00-2 2v12a2 2 0 002 2h10a2 2 0 002-2v-1M8 5a2 2 0 002 2h2a2 2 0 002-2M8 5a2 2 0 012-2h2a2 2 0 012 2m0 0h2a2 2 0 012 2v3m2 4H10m0 0l3-3m-3 3l3 3"}]]
           "Copy Dates"]])

       ;; Hidden textarea containing just the dates for copying (invisible element)
       (when (and habit-id (seq habit-log-dates))
         [:textarea.hidden#dates-text dates-only-text])

       ;; List of dates
       (if (and habit-id (seq habit-log-dates))
         [:div.border.rounded-lg.overflow-hidden
          [:table.min-w-full.divide-y.divide-gray-200
           [:thead.bg-gray-50
            [:tr
             [:th.px-6.py-3.text-left.text-xs.font-medium.text-gray-500.uppercase.tracking-wider "Date"]
             [:th.px-6.py-3.text-left.text-xs.font-medium.text-gray-500.uppercase.tracking-wider "Action"]]]
           [:tbody.bg-white.divide-y.divide-gray-200
            (for [{:keys [date id]} habit-log-dates]
              [:tr.hover:bg-gray-50
               [:td.px-6.py-4.whitespace-nowrap.text-sm.text-gray-900 date]
               [:td.px-6.py-4.whitespace-nowrap.text-sm.font-medium
                [:a.text-blue-600.hover:text-blue-900
                 {:href (str "/app/habit-logs/" id "/edit")}
                 "View Details"]]])]]]

         ;; No habit selected or no dates found
         [:div.text-center.py-8.text-gray-500
          (if habit-id
            "No log entries found for this habit."
            "Please select a habit to view log dates.")])]))))

(defn data-viz [{:keys [session biff/db params]
                 :as   context}]
  ;; Retrieve the user entity, including the time zone
  (let [{:user/keys [email time-zone] :as user}
        (xt/entity db (:uid session))
        sensitive            (some-> params :sensitive param-true?)
        archived             (some-> params :archived param-true?)
        ;; Normalize :habit-ids to always be a sequence
        params-ids           (let [ids (:habit-ids params)]
                               (if (string? ids)
                                 [ids]   ;; Wrap single string in a vector
                                 ids))   ;; Leave as-is if already a sequence
        filtered-habits      (->> params-ids
                                  (map (fn [s]
                                         (when (not (str/blank? s))
                                           (UUID/fromString s))))
                                  (remove nil?)
                                  set)
        all-habit-logs       (->> (all-for-user-query (merge context (pot/map-of sensitive archived)))
                                  (sort-by :habit-log/timestamp)
                                  reverse)
        all-habits           (->> (habit/all-for-user-query (merge context (pot/map-of sensitive archived))))
        ;; Aggregate habit logs per day in the user's time zone
        counts-per-day       (->> all-habit-logs
                                  (filter (fn [log]
                                       ;; NOTE should this condition be moved to a cond->> ?
                                            (if (seq filtered-habits)
                                              (seq (set/intersection
                                                    filtered-habits
                                                    (:habit-log/habit-ids log)))
                                              true)))
                             ;; Map each item to the date in the user's time zone
                                  (map (fn [item]
                                         (let [timestamp      (:habit-log/timestamp item)
                                               item-time-zone (or (:habit-log/time-zone item) time-zone)
                                               zoned-date     (-> timestamp
                                                                  (t/in (t/zone item-time-zone)))
                                               local-date     (t/date zoned-date)]
                                           local-date)))
                             ;; Count the frequency of each date
                                  frequencies
                             ;; Convert to a sequence of maps with 'date' and 'value'
                                  (map (fn [[date count]]
                                         {:date  (t/format (t/formatter "yyyy-MM-dd") date)
                                          :value count}))
                             ;; Convert to a vector
                                  vec)
        base-url "/app/dv/habit-logs"]
    ;; Render the page with Cal-Heatmap and habit filter buttons
    (ui/page
     {::ui/cal-heatmap true}
     (side-bar
      (pot/map-of email)
      [:div.flex.flex-col
       [:a.link {:href (str base-url)} "clear filters"]
       (if sensitive
         [:a.link {:href (str base-url "?"
                              (when archived "archived=true"))} "remove sensitive"]
         [:a.link {:href (str base-url "?" "sensitive=true"
                              (when archived "&archived=true"))} "sensitive"])
       (if archived
         [:a.link {:href (str base-url "?"
                              (when sensitive "sensitive=true"))} "remove archived"]
         [:a.link {:href (str base-url "?" "archived=true"
                              (when archived "&sensitive=true"))} "archived"])

       ;; Filter buttons section
       [:div#habit-filters.flex.flex-wrap.my-4.w-96
        ;; Render a button for each unique habit
        (for [{id :xt/id name :habit/name} all-habits]
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
               {:href (str base-url "?" url-habits-s
                           (when sensitive "&sensitive=true")
                           (when archived "&archived=true"))}
               name]
              [:a.px-4.py-2.bg-gray-200.rounded.m-1.hover:underline.text-blue-500.outline.outline-gray-300.outline-2
               {:href (str base-url "?" url-habits-ns
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
