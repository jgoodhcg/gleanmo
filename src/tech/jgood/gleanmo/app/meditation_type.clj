(ns tech.jgood.gleanmo.app.meditation-type
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
  (cond->>  (q db '{:find  (pull ?meditation-type [*])
                    :where [[?meditation-type ::schema/type :meditation-type]
                            [?meditation-type :user/id user-id]
                            (not [?meditation-type ::schema/deleted-at])]
                    :in    [user-id]} (:uid session))
    :always         (sort-by ::schema/created-at)))

(defn single-for-user-query [{:keys [biff/db session xt/id]}]
  (first
   (q db '{:find  (pull ?meditation-type [*])
           :where [[?meditation-type ::schema/type :meditation-type]
                   [?meditation-type :user/id user-id]
                   [?meditation-type :xt/id meditation-type-id]
                   (not [?meditation-type ::schema/deleted-at])]
           :in    [user-id meditation-type-id]} (:uid session) id)))

(defn list-item [{:meditation-type/keys [name notes]
                  id          :xt/id}]
  [:a {:href (str "/app/meditation-types/" id "/edit")}
   [:div.relative.hover:bg-gray-100.transition.duration-150.p-4.border-b.border-gray-200.cursor-pointer.w-full.md:w-96
    [:div.flex.justify-between
     [:h2.text-md.font-bold name]]
    [:p.text-sm.text-gray-600 notes]]])

(defn new-form [{:keys [session biff/db] :as ctx}]
  (let [user-id              (:uid session)
        {:user/keys [email]} (xt/entity db user-id)
        recent-meditation-types        (->> (all-for-user-query ctx)
                                  (sort-by ::schema/created-at)
                                  (reverse)
                                  (take 3))]
    (ui/page
     {}
     (side-bar (pot/map-of email)
               [:div.m-2.w-full.md:w-96.space-y-8
                (biff/form
                 {:hx-post   "/app/meditation-types"
                  :hx-swap   "outerHTML"
                  :hx-select "#create-meditation-type-form"
                  :id        "create-meditation-type-form"}

                 [:div
                  [:h2.text-base.font-semibold.leading-7.text-gray-900 "Create Meditation-Type"]
                  [:p.mt-1.text-sm.leading-6.text-gray-600 "Create a new meditation-type."]]

                 [:div.grid.grid-cols-1.gap-y-6

                  ;; Meditation-Type Name
                  [:div
                   [:label.block.text-sm.font-medium.leading-6.text-gray-900 {:for "meditation-type-name"} "Meditation-Type Name"]
                   [:div.mt-2
                    [:input.rounded-md.shadow-sm.block.w-full.border-0.py-1.5.text-gray-900.focus:ring-2.focus:ring-blue-600
                     {:type "text" :name "meditation-type-name" :autocomplete "off"}]]]

                  ;; Notes
                  [:div
                   [:label.block.text-sm.font-medium.leading-6.text-gray-900 {:for "notes"} "Notes"]
                   [:div.mt-2
                    [:textarea.rounded-md.shadow-sm.block.w-full.border-0.py-1.5.text-gray-900.focus:ring-2.focus:ring-blue-600
                     {:name "notes" :autocomplete "off"}]]]

                  ;; Submit button
                  [:div.mt-2.w-full
                   [:button.bg-blue-500.hover:bg-blue-700.text-white.font-bold.py-2.px-4.rounded.w-full
                    {:type "submit"} "Create Meditation-Type"]]]

                 [:div.my-4 [:span "Recents"]]
                 (->> recent-meditation-types
                      (map list-item)))]))))

(defn create! [{:keys [params session] :as ctx}]
  (let [now (t/now)]
    (biff/submit-tx ctx
                    [(merge {:db/doc-type           :meditation-type
                             ::schema/type          :meditation-type
                             :user/id               (:uid session)
                             :meditation-type/name  (:meditation-type-name params)
                             :meditation-type/label (:meditation-type-name params)
                             :meditation-type/notes (:notes params)
                             ::schema/created-at    now})]))
  {:status  303
   :headers {"location" "/app/new/meditation-type"}})

(defn search-component [{:keys [search-str]}]
  [:div.my-2
   (biff/form
    {:id         "meditation-type-search"
     :hx-post    "/app/search-meditation-types"
     :hx-swap    "outerHTML"
     :hx-trigger "search"
     :hx-select  "#meditation-types-list"
     :hx-target  "#meditation-types-list"}
    [:div.flex.flex-col.justify-center.my-6

     [:input.form-control.w-full.md:w-96.mb-2
      (merge {:type        "search"
              :name        "search"
              :placeholder "Begin Typing To Search Meditation-Types..."
              :script      "on keyup setURLParameter(me.name, me.value) then htmx.trigger('#meditation-type-search', 'search', {})"}

             (when (not (str/blank? search-str))
               {:value search-str}))]])])

(defn list-page
  "Accepts GET and POST. POST is for search form as body."
  [{:keys [session biff/db params]
    :as   ctx}]
  (let [user-id             (:uid session)
        {:user/keys
         [email time-zone]} (xt/entity db user-id)
        meditation-types           (all-for-user-query ctx)
        edit-id             (some-> params :edit (UUID/fromString))
        search-str          (or (some-> params :search search-str-xform)
                                "")]
    (ui/page
     {}
     [:div
      (side-bar (pot/map-of email)
                [:div.my-4
                 (link-button {:href  "/app/new/meditation-type"
                               :label "Create Meditation-Type"})]
                (search-component (pot/map-of search-str ))
                [:div {:id "meditation-types-list"}
                 (->> meditation-types
                      (filter (fn [{:meditation-type/keys             [name notes]
                                   id                         :xt/id}]
                                (let [matches-name  (str/includes? (str/lower-case (str name)) search-str)
                                      matches-notes (str/includes? (str/lower-case (str notes)) search-str)]
                                  (or matches-name
                                      matches-notes))))
                      (map (fn [z] (list-item (-> z (assoc :edit-id edit-id))))))])])))

