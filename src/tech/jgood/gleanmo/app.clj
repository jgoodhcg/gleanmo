(ns tech.jgood.gleanmo.app
  (:require
   [com.biffweb :as biff :refer [q]]
   [potpuri.core :as pot]
   [tech.jgood.gleanmo.app.habit :as habit]
   [tech.jgood.gleanmo.app.habit-log :as habit-log]
   [tech.jgood.gleanmo.app.user :as user]
   [tech.jgood.gleanmo.app.shared :refer [get-last-tx-time nav-bar
                                          time-zone-select zoned-date-time-fmt]]
   [tech.jgood.gleanmo.middleware :as mid]
   [tech.jgood.gleanmo.schema :as schema]
   [tech.jgood.gleanmo.settings :as settings]
   [tech.jgood.gleanmo.ui :as ui]
   [tick.core :as t]
   [xtdb.api :as xt]))

(def about-page
  (ui/page
   {:base/title (str "About " settings/app-name)}
   [:p "This app was made with "
    [:a.link {:href "https://biffweb.com"} "Biff"] "."]))

(def db-viz-supported-types #{:user :habit :habit-log})

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
                                     default-limit)]

    (when (not (true? super-user))
      (throw (Exception. "User not authorized for db-viz")))

    (ui/page
     {}
       (nav-bar (pot/map-of email))
       [:div.my-4
        (for [t (-> db-viz-supported-types sort)]
          [:a.link.mr-2 {:href (str "/app/db/" (name t) "?offset=0&limit=" limit)}
           t])]
       [:div.mt-4.mb-2
        [:a.link.mr-4 {:href (str "/app/db/" (name type) "?offset=" (max 0 (- offset limit))
                                  "&limit=" limit)}
         "<-"]
        [:a.link {:href (str "/app/db/" (name type) "?offset=" (+ offset limit)
                             "&limit=" limit)}
         "->"]]
       (if (some? type)
         (let [query-result
               (->> (q db
                       '{:find  [(pull ?entity [*])]
                         :where [[?entity :xt/id ?id]
                                 [?entity ::schema/type type]]
                         :in    [type]}
                       type))
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
         [:div.my-4 [:span "Unsupported type, must be one of: " (str db-viz-supported-types)]]))))

(defn home [{:keys [session biff/db]}]
  (let [user-id              (:uid session)
        {:user/keys [email]} (xt/entity db user-id)]
    (ui/page
     {}
     [:div
      (nav-bar (pot/map-of email))
      [:div.flex.flex-col.md:flex-row.justify-center
       [:h1.text-3xl.font-bold "Home page"]]])))

(def plugin
  {:static {"/about/" about-page}
   :routes ["/app" {:middleware [mid/wrap-signed-in]}
            ;; Main app and DB visualization
            [""    {:get home}]
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
            ["/new/habit"       {:get habit/new-form}]
            ["/search-habits"   {:post habit/list-page}]
            ["/habits"          {:get habit/list-page :post habit/create!}]
            ["/habits/:id"      {:get habit/view :post habit/edit!}]
            ["/habits/:id/edit" {:get habit/edit-form}]
            ["/habits/:id/delete" {:post habit/soft-delete!}]

            ;; habit-log
            ["/new/habit-log"       {:get habit-log/new-form}]
            ["/habit-logs"          {:get habit-log/list-page :post habit-log/create!}]
            ["/habit-logs/:id"      {:get habit-log/view :post habit-log/edit!}]
            ["/habit-logs/:id/edit" {:get habit-log/edit-form}]
            ["/habit-logs/:id/delete" {:post habit-log/soft-delete!}]]})
