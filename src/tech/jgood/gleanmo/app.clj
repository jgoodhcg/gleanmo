(ns tech.jgood.gleanmo.app
  (:require
   [clojure.string :as str]
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
   [tech.jgood.gleanmo.app.shared :refer [side-bar]]
   [tech.jgood.gleanmo.app.timers :as timers]
   [tech.jgood.gleanmo.app.user :as user]
   [tech.jgood.gleanmo.db.queries :as db]
   [tech.jgood.gleanmo.middleware :as mid]
   [tech.jgood.gleanmo.schema.meta :as sm]
   [tech.jgood.gleanmo.settings :as settings]
   [tech.jgood.gleanmo.ui :as ui]))

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
                 [:th.table-header-cell {:key (str attr)
                                         :style {:max-width "250px" :overflow "hidden"}}
                  (str attr)])]]
             [:tbody.table-body
              (map-indexed
               (fn [idx row]
                 [:tr.table-row {:key idx}
                  (for [attr-val row]
                    [:td.table-cell {:style {:max-width "250px" :overflow "hidden"}}
                     (str attr-val)])])
               table-rows)]]]])
        [:div.my-4
         [:span "Unsupported type, must be one of: "
          (str db-viz-supported-types)]])))))


(defn root
  [{:keys [session biff/db], :as ctx}]
  (ui/page
   {}
   [:div
    (side-bar
     ctx
     [:div.flex.flex-col.justify-center.space-y-4
      [:h1.text-3xl.font-bold.text-primary "App Root Page!"]
      [:div.rgb-test.rgb-glow
       "🚀 RGB Glow Test - This should have animated rainbow borders!"]])]))


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
            
            ;; Dashboard routes
            dashboards/routes

            ;; Timers
            timers/routes

            ;; Main app and DB visualization
            ["" {:get root}]

            ["/db" {:get db-viz}]
            ["/db/:type" {:get db-viz}]

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
