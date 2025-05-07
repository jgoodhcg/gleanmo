(ns tech.jgood.gleanmo.app.ical-url
  (:require
   [clojure.string :as str]
   [com.biffweb :as biff :refer [q]]
   [potpuri.core :as pot]
   [tech.jgood.gleanmo.app.shared :refer [get-last-tx-time get-user-time-zone
                                          link-button search-str-xform
                                          side-bar zoned-date-time-fmt]]
   [tech.jgood.gleanmo.crud.routes :as crud]
   [tech.jgood.gleanmo.db.queries :as db]
   [tech.jgood.gleanmo.schema :refer [schema]]
   [tech.jgood.gleanmo.schema.meta :as sm]
   [tech.jgood.gleanmo.ui :as ui]
   [tick.core :as t])
  (:import
   [java.util UUID]))

(def crud-routes
  (crud/gen-routes {:entity-key :ical-url
                    :entity-str "ical-url"
                    :plural-str "iCal URLs"
                    :schema     schema}))

(defn all-for-user-query [{:keys [biff/db session]}]
  (cond->>  (q db '{:find  (pull ?ical-url [*])
                    :where [[?ical-url ::sm/type :ical-url]
                            [?ical-url :ical-url/user-id user-id]
                            (not [?ical-url ::sm/deleted-at])]
                    :in    [user-id]} (:uid session))
    :always         (sort-by ::sm/created-at)))

(defn single-for-user-query [{:keys [biff/db session xt/id]}]
  (first
   (q db '{:find  (pull ?ical-url [*])
           :where [[?ical-url ::sm/type :ical-url]
                   [?ical-url :ical-url/user-id user-id]
                   [?ical-url :xt/id ical-url-id]
                   (not [?ical-url ::sm/deleted-at])]
           :in    [user-id ical-url-id]} (:uid session) id)))

(defn list-item [{:ical-url/keys [name url notes]
                  id          :xt/id}]
  [:a {:href (str "/app/ical-urls/" id "/edit")}
   [:div.relative.hover:bg-gray-100.transition.duration-150.p-4.border-b.border-gray-200.cursor-pointer.w-full.md:w-96
    [:div.flex.justify-between
     [:h2.text-md.font-bold name]]
    [:p.text-sm.text-gray-600 url]
    [:p.text-sm.text-gray-600 notes]]])

(defn new-form [{:keys [session biff/db] :as ctx}]
  (let [user-id              (:uid session)
        {:user/keys [email]} (db/get-entity-by-id db user-id)
        recent-ical-urls        (->> (all-for-user-query ctx)
                                  (sort-by ::sm/created-at)
                                  (reverse)
                                  (take 3))]
    (ui/page
     {}
     (side-bar (pot/map-of email)
               [:div.m-2.w-full.md:w-96.space-y-8
                (biff/form
                 {:hx-post   "/app/ical-urls"
                  :hx-swap   "outerHTML"
                  :hx-select "#create-ical-url-form"
                  :id        "create-ical-url-form"}

                 [:div
                  [:h2.text-base.font-semibold.leading-7.text-gray-900 "Create iCal URL"]
                  [:p.mt-1.text-sm.leading-6.text-gray-600 "Create a new iCal URL."]]

                 [:div.grid.grid-cols-1.gap-y-6

                  ;; iCal URL Name
                  [:div
                   [:label.block.text-sm.font-medium.leading-6.text-gray-900 {:for "ical-url-name"} "iCal URL Name"]
                   [:div.mt-2
                    [:input.rounded-md.shadow-sm.block.w-full.border-0.py-1.5.text-gray-900.focus:ring-2.focus:ring-blue-600
                     {:type "text" :name "ical-url-name" :autocomplete "off"}]]]

                  ;; URL
                  [:div
                   [:label.block.text-sm.font-medium.leading-6.text-gray-900 {:for "url"} "URL"]
                   [:div.mt-2
                    [:input.rounded-md.shadow-sm.block.w-full.border-0.py-1.5.text-gray-900.focus:ring-2.focus:ring-blue-600
                     {:type "url" :name "url" :autocomplete "off"}]]]

                  ;; Notes
                  [:div
                   [:label.block.text-sm.font-medium.leading-6.text-gray-900 {:for "notes"} "Notes"]
                   [:div.mt-2
                    [:textarea.rounded-md.shadow-sm.block.w-full.border-0.py-1.5.text-gray-900.focus:ring-2.focus:ring-blue-600
                     {:name "notes" :autocomplete "off"}]]]

                  ;; Submit button
                  [:div.mt-2.w-full
                   [:button.bg-blue-500.hover:bg-blue-700.text-white.font-bold.py-2.px-4.rounded.w-full
                    {:type "submit"} "Create iCal URL"]]]

                 [:div.my-4 [:span "Recents"]]
                 (->> recent-ical-urls
                      (map list-item)))]))))

