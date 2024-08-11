(ns tech.jgood.gleanmo.app.location
  (:require
   [clojure.string :as str]
   [com.biffweb :as biff :refer [q]]
   [potpuri.core :as pot]
   [tech.jgood.gleanmo.app.shared :refer [get-last-tx-time get-user-time-zone
                                          link-button search-str-xform
                                          side-bar zoned-date-time-fmt]]
   [tech.jgood.gleanmo.schema :as schema]
   [tech.jgood.gleanmo.ui :as ui]
   [tick.core :as t]
   [xtdb.api :as xt])
  (:import
   [java.util UUID]))

(defn all-for-user-query [{:keys [biff/db session]}]
  (cond->>  (q db '{:find  (pull ?location [*])
                    :where [[?location ::schema/type :location]
                            [?location :user/id user-id]
                            (not [?location ::schema/deleted-at])]
                    :in    [user-id]} (:uid session))
    :always         (sort-by ::schema/created-at)))

(defn single-for-user-query [{:keys [biff/db session xt/id]}]
  (first
   (q db '{:find  (pull ?location [*])
           :where [[?location :xt/id location-id]
                   [?location ::schema/type :location]
                   [?location :user/id user-id]
                   (not [?location ::schema/deleted-at])]
           :in    [user-id location-id]} (:uid session) id)))

(defn list-item [{:location/keys [name notes]
                  id          :xt/id}]
  [:a {:href (str "/app/locations/" id "/edit")}
   [:div.relative.hover:bg-gray-100.transition.duration-150.p-4.border-b.border-gray-200.cursor-pointer.w-full.md:w-96
    [:div.flex.justify-between
     [:h2.text-md.font-bold name]]
    [:p.text-sm.text-gray-600 notes]]])

(defn new-form [{:keys [session biff/db] :as ctx}]
  (let [user-id              (:uid session)
        {:user/keys [email]} (xt/entity db user-id)
        recent-locations        (->> (all-for-user-query ctx)
                                  (sort-by ::schema/created-at)
                                  (reverse)
                                  (take 3))]
    (ui/page
     {}
     (side-bar (pot/map-of email)
               [:div.m-2.w-full.md:w-96.space-y-8
                (biff/form
                 {:hx-post   "/app/locations"
                  :hx-swap   "outerHTML"
                  :hx-select "#create-location-form"
                  :id        "create-location-form"}

                 [:div
                  [:h2.text-base.font-semibold.leading-7.text-gray-900 "Create Location"]
                  [:p.mt-1.text-sm.leading-6.text-gray-600 "Create a new location."]]

                 [:div.grid.grid-cols-1.gap-y-6

                  ;; Location Name
                  [:div
                   [:label.block.text-sm.font-medium.leading-6.text-gray-900 {:for "location-name"} "Location Name"]
                   [:div.mt-2
                    [:input.rounded-md.shadow-sm.block.w-full.border-0.py-1.5.text-gray-900.focus:ring-2.focus:ring-blue-600
                     {:type "text" :name "location-name" :autocomplete "off"}]]]

                  ;; Notes
                  [:div
                   [:label.block.text-sm.font-medium.leading-6.text-gray-900 {:for "notes"} "Notes"]
                   [:div.mt-2
                    [:textarea.rounded-md.shadow-sm.block.w-full.border-0.py-1.5.text-gray-900.focus:ring-2.focus:ring-blue-600
                     {:name "notes" :autocomplete "off"}]]]

                  ;; Submit button
                  [:div.mt-2.w-full
                   [:button.bg-blue-500.hover:bg-blue-700.text-white.font-bold.py-2.px-4.rounded.w-full
                    {:type "submit"} "Create Location"]]]

                 [:div.my-4 [:span "Recents"]]
                 (->> recent-locations
                      (map list-item)))]))))

(defn create! [{:keys [params session] :as ctx}]
  (let [now       (t/now)]
    (biff/submit-tx ctx
                    [(merge {:db/doc-type        :location
                             ::schema/type       :location
                             :user/id            (:uid session)
                             :location/name      (:location-name params)
                             :location/notes     (:notes params)
                             ::schema/created-at now})]))
  {:status  303
   :headers {"location" "/app/new/location"}})

(defn search-component [{:keys [search-str]}]
  [:div.my-2
   (biff/form
    {:id         "location-search"
     :hx-post    "/app/search-locations"
     :hx-swap    "outerHTML"
     :hx-trigger "search"
     :hx-select  "#locations-list"
     :hx-target  "#locations-list"}
    [:div.flex.flex-col.justify-center.my-6

     [:input.form-control.w-full.md:w-96.mb-2
      (merge {:type        "search"
              :name        "search"
              :placeholder "Begin Typing To Search Locations..."
              :script      "on keyup setURLParameter(me.name, me.value) then htmx.trigger('#location-search', 'search', {})"}

             (when (not (str/blank? search-str))
               {:value search-str}))]])])

