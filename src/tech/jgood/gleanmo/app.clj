(ns tech.jgood.gleanmo.app
  (:require
   [clojure.string :as str]
   [com.biffweb :as biff]
   [tech.jgood.gleanmo.app.bm-log :as bm-log]
   [tech.jgood.gleanmo.app.calendar :as calendar]
   [tech.jgood.gleanmo.app.calendar-event :as calendar-event]
   [tech.jgood.gleanmo.app.cruddy :as cruddy]
   [tech.jgood.gleanmo.app.dashboards :as dashboards]
   [tech.jgood.gleanmo.app.habit :as habit]
   [tech.jgood.gleanmo.app.habit-log :as habit-log]
   [tech.jgood.gleanmo.app.location :as location]
   [tech.jgood.gleanmo.app.medication :as medication]
   [tech.jgood.gleanmo.app.medication-log :as med-log]
   [tech.jgood.gleanmo.app.meditation :as meditation]
   [tech.jgood.gleanmo.app.meditation-log :as meditation-log]
   [tech.jgood.gleanmo.app.project :as project]
   [tech.jgood.gleanmo.app.project-log :as project-log]
   [tech.jgood.gleanmo.app.task :as task]
   [tech.jgood.gleanmo.app.shared :as    shared
    :refer [side-bar]]
   [tech.jgood.gleanmo.app.timers :as timers]
   [tech.jgood.gleanmo.app.overview :as overview]
   [tech.jgood.gleanmo.app.user :as user]
   [tech.jgood.gleanmo.db.queries :as db]
   [tech.jgood.gleanmo.middleware :as mid]
   [tech.jgood.gleanmo.observability :as obs]
   [tech.jgood.gleanmo.schema.meta :as sm]
   [tech.jgood.gleanmo.settings :as settings]
   [tech.jgood.gleanmo.ui :as ui]
   [xtdb.api :as xt]))

(def about-page
  (ui/page
   {:base/title (str "About " settings/app-name)}
   [:p "This app was made with "
    [:a.link {:href "https://biffweb.com"} "Biff"] "."]))

(def db-viz-supported-types
  #{:user
    :habit
    :habit-log
    :meditation
    :meditation-log
    :bm-log
    :medication
    :medication-log
    :location
    :ical-url
    :calendar-event
    :project
    :project-log
    :task
    :cruddy})