(defn create! [{:keys [params session] :as ctx}]
  (let [now (t/now)]
    (biff/submit-tx ctx
                    [(merge {:db/doc-type        :ical-url
                             ::sm/type       :ical-url
                             :ical-url/user-id   (:uid session)
                             :ical-url/name      (:ical-url-name params)
                             :ical-url/label     (:ical-url-name params)
                             :ical-url/url       (:url params)
                             :ical-url/notes     (:notes params)
                             ::sm/created-at now})]))
  {:status  303
   :headers {"location" "/app/new/ical-url"}})

(defn search-component [{:keys [search-str]}]
  [:div.my-2
   (biff/form
    {:id         "ical-url-search"
     :hx-post    "/app/search-ical-urls"
     :hx-swap    "outerHTML"
     :hx-trigger "search"
     :hx-select  "#ical-urls-list"
     :hx-target  "#ical-urls-list"}
    [:div.flex.flex-col.justify-center.my-6

     [:input.form-control.w-full.md:w-96.mb-2
      (merge {:type        "search"
              :name        "search"
              :placeholder "Begin Typing To Search iCal URLs..."
              :script      "on keyup setURLParameter(me.name, me.value) then htmx.trigger('#ical-url-search', 'search', {})"}

             (when (not (str/blank? search-str))
               {:value search-str}))]])])

(defn list-page
  "Accepts GET and POST. POST is for search form as body."
  [{:keys [session biff/db params]
    :as   ctx}]
  (let [user-id             (:uid session)
        {:user/keys
         [email time-zone]} (db/get-entity-by-id db user-id)
        ical-urls           (all-for-user-query ctx)
        edit-id             (some-> params :edit (UUID/fromString))
        search-str          (or (some-> params :search search-str-xform)
                                "")]
    (ui/page
     {}
     [:div
      (side-bar (pot/map-of email)
                [:div.my-4
                 (link-button {:href  "/app/new/ical-url"
                               :label "Create iCal URL"})]
                (search-component (pot/map-of search-str ))
                [:div {:id "ical-urls-list"}
                 (->> ical-urls
                      (filter (fn [{:ical-url/keys             [name url notes]
                                   id                         :xt/id}]
                                (let [matches-name  (str/includes? (str/lower-case (str name)) search-str)
                                      matches-url (str/includes? (str/lower-case (str url)) search-str)
                                      matches-notes (str/includes? (str/lower-case (str notes)) search-str)]
                                  (or matches-name
                                      matches-url
                                      matches-notes))))
                      (map (fn [z] (list-item (-> z (assoc :edit-id edit-id))))))])])))

(defn edit! [{:keys [params] :as ctx}]
  (let [id       (-> params :id UUID/fromString)
        ical-url (single-for-user-query (merge ctx {:xt/id id}))
        name     (:name params)
        url      (:url params)
        notes    (-> params :notes str)]
    ;; Authz is that the user owns the iCal URL
    (if (some? ical-url)
      (do
        (biff/submit-tx ctx
                        [{:db/op          :update
                          :db/doc-type    :ical-url
                          :xt/id          id
                          :ical-url/name  name
                          :ical-url/label name
                          :ical-url/url   url
                          :ical-url/notes notes}])
        {:status  303
         :headers {"location" (str "/app/ical-urls/" id "/edit")}})
      {:status 403
       :body   "Not authorized to edit that iCal URL"})))

