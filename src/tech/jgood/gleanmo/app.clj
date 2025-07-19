(ns tech.jgood.gleanmo.app
  (:require
   [clojure.string :as str]
   [com.biffweb :as biff :refer [q]]
   [potpuri.core :as pot]
   [clojure.pprint :refer [pprint]]
   [tech.jgood.gleanmo.app.cruddy :as cruddy]
   [tech.jgood.gleanmo.app.habit :as habit]
   [tech.jgood.gleanmo.app.habit-log :as habit-log]
   [tech.jgood.gleanmo.app.ical-url :as ical-url]
   [tech.jgood.gleanmo.app.location :as location]
   [tech.jgood.gleanmo.app.meditation-log :as meditation-log]
   [tech.jgood.gleanmo.app.meditation :as meditation]
   [tech.jgood.gleanmo.app.bm-log :as bm-log]
   [tech.jgood.gleanmo.app.shared :refer [side-bar]]
   [tech.jgood.gleanmo.app.user :as user]
   [tech.jgood.gleanmo.middleware :as mid]
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
    :location
    :ical-url
    :cruddy})

(defn db-viz [{:keys [session biff/db path-params params]}]
  (let [{:user/keys  [email]
         :authz/keys [super-user]} (xt/entity db (:uid session))
        type                       (->> path-params :type keyword (conj []) (some db-viz-supported-types))
        default-limit              100
        offset                     (if (some? (-> params :offset))
                                     (-> params :offset Integer/parseInt)
                                     0)
        limit                      (if (some? (-> params :limit))
                                     (-> params :limit Integer/parseInt)
                                     default-limit)
        filter-email               (-> params :email)
        all-query                  '{:find  [(pull ?entity [*])]
                                     :where [[?entity :xt/id ?id]
                                             [?entity ::sm/type type]]
                                     :in    [[type email]]}
        email-query                '{:find  [(pull ?entity [*])]
                                     :where [[?entity :xt/id ?id]
                                             [?entity ::sm/type type]
                                             [?user   :user/email email]
                                             [?entity :user/id ?user]]
                                     :in    [[type email]]}
        email-query-user           '{:find  [(pull ?entity [*])]
                                     :where [[?entity :xt/id ?id]
                                             [?entity ::sm/type type]
                                             [?entity :user/email email]]
                                     :in    [[type email]]}
        query                      (cond
                                     (and (= type :user) (not (str/blank? filter-email)))
                                     email-query-user

                                     (some? filter-email) email-query

                                     :else all-query)]

    (when (not (true? super-user))
      (throw (Exception. "User not authorized for db-viz")))

    (ui/page
     {}
     (side-bar (pot/map-of email)
                 ;; supported types
               [:div.my-4
                (for [t (-> db-viz-supported-types sort)]
                  [:a.link.mr-2 {:href (str "/app/db/" (name t) "?offset=0&limit=" limit)}
                   t])]
               (when (some? type)
                   ;; pagination
                 [:div.mt-4.mb-2
                  [:a.link.mr-4 {:href (str "/app/db/" (name type) "?offset=" (max 0 (- offset limit))
                                            "&limit=" limit)}
                   "<-"]
                  [:a.link {:href (str "/app/db/" (name type) "?offset=" (+ offset limit)
                                       "&limit=" limit)}
                   "->"]])
                 ;; items
               (if (some? type)
                 (let [query-result
                       (->> (q db query [type filter-email]))
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
                    [:h2.text-lg.font-bold.mb-2 type]
                    [:table.w-full.rounded-lg.overflow-hidden.bg-white.shadow-md
                     [:thead.bg-gray-100
                      [:tr
                       (for [attr all-attributes]
                         [:th.py-2.px-4.text-left.text-gray-600.border-b
                          (str attr)])]]
                     [:tbody
                      (for [row table-rows]
                        [:tr.hover:bg-gray-50
                         (for [attr-val row]
                           [:td.py-2.px-4.border-b.text-gray-900
                            (str attr-val)])])]]])
                 [:div.my-4 [:span "Unsupported type, must be one of: " (str db-viz-supported-types)]])))))

(defn root [{:keys [session biff/db]}]
  (let [user-id              (:uid session)
        {:user/keys [email]} (xt/entity db user-id)]
    (ui/page
     {}
     [:div
      (side-bar (pot/map-of email)
                [:div.flex.flex-col.justify-center.space-y-4
                 [:h1.text-3xl.font-bold.text-primary "App Root Page!"]
                 [:div.rgb-test.rgb-glow 
                  "🚀 RGB Glow Test - This should have animated rainbow borders!"]])])))

(def module
  {:static {"/about/" about-page}
   :routes ["/app" {:middleware [mid/wrap-signed-in]}

            cruddy/crud-routes
            habit/crud-routes
            habit-log/crud-routes
            ;; ical-url/crud-routes
            location/crud-routes
            meditation/crud-routes
            meditation-log/crud-routes
            bm-log/crud-routes

            ;; Main app and DB visualization
            [""    {:get root}]

            ["/db"       {:get db-viz}]
            ["/db/:type" {:get db-viz}]

            ;; user
            ["/my-user"        {:get user/my-user}]
            ["/users"          {:middleware [mid/wrap-user-authz]
                                :post       user/create!}]
            ["/users/:id"      {:middleware [mid/wrap-user-authz]
                                :get        user/view :post user/edit!}]
            ["/users/:id/edit" {:middleware [mid/wrap-user-authz]
                                :get        user/edit-form}]

            ;;
            ;; data viz
            ;;

            ["/dv/habit-logs" {:get habit-log/data-viz}]
            ["/dv/habit-dates" {:get habit-log/habit-dates}]
            ["/dv/meditation-stats" {:get meditation-log/meditation-stats
                                              :post meditation-log/meditation-stats}]
            ;;
            ]})
