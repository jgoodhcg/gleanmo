(ns tech.jgood.gleanmo.app
  (:require
   [clojure.string :as str]
   [com.biffweb :as biff :refer [q]]
   [potpuri.core :as pot]
   [tech.jgood.gleanmo.app.habit :as habit]
   [tech.jgood.gleanmo.app.habit-log :as habit-log]
   [tech.jgood.gleanmo.app.ical-url :as ical-url]
   [tech.jgood.gleanmo.app.location :as location]
   [tech.jgood.gleanmo.app.meditation-log :as meditation-log]
   [tech.jgood.gleanmo.app.meditation-type :as meditation-type]
   [tech.jgood.gleanmo.app.shared :refer [side-bar]]
   [tech.jgood.gleanmo.app.user :as user]
   [tech.jgood.gleanmo.middleware :as mid]
   [tech.jgood.gleanmo.schema :as schema]
   [tech.jgood.gleanmo.settings :as settings]
   [tech.jgood.gleanmo.ui :as ui]
   [xtdb.api :as xt]))

(def about-page
  (ui/page
   {:base/title (str "About " settings/app-name)}
   [:p "This app was made with "
    [:a.link {:href "https://biffweb.com"} "Biff"] "."]))

(def db-viz-supported-types #{:user :habit :habit-log :meditation-type :meditation-log :location :ical-url})

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
                                             [?entity ::schema/type type]]
                                     :in    [[type email]]}
        email-query                '{:find  [(pull ?entity [*])]
                                     :where [[?entity :xt/id ?id]
                                             [?entity ::schema/type type]
                                             [?user   :user/email email]
                                             [?entity :user/id ?user]]
                                     :in    [[type email]]}
        email-query-user           '{:find  [(pull ?entity [*])]
                                     :where [[?entity :xt/id ?id]
                                             [?entity ::schema/type type]
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
                              (sort-by (juxt ::schema/created-at :user/id :xt/id))
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
                [:div.flex.flex-col.md:flex-row.justify-center
                 [:h1.text-3xl.font-bold "App Root Page!"]])])))

(def module
  {:static {"/about/" about-page}
   :routes ["/app" {:middleware [mid/wrap-signed-in]}
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

            ;; habit
            ["/new/habit"         {:get habit/new-form}]
            ["/search-habits"     {:post habit/list-page}]
            ["/habits"            {:get habit/list-page :post habit/create!}]
            ["/habits/:id"        {:get habit/view :post habit/edit!}]
            ["/habits/:id/edit"   {:get habit/edit-form}]
            ["/habits/:id/delete" {:post habit/soft-delete!}]

            ;; habit-log
            ["/new/habit-log"         {:get habit-log/new-form}]
            ["/habit-logs"            {:get habit-log/list-page :post habit-log/create!}]
            ["/habit-logs/:id"        {:get habit-log/view :post habit-log/edit!}]
            ["/habit-logs/:id/edit"   {:get habit-log/edit-form}]
            ["/habit-logs/:id/delete" {:post habit-log/soft-delete!}]

            ;; location
            ["/new/location"          {:get location/new-form}]
            ["/search-locations"      {:post location/list-page}]
            ["/locations"             {:get location/list-page :post location/create!}]
            ["/locations/:id"         {:get location/view :post location/edit!}]
            ["/locations/:id/edit"    {:get location/edit-form}]
            ["/locations/:id/delete"  {:post location/soft-delete!}]

            ;;
            ;; mbsr
            ;;

            ;; meditation-type
            ["/new/meditation-type"         {:get meditation-type/new-form}]
            ["/search-meditation-types"     {:post meditation-type/list-page}]
            ["/meditation-types"            {:get meditation-type/list-page :post meditation-type/create!}]
            ["/meditation-types/:id"        {:get meditation-type/view :post meditation-type/edit!}]
            ["/meditation-types/:id/edit"   {:get meditation-type/edit-form}]
            ["/meditation-types/:id/delete" {:post meditation-type/soft-delete!}]

            ;; meditation-log
            ["/new/meditation-log"         {:get meditation-log/new-form}]
            ["/meditation-logs"            {:get meditation-log/list-page :post meditation-log/create!}]
            ["/meditation-logs/:id"        {:get meditation-log/view :post meditation-log/edit!}]
            ["/meditation-logs/:id/edit"   {:get meditation-log/edit-form}]
            ["/meditation-logs/:id/delete" {:post meditation-log/soft-delete!}]

            ;;
            ;; time tracking
            ;;

            ;; ical-url
            ["/new/ical-url"            {:get ical-url/new-form}]
            ["/search-ical-urls"        {:post ical-url/list-page}]
            ["/ical-urls"               {:get ical-url/list-page :post ical-url/create!}]
            ["/ical-urls/:id"           {:get ical-url/view :post ical-url/edit!}]
            ["/ical-urls/:id/edit"      {:get ical-url/edit-form}]
            ["/ical-urls/:id/delete"    {:post ical-url/soft-delete!}]

            ;;
            ]})