(defn edit! [{:keys [params] :as ctx}]
  (let [id              (-> params :id UUID/fromString)
        meditation-type (single-for-user-query (merge ctx {:xt/id id}))
        name            (:name params)
        notes           (-> params :notes str)]
    ;; Authz is that the user owns the meditation-type
    (if (some? meditation-type)
      (do
        (biff/submit-tx ctx
                        [{:db/op                 :update
                          :db/doc-type           :meditation-type
                          :xt/id                 id
                          :meditation-type/name  name
                          :meditation-type/label name
                          :meditation-type/notes notes}])
        {:status  303
         :headers {"location" (str "/app/meditation-types/" id "/edit")}})
      {:status 403
       :body   "Not authorized to edit that meditation-type"})))

(defn edit-form [{:keys [path-params
                         session
                         biff/db]
                  :as   ctx}]
  (let [meditation-type-id             (-> path-params :id UUID/fromString)
        user-id              (:uid session)
        {email :user/email}  (xt/entity db user-id)
        time-zone            (get-user-time-zone ctx)
        meditation-type                (single-for-user-query (merge ctx {:xt/id meditation-type-id}))
        latest-tx-time       (-> (get-last-tx-time (merge ctx {:xt/id meditation-type-id}))
                                 (t/in (t/zone time-zone))
                                 (->> (t/format (t/formatter zoned-date-time-fmt))))
        formatted-created-at (-> meditation-type
                                 ::schema/created-at
                                 (t/in (t/zone time-zone))
                                 (->> (t/format (t/formatter zoned-date-time-fmt))))]
    (ui/page
     {}
     [:div {:id "meditation-type-edit-page"}
      (side-bar (pot/map-of email)

                ;; edit-form
                (biff/form
                 {:hx-post   (str "/app/meditation-types/" meditation-type-id)
                  :hx-swap   "outerHTML"
                  :hx-select "#meditation-type-edit-form"
                  :id        "meditation-type-edit-form"}

                 [:div.w-full.md:w-96.p-2
                  [:input {:type "hidden" :name "id" :value meditation-type-id}]

                  [:div.grid.grid-cols-1.gap-y-6

                   ;; Meditation-Type Name
                   [:div
                    [:label.block.text-sm.font-medium.leading-6.text-gray-900
                     {:for "meditation-type-name"} "Meditation-Type Name"]
                    [:div.mt-2
                     [:input.rounded-md.shadow-sm.block.w-full.border-0.py-1.5.text-gray-900.focus:ring-2.focus:ring-blue-600
                      {:type "text" :name "name" :value (:meditation-type/name meditation-type)}]]]

                   ;; Notes
                   [:div
                    [:label.block.text-sm.font-medium.leading-6.text-gray-900 {:for "notes"} "Notes"]
                    [:div.mt-2
                     [:textarea.rounded-md.shadow-sm.block.w-full.border-0.py-1.5.text-gray-900.focus:ring-2.focus:ring-blue-600
                      {:name "notes"} (:meditation-type/notes meditation-type)]]]

                   ;; Submit button
                   [:div.mt-2.w-full
                    [:button.bg-blue-500.hover:bg-blue-700.text-white.font-bold.py-2.px-4.rounded.w-full
                     {:type       "submit"}
                     "Update Meditation-Type"]]

                   [:div.mt-4.flex.flex-col
                    [:span.text-gray-500 (str "last updated: " latest-tx-time)]
                    [:span.text-gray-500 (str "created at: " (or formatted-created-at (::schema/created-at meditation-type)))]]]])

                ;; delete form
                (biff/form
                 {:action (str "/app/meditation-types/" meditation-type-id "/delete") :method "post"}
                 [:div.w-full.md:w-96.p-2.my-4
                  [:input.text-center.bg-red-100.hover:bg-red-500.hover:text-white.text-black.font-bold.py-2.px-4.rounded.w-full
                   {:type "submit" :value "Delete"}]]))])))

(defn view [{:keys [path-params
                    session
                    biff/db]
             :as   ctx}]
  (let [meditation-type-id         (-> path-params :id UUID/fromString)
        user-id             (:uid session)
        {email :user/email} (xt/entity db user-id)
        meditation-type            (single-for-user-query (merge ctx {:xt/id meditation-type-id}))]
    (ui/page
     {}
     [:div
      (side-bar (pot/map-of email)
                (list-item meditation-type))])))

(defn soft-delete! [{:keys [path-params params]
                     :as   ctx}]
  (let [meditation-type-id (-> path-params :id UUID/fromString)
        meditation-type    (single-for-user-query (merge ctx {:xt/id meditation-type-id}))
        now         (t/now)]
    (if (some? meditation-type)
      (do
        (biff/submit-tx ctx
                        [{:db/op              :update
                          :db/doc-type        :meditation-type
                          :xt/id              meditation-type-id
                          ::schema/deleted-at now}])
        {:status  303
         :headers {"location" "/app/meditation-types"}})
      {:status 403
       :body   "Not authorized to edit that meditation-type"})))
