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

(defn db-viz [{:keys [session biff/db]}]
  (let [{:user/keys [email]} (xt/entity db (:uid session))
        query-result
        (->> (q db
                '{:find  [(pull ?entity [*])]
                  :where [[?entity :xt/id]]}))
        all-entities
        (->> query-result
             (map first)
             (filter #(uuid? (:xt/id %)))
             (map #(into (sorted-map) %))
             (group-by ::schema/type))]

    (ui/page
     {}
     (nav-bar (pot/map-of email))
     ;; a table for each entity type
     (for [[t entities] all-entities]
       (let [#_#_entities (->> entities (map #(dissoc % ::schema/type)))
             all-attributes
             (->> entities
                  (mapcat keys)
                  distinct
                  sort)
             table-rows
             (map (fn [entity]
                    (map (fn [attr]
                           (get entity attr "_"))  ; Replace "N/A" with your preferred placeholder for missing values
                         all-attributes))
                  entities)]
         [:div.my-4
          [:h2.text-lg.font-bold.mb-2 t]
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
                  (str attr-val)])])]]]))

     [:.h-6])))

(defn app [{:keys [session biff/db]}]
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
            [""    {:get app}]
            ["/db" {:get db-viz}]

            ;; Specific route for the current user

            ;; User-related routes
            ["/my-user" {:get user/me}]
            ["/users"   {:middleware [mid/wrap-user-authz]
                         :post       user/post!}
             ["/:id"      {:get    user/view
                           :put    user/put!
                           :patch  user/patch!
                           :delete user/delete!}]
             ["/:id/edit" {:middleware [mid/wrap-user-authz]
                           :get        user/edit-form}]]

            #_#_#_#_
            ;; Habit-related routes
            ["/habits"          {:get habit/list :post habit/post!}]
            ["/habits/new"      {:get habit/new-form}]
            ["/habits/:id"      {:get habit/view :put habit/put! :patch habit/patch! :delete habit/delete!}]
            ["/habits/:id/edit" {:get habit/edit-form}]

            #_#_#_#_
            ;; Habit log-related routes
            ["/habit-logs"          {:get habit-log/list :post habit-log/post!}]
            ["/habit-logs/new"      {:get habit-log/new-form}]
            ["/habit-logs/:id"      {:get   habit-log/view   :put    habit-log/put!
                                     :patch habit-log/patch! :delete habit-log/delete!}]
            ["/habit-logs/:id/edit" {:get habit-log/edit-form}]]})