(defn edit-form [{:keys [path-params
                         session
                         biff/db]
                  :as   ctx}]
  (let [ical-url-id             (-> path-params :id UUID/fromString)
        user-id              (:uid session)
        {email :user/email}  (db/get-entity-by-id db user-id)
        time-zone            (get-user-time-zone ctx)
        ical-url                (single-for-user-query (merge ctx {:xt/id ical-url-id}))
        latest-tx-time       (-> (get-last-tx-time (merge ctx {:xt/id ical-url-id}))
                                 (t/in (t/zone time-zone))
                                 (->> (t/format (t/formatter zoned-date-time-fmt))))
        formatted-created-at (-> ical-url
                                 ::sm/created-at
                                 (t/in (t/zone time-zone))
                                 (->> (t/format (t/formatter zoned-date-time-fmt))))]
    (ui/page
     {}
     [:div {:id "ical-url-edit-page"}
      (side-bar (pot/map-of email)

                ;; edit-form
                (biff/form
                 {:hx-post   (str "/app/ical-urls/" ical-url-id)
                  :hx-swap   "outerHTML"
                  :hx-select "#ical-url-edit-form"
                  :id        "ical-url-edit-form"}

                 [:div.w-full.md:w-96.p-2
                  [:input {:type "hidden" :name "id" :value ical-url-id}]

                  [:div.grid.grid-cols-1.gap-y-6

                   ;; iCal URL Name
                   [:div
                    [:label.block.text-sm.font-medium.leading-6.text-gray-900
                     {:for "ical-url-name"} "iCal URL Name"]
                    [:div.mt-2
                     [:input.rounded-md.shadow-sm.block.w-full.border-0.py-1.5.text-gray-900.focus:ring-2.focus:ring-blue-600
                      {:type "text" :name "name" :value (:ical-url/name ical-url)}]]]

                   ;; URL
                   [:div
                    [:label.block.text-sm.font-medium.leading-6.text-gray-900 {:for "url"} "URL"]
                    [:div.mt-2
                     [:input.rounded-md.shadow-sm.block.w-full.border-0.py-1.5.text-gray-900.focus:ring-2.focus:ring-blue-600
                      {:type "url" :name "url" :value (:ical-url/url ical-url)}]]]

                   ;; Notes
                   [:div
                    [:label.block.text-sm.font-medium.leading-6.text-gray-900 {:for "notes"} "Notes"]
                    [:div.mt-2
                     [:textarea.rounded-md.shadow-sm.block.w-full.border-0.py-1.5.text-gray-900.focus:ring-2.focus:ring-blue-600
                      {:name "notes"} (:ical-url/notes ical-url)]]]

                   ;; Submit button
                   [:div.mt-2.w-full
                    [:button.bg-blue-500.hover:bg-blue-700.text-white.font-bold.py-2.px-4.rounded.w-full
                     {:type       "submit"}
                     "Update iCal URL"]]

                   [:div.mt-4.flex.flex-col
                    [:span.text-gray-500 (str "last updated: " latest-tx-time)]
                    [:span.text-gray-500 (str "created at: " (or formatted-created-at (::sm/created-at ical-url)))]]]])

                ;; delete form
                (biff/form
                 {:action (str "/app/ical-urls/" ical-url-id "/delete") :method "post"}
                 [:div.w-full.md:w-96.p-2.my-4
                  [:input.text-center.bg-red-100.hover:bg-red-500.hover:text-white.text-black.font-bold.py-2.px-4.rounded.w-full
                   {:type "submit" :value "Delete"}]]))])))

(defn view [{:keys [path-params
                    session
                    biff/db]
             :as   ctx}]
  (let [ical-url-id         (-> path-params :id UUID/fromString)
        user-id             (:uid session)
        {email :user/email} (db/get-entity-by-id db user-id)
        ical-url            (single-for-user-query (merge ctx {:xt/id ical-url-id}))]
    (ui/page
     {}
     [:div
      (side-bar (pot/map-of email)
                (list-item ical-url))])))

(defn soft-delete! [{:keys [path-params params]
                     :as   ctx}]
  (let [ical-url-id (-> path-params :id UUID/fromString)
        ical-url    (single-for-user-query (merge ctx {:xt/id ical-url-id}))
        now         (t/now)]
    (if (some? ical-url)
      (do
        (biff/submit-tx ctx
                        [{:db/op              :update
                          :db/doc-type        :ical-url
                          :xt/id              ical-url-id
                          ::sm/deleted-at now}])
        {:status  303
         :headers {"location" "/app/ical-urls"}})
      {:status 403
       :body   "Not authorized to edit that iCal URL"})))