(defn list-page
  "Accepts GET and POST. POST is for search form as body."
  [{:keys [session biff/db params]
    :as   ctx}]
  (let [user-id             (:uid session)
        {:user/keys
         [email time-zone]} (xt/entity db user-id)
        locations           (all-for-user-query (merge ctx {:archived  true}))
        edit-id             (some-> params :edit (UUID/fromString))
        search-str          (or (some-> params :search search-str-xform)
                                "")]
    (ui/page
     {}
     [:div
      (side-bar (pot/map-of email)
                [:div.my-4
                 (link-button {:href  "/app/new/location"
                               :label "Create Location"})]
                (search-component (pot/map-of search-str ))
                [:div {:id "locations-list"}
                 (->> locations
                      (filter (fn [{:location/keys             [name notes]
                                   id                         :xt/id}]
                                (let [matches-name  (str/includes? (str/lower-case (str name)) search-str)
                                      matches-notes (str/includes? (str/lower-case (str notes)) search-str)]
                                  (or matches-name
                                      matches-notes))))
                      (map (fn [z] (list-item (-> z (assoc :edit-id edit-id))))))])])))

(defn edit! [{:keys [params] :as ctx}]
  (let [id       (-> params :id UUID/fromString)
        location (single-for-user-query (merge ctx {:xt/id id}))
        name     (:name params)
        notes    (-> params :notes str)]
    ;; Authz is that the user owns the location
    (if (some? location)
      (do
        (biff/submit-tx ctx
                        [{:db/op          :update
                          :db/doc-type    :location
                          :xt/id          id
                          :location/name  name
                          :location/notes notes}])
        {:status  303
         :headers {"location" (str "/app/locations/" id "/edit")}})
      {:status 403
       :body   "Not authorized to edit that location"})))

(defn edit-form [{:keys [path-params
                         session
                         biff/db]
                  :as   ctx}]
  (let [location-id             (-> path-params :id UUID/fromString)
        user-id              (:uid session)
        {email :user/email}  (xt/entity db user-id)
        time-zone            (get-user-time-zone ctx)
        location                (single-for-user-query (merge ctx {:xt/id location-id}))
        latest-tx-time       (-> (get-last-tx-time (merge ctx {:xt/id location-id}))
                                 (t/in (t/zone time-zone))
                                 (->> (t/format (t/formatter zoned-date-time-fmt))))
        formatted-created-at (-> location
                                 ::schema/created-at
                                 (t/in (t/zone time-zone))
                                 (->> (t/format (t/formatter zoned-date-time-fmt))))]
    (ui/page
     {}
     [:div {:id "location-edit-page"}
      (side-bar (pot/map-of email)

                ;; edit-form
                (biff/form
                 {:hx-post   (str "/app/locations/" location-id)
                  :hx-swap   "outerHTML"
                  :hx-select "#location-edit-form"
                  :id        "location-edit-form"}

                 [:div.w-full.md:w-96.p-2
                  [:input {:type "hidden" :name "id" :value location-id}]

                  [:div.grid.grid-cols-1.gap-y-6

                   ;; Location Name
                   [:div
                    [:label.block.text-sm.font-medium.leading-6.text-gray-900
                     {:for "location-name"} "Location Name"]
                    [:div.mt-2
                     [:input.rounded-md.shadow-sm.block.w-full.border-0.py-1.5.text-gray-900.focus:ring-2.focus:ring-blue-600
                      {:type "text" :name "name" :value (:location/name location)}]]]

                   ;; Notes
                   [:div
                    [:label.block.text-sm.font-medium.leading-6.text-gray-900 {:for "notes"} "Notes"]
                    [:div.mt-2
                     [:textarea.rounded-md.shadow-sm.block.w-full.border-0.py-1.5.text-gray-900.focus:ring-2.focus:ring-blue-600
                      {:name "notes"} (:location/notes location)]]]

                   ;; Submit button
                   [:div.mt-2.w-full
                    [:button.bg-blue-500.hover:bg-blue-700.text-white.font-bold.py-2.px-4.rounded.w-full
                     {:type       "submit"}
                     "Update Location"]]

                   [:div.mt-4.flex.flex-col
                    [:span.text-gray-500 (str "last updated: " latest-tx-time)]
                    [:span.text-gray-500 (str "created at: " (or formatted-created-at (::schema/created-at location)))]]]])

                ;; delete form
                (biff/form
                 {:action (str "/app/locations/" location-id "/delete") :method "post"}
                 [:div.w-full.md:w-96.p-2.my-4
                  [:input.text-center.bg-red-100.hover:bg-red-500.hover:text-white.text-black.font-bold.py-2.px-4.rounded.w-full
                   {:type "submit" :value "Delete"}]]))])))

(defn view [{:keys [path-params
                    session
                    biff/db]
             :as   ctx}]
  (let [location-id         (-> path-params :id UUID/fromString)
        user-id             (:uid session)
        {email :user/email} (xt/entity db user-id)
        location            (single-for-user-query (merge ctx {:xt/id location-id}))]
    (ui/page
     {}
     [:div
      (side-bar (pot/map-of email)
                (list-item location))])))

(defn soft-delete! [{:keys [path-params params]
                     :as   ctx}]
  (let [location-id (-> path-params :id UUID/fromString)
        location    (single-for-user-query (merge ctx {:xt/id location-id}))
        now         (t/now)]
    (if (some? location)
      (do
        (biff/submit-tx ctx
                        [{:db/op              :update
                          :db/doc-type        :location
                          :xt/id              location-id
                          ::schema/deleted-at now}])
        {:status  303
         :headers {"location" "/app/locations"}})
      {:status 403
       :body   "Not authorized to edit that location"})))