(defn db-viz
  [{:keys [session biff/db path-params params], :as ctx}]
  (let [user-id          (:uid session)
        {:keys [super-user]} (db/get-user-authz db user-id)
        type             (->> path-params
                              :type
                              keyword
                              (conj [])
                              (some db-viz-supported-types))
        default-limit    100
        offset           (if (some? (-> params
                                        :offset))
                           (-> params
                               :offset
                               Integer/parseInt)
                           0)
        limit            (if (some? (-> params
                                        :limit))
                           (-> params
                               :limit
                               Integer/parseInt)
                           default-limit)
        filter-email     (-> params
                             :email)
        all-query        '{:find  [(pull ?entity [*])],
                           :where [[?entity :xt/id ?id]
                                   [?entity ::sm/type type]],
                           :in    [[type email]]}
        email-query      '{:find  [(pull ?entity [*])],
                           :where [[?entity :xt/id ?id]
                                   [?entity ::sm/type type]
                                   [?user :user/email email]
                                   [?entity :user/id ?user]],
                           :in    [[type email]]}
        email-query-user '{:find  [(pull ?entity [*])],
                           :where [[?entity :xt/id ?id]
                                   [?entity ::sm/type type]
                                   [?entity :user/email email]],
                           :in    [[type email]]}
        query            (cond
                           (and (= type :user) (not (str/blank? filter-email)))
                           email-query-user

                           (some? filter-email) email-query

                           :else all-query)]

    (when (not (true? super-user))
      (throw (Exception. "User not authorized for db-viz")))

    (ui/page
     {}
     (side-bar
      ctx
        ;; supported types
      [:div.my-4
       (for [t (-> db-viz-supported-types
                   sort)]
         [:a.link.mr-2
          {:href (str "/app/db/" (name t) "?offset=0&limit=" limit)}
          t])]
      (when (some? type)
          ;; pagination
        [:div.mt-4.mb-2
         [:a.link.mr-4
          {:href (str "/app/db/" (name type)
                      "?offset=" (max 0 (- offset limit))
                      "&limit="  limit)}
          "<-"]
         [:a.link
          {:href (str "/app/db/" (name type)
                      "?offset=" (+ offset limit)
                      "&limit="  limit)}
          "->"]])
        ;; items
      (if (some? type)
        (let [query-result
              (db/db-viz-query db query type filter-email)
              all-entities
              (->> query-result
                   (map first)
                   (filter #(uuid? (:xt/id %)))
                   (sort-by (juxt ::sm/created-at :user/id :xt/id))
                   (drop offset)
                   (take limit)
                   (map #(into (sorted-map) %)))
              all-attributes
              (->> all-entities
                   (mapcat keys)
                   distinct
                   sort)
              table-rows
              (map (fn [entity]
                     (map (fn [attr]
                            (get entity attr "_"))
                          all-attributes))
                   all-entities)]
          [:div.my-4
           [:h2.form-header (str (name type) " entities")]
           [:div.table-container
            [:table.min-w-full.table-fixed {:style {:table-layout "fixed"}}
             [:thead.table-header
              [:tr
               (for [attr all-attributes]
                 [:th.table-header-cell
                  {:key   (str attr),
                   :style {:max-width "250px", :overflow "hidden"}}
                  (str attr)])]]
             [:tbody.table-body
              (map-indexed
               (fn [idx row]
                 [:tr.table-row {:key idx}
                  (for [attr-val row]
                    [:td.table-cell
                     {:style {:max-width "250px", :overflow "hidden"}}
                     (str attr-val)])])
               table-rows)]]]])
        [:div.my-4
         [:span "Unsupported type, must be one of: "
          (str db-viz-supported-types)]])))))

(defn root
  [ctx]
  (ui/page
   ctx
   (side-bar
    ctx
    (overview/overview-shell ctx))))

(defn- super-user?
  [db user-id]
  (true? (:super-user (db/get-user-authz db user-id))))

(def window-options
  [{:label "1 minute", :dur (java.time.Duration/ofMinutes 1)}
   {:label "5 minutes", :dur (java.time.Duration/ofMinutes 5)}
   {:label "15 minutes", :dur (java.time.Duration/ofMinutes 15)}
   {:label "30 minutes", :dur (java.time.Duration/ofMinutes 30)}
   {:label "45 minutes", :dur (java.time.Duration/ofMinutes 45)}
   {:label "1 hour", :dur (java.time.Duration/ofHours 1)}
   {:label "3 hours", :dur (java.time.Duration/ofHours 3)}
   {:label "6 hours", :dur (java.time.Duration/ofHours 6)}
   {:label "12 hours", :dur (java.time.Duration/ofHours 12)}
   {:label "24 hours", :dur (java.time.Duration/ofHours 24)}
   {:label "7 days", :dur (java.time.Duration/ofDays 7)}
   {:label "All time", :dur nil}])

(defn- now-inst [] (java.time.Instant/now))

(defn load-performance-history
  [db instance-id {:keys [dur]}]
  (let [doc-id  (keyword "performance-report" instance-id)
        history (xt/entity-history db doc-id :desc {:with-docs? true})
        cutoff  (when dur (.minus (now-inst) dur))]
    (->> history
         (keep (fn [{:keys [xtdb.api/doc xtdb.api/tx-time]}]
                 (let [generated (:performance-report/generated-at doc)
                       include?  (or (nil? cutoff)
                                     (and generated
                                          (not (.isBefore generated cutoff))))]
                   (when include?
                     {:doc doc, :generated generated, :tx-time tx-time})))))))

(defn format-duration
  [nanos]
  (cond
    (nil? nanos)   "0ns"
    (>= nanos 1e9) (format "%.2fs" (/ nanos 1e9))
    (>= nanos 1e6) (format "%.2fms" (/ nanos 1e6))
    (>= nanos 1e3) (format "%.2fµs" (/ nanos 1e3))
    :else          (str nanos "ns")))

(defn aggregate-metric
  [existing metrics]
  (let [n-a     (:n existing 0)
        n-b     (:n metrics 0)
        sum-a   (:sum existing 0.0)
        sum-b   (:sum metrics 0.0)
        n       (+ n-a n-b)
        sum     (+ sum-a sum-b)
        min-val (cond
                  (and (:min existing) (:min metrics)) (min (:min existing)
                                                            (:min metrics))
                  (:min existing) (:min existing)
                  :else (:min metrics))
        max-val (cond
                  (and (:max existing) (:max metrics)) (max (:max existing)
                                                            (:max metrics))
                  (:max existing) (:max existing)
                  :else (:max metrics))]
    {:n    n,
     :sum  sum,
     :mean (if (pos? n) (/ sum n) 0),
     :min  min-val,
     :max  max-val}))

(defn aggregate-route
  [route-acc {:keys [clock stats]}]
  (let [total (+ (:clock-total route-acc 0) (get-in clock [:total] 0))
        aggregated-stats (reduce-kv
                          (fn [acc pid metrics]
                            (update acc pid aggregate-metric metrics))
                          (:stats route-acc {})
                          stats)]
    {:clock-total total,
     :stats       aggregated-stats}))

(defn merge-pstats
  [entries]
  (reduce
   (fn [acc {:keys [doc]}]
     (reduce-kv
      (fn [acc2 route data]
        (update acc2 route aggregate-route data))
      acc
      (:performance-report/pstats doc)))
   {}
   entries))

(defn id->label
  [id]
  (cond
    (instance? clojure.lang.Named id) (name id)
    (string? id) id
    :else (pr-str id)))

(defn build-summary
  [_ {:keys [clock-total stats]}]
  (let [header (format "%-55s %8s %10s %10s %10s %10s"
                       "Span" "Calls"
                       "Mean" "Total"
                       "Min"  "Max")
        lines  (for [[pid {:keys [n mean sum min max]}] stats]
                 (format "%-55s %8d %10s %10s %10s %10s"
                         (id->label pid)
                         n
                         (format-duration mean)
                         (format-duration sum)
                         (format-duration min)
                         (format-duration max)))]
    (str/join "\n"
              (concat [header]
                      lines
                      [(str "Clock total: " (format-duration clock-total))]))))

(defn persist-performance-snapshot
  [{:keys [session biff/db], :as ctx}]
  (let [user-id (:uid session)]
    (if-not (super-user? db user-id)
      (ui/page
       {:status 403}
       (side-bar
        ctx
        [:div.max-w-xl.mx-auto.space-y-4
         [:h1.text-2xl.font-semibold "Access Restricted"]
         [:p "This action is limited to super users."]]))
      (let [result (obs/persist-instance-snapshot! ctx)
            status (if result "ok" "empty")]
        {:status  303,
         :headers {"location" (str "/app/monitoring/performance?persist="
                                   status)}}))))

(defn performance-instance-dashboard
  [{:keys [session biff/db params], :as ctx}]
  (let [user-id (:uid session)]
    (if-not (super-user? db user-id)
      (ui/page
       {:status 403}
       (side-bar
        ctx
        [:div.max-w-xl.mx-auto.space-y-4
         [:h1.text-2xl.font-semibold "Access Restricted"]
         [:p
          "This page is limited to super users. Contact an administrator if you need access."]]))
      (let [selected-label   (or (:window params) "1 hour")
            persist-status   (:persist params)
            live-doc         (obs/current-metrics)
            live-merged      (when live-doc
                               (merge-pstats [{:doc live-doc}]))
            window           (some #(when (= (:label %) selected-label) %)
                                   window-options)
            instance-id      (obs/current-instance-id)
            all-history      (load-performance-history db
                                                       instance-id
                                                       {:dur nil})
            entries          (if (:dur window)
                               (load-performance-history db instance-id window)
                               all-history)
            merged-stats     (merge-pstats entries)
            latest-doc       (some-> entries
                                     first
                                     :doc)
            latest-generated (some-> entries
                                     first
                                     :generated)]
        (ui/page
         {}
         (side-bar
          ctx
          [:div.max-w-5xl.mx-auto.space-y-6
           [:h1.text-2xl.font-semibold
            "Performance Dashboard (This Instance)"]
           (when persist-status
             [:div.bg-dark-surface.border.border-dark.rounded.px-4.py-3.text-sm
              (case persist-status
                "ok"    "Snapshot persisted and profiler reset."
                "empty" "No metrics were available to persist."
                persist-status)])
           [:div.grid.grid-cols-1.md:grid-cols-4.gap-4
            [:div.bg-dark-surface.p-4.rounded
             [:dt.text-xs.text-gray-400.uppercase "Instance"]
             [:dd.font-mono.break-all (str instance-id)]]
            [:div.bg-dark-surface.p-4.rounded
             [:dt.text-xs.text-gray-400.uppercase "Git SHA"]
             [:dd.font-mono
              (or (:performance-report/git-sha latest-doc) "unknown")]]
            [:div.bg-dark-surface.p-4.rounded
             [:dt.text-xs.text-gray-400.uppercase "Total Snapshots"]
             [:dd.font-mono (str (count all-history))]]
            [:div.bg-dark-surface.p-4.rounded
             [:dt.text-xs.text-gray-400.uppercase "Latest Snapshot"]
             [:dd.font-mono
              (or (some-> (:performance-report/generated-at latest-doc)
                          str)
                  "n/a")]]]
           (biff/form
            {:method "post",
             :action "/app/monitoring/performance",
             :class  "inline-block"}
            [:button.form-button-primary {:type "submit"}
             "Persist & Reset Metrics"])

           [:section.space-y-4
            [:h2.text-xl.font-semibold "Live Metrics (since last persist)"]
            (if (and live-doc (seq live-merged))
              (let [generated (:performance-report/generated-at live-doc)]
                (into
                 [:div.space-y-4
                  [:p.text-sm.text-gray-400
                   (str "Snapshot taken at "
                        (or (some-> generated
                                    str)
                            "n/a")
                        ".")]]
                 (for [[k aggregated] (sort-by (comp id->label first)
                                               live-merged)]
                   [:article.bg-dark-surface.p-4.rounded
                    [:h2.font-mono.text-sm.mb-2 (id->label k)]
                    [:pre.text-xs.whitespace-pre-wrap
                     (build-summary k aggregated)]])))
              [:p.text-sm.text-gray-500 "No live metrics captured yet."])]

           [:form.mb-4 {:method "get"}
            [:label.mr-2 "Window:"]
            [:select.bg-dark-surface.text-white.rounded.px-2.py-1
             {:name     "window",
              :onchange "this.form.submit()"}
             (for [{:keys [label]} window-options]
               [:option
                {:value    label,
                 :selected (= label selected-label)}
                label])]]
           [:div.grid.grid-cols-1.md:grid-cols-3.gap-4
            [:div.bg-dark-surface.p-4.rounded
             [:dt.text-xs.text-gray-400.uppercase "Window"]
             [:dd.font-mono selected-label]]
            [:div.bg-dark-surface.p-4.rounded
             [:dt.text-xs.text-gray-400.uppercase "Snapshots in Window"]
             [:dd.font-mono (str (count entries))]]
            [:div.bg-dark-surface.p-4.rounded
             [:dt.text-xs.text-gray-400.uppercase "Latest in Window"]
             [:dd.font-mono
              (or (some-> latest-generated
                          str)
                  "n/a")]]]
           (if (seq merged-stats)
             (for [[k aggregated] (sort-by (comp id->label first)
                                           merged-stats)]
               [:article.bg-dark-surface.p-4.rounded
                [:h2.font-mono.text-sm.mb-2 (id->label k)]
                [:pre.text-xs.whitespace-pre-wrap
                 (build-summary k aggregated)]])
             [:p.text-sm.text-gray-400
              "No metrics captured in this window."])]))))))

(def module
  {:static {"/about/" about-page},
   :routes ["/app" {:middleware [mid/wrap-signed-in]}

            cruddy/crud-routes
            calendar-event/crud-routes
            habit/crud-routes
            habit-log/crud-routes
            habit-log/viz-routes
            ;; ical-url/crud-routes
            location/crud-routes
            meditation/crud-routes
            meditation-log/crud-routes
            meditation-log/viz-routes
            bm-log/crud-routes
            bm-log/viz-routes
            medication/crud-routes
            med-log/crud-routes
            med-log/viz-routes
            project/crud-routes
            project-log/crud-routes
            project-log/viz-routes
            project-log/timer-routes
            meditation-log/timer-routes
            task/crud-routes

            ;; Dashboard routes
            dashboards/routes

            ;; Timers
            timers/routes

            ;; Main app and DB visualization
            ["/overview/stats" {:get overview/stats-fragment}]
            ["/overview/events" {:get overview/upcoming-events-fragment}]
            ["/overview/recent" {:get overview/recent-activity-fragment}]
            ["" {:get root}]

            ["/db" {:get db-viz}]
            ["/db/:type" {:get db-viz}]
            ["/monitoring/performance"
             {:get  performance-instance-dashboard,
              :post persist-performance-snapshot}]

            ;; user
            ["/my-user" {:get user/my-user}]
            ["/users"
             {:middleware [mid/wrap-user-authz],
              :post       user/create!}]
            ["/users/:id"
             {:middleware [mid/wrap-user-authz],
              :get        user/view,
              :post       user/edit!}]
            ["/users/:id/edit"
             {:middleware [mid/wrap-user-authz],
              :get        user/edit-form}]

            ;; Settings endpoints
            ["/users/:id/settings/turn-off-sensitive"
             {:middleware [mid/wrap-user-authz],
              :post       user/turn-off-sensitive!}]
            ["/users/:id/settings/turn-off-archived"
             {:middleware [mid/wrap-user-authz],
              :post       user/turn-off-archived!}]
            ["/users/:id/settings/turn-off-bm-logs"
             {:middleware [mid/wrap-user-authz],
              :post       user/turn-off-bm-logs!}]

            ;;
            ;; data viz
            ;;

            ["/dv/habit-dates" {:get habit-log/habit-dates}]
            ["/dv/meditation-stats"
             {:get  meditation-log/meditation-stats,
              :post meditation-log/meditation-stats}]
            ["/dv/bm-stats" {:get bm-log/bm-stats}]

            ;; Calendar views
            ["/calendar/year" {:get calendar/year-calendar}]
            ["/calendar/event-form" {:get calendar/big-calendar-event-form}]
            ["/calendar/events" {:post calendar/big-calendar-create-event!}]
            ;;
            ]})
