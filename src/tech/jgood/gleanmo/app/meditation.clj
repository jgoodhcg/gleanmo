(ns tech.jgood.gleanmo.app.meditation
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
  (crud/gen-routes {:entity-key :meditation
                    :entity-str "meditation"
                    :plural-str "meditations"
                    :schema     schema}))

(defn all-for-user-query [{:keys [biff/db session]}]
  (cond->>  (q db '{:find  (pull ?meditation [*])
                    :where [[?meditation ::sm/type :meditation]
                            [?meditation :user/id user-id]
                            (not [?meditation ::sm/deleted-at])]
                    :in    [user-id]} (:uid session))
    :always         (sort-by ::sm/created-at)))

(defn single-for-user-query [{:keys [biff/db session xt/id]}]
  (first
   (q db '{:find  (pull ?meditation [*])
           :where [[?meditation ::sm/type :meditation]
                   [?meditation :user/id user-id]
                   [?meditation :xt/id meditation-id]
                   (not [?meditation ::sm/deleted-at])]
           :in    [user-id meditation-id]} (:uid session) id)))

(defn list-item [{:meditation/keys [name notes]
                  id          :xt/id}]
  [:a {:href (str "/app/meditations/" id "/edit")}
   [:div.relative.hover:bg-gray-100.transition.duration-150.p-4.border-b.border-gray-200.cursor-pointer.w-full.md:w-96
    [:div.flex.justify-between
     [:h2.text-md.font-bold name]]
    [:p.text-sm.text-gray-600 notes]]])

(defn new-form [{:keys [session biff/db] :as ctx}]
  (let [user-id              (:uid session)
        {:user/keys [email]} (db/get-entity-by-id db user-id)
        recent-meditations        (->> (all-for-user-query ctx)
                                  (sort-by ::sm/created-at)
                                  (reverse)
                                  (take 3))]
    (ui/page
     {}
     (side-bar (pot/map-of email)
               [:div.m-2.w-full.md:w-96.space-y-8
                (biff/form
                 {:hx-post   "/app/meditations"
                  :hx-swap   "outerHTML"
                  :hx-select "#create-meditation-form"
                  :id        "create-meditation-form"}

                 [:div
                  [:h2.text-base.font-semibold.leading-7.text-gray-900 "Create Meditation"]
                  [:p.mt-1.text-sm.leading-6.text-gray-600 "Create a new meditation."]]

                 [:div.grid.grid-cols-1.gap-y-6

                  ;; Meditation Name
                  [:div
                   [:label.block.text-sm.font-medium.leading-6.text-gray-900 {:for "meditation-name"} "Meditation Name"]
                   [:div.mt-2
                    [:input.rounded-md.shadow-sm.block.w-full.border-0.py-1.5.text-gray-900.focus:ring-2.focus:ring-blue-600
                     {:type "text" :name "meditation-name" :autocomplete "off"}]]]

                  ;; Notes
                  [:div
                   [:label.block.text-sm.font-medium.leading-6.text-gray-900 {:for "notes"} "Notes"]
                   [:div.mt-2
                    [:textarea.rounded-md.shadow-sm.block.w-full.border-0.py-1.5.text-gray-900.focus:ring-2.focus:ring-blue-600
                     {:name "notes" :autocomplete "off"}]]]

                  ;; Submit button
                  [:div.mt-2.w-full
                   [:button.bg-blue-500.hover:bg-blue-700.text-white.font-bold.py-2.px-4.rounded.w-full
                    {:type "submit"} "Create Meditation"]]]

                 [:div.my-4 [:span "Recents"]]
                 (->> recent-meditations
                      (map list-item)))]))))

(defn create! [{:keys [params session] :as ctx}]
  (let [now (t/now)]
    (biff/submit-tx ctx
                    [(merge {:db/doc-type      :meditation
                             ::sm/type         :meditation
                             :user/id          (:uid session)
                             :meditation/name  (:meditation-name params)
                             :meditation/label (:meditation-name params)
                             :meditation/notes (:notes params)
                             ::sm/created-at   now})]))
  {:status  303
   :headers {"location" "/app/new/meditation"}})

(defn search-component [{:keys [search-str]}]
  [:div.my-2
   (biff/form
    {:id         "meditation-search"
     :hx-post    "/app/search-meditations"
     :hx-swap    "outerHTML"
     :hx-trigger "search"
     :hx-select  "#meditations-list"
     :hx-target  "#meditations-list"}
    [:div.flex.flex-col.justify-center.my-6

     [:input.form-control.w-full.md:w-96.mb-2
      (merge {:type        "search"
              :name        "search"
              :placeholder "Begin Typing To Search Meditations..."
              :script      "on keyup setURLParameter(me.name, me.value) then htmx.trigger('#meditation-search', 'search', {})"}

             (when (not (str/blank? search-str))
               {:value search-str}))]])])

(defn list-page
  "Accepts GET and POST. POST is for search form as body."
  [{:keys [session biff/db params]
    :as   ctx}]
  (let [user-id             (:uid session)
        {:user/keys
         [email time-zone]} (db/get-entity-by-id db user-id)
        meditations           (all-for-user-query ctx)
        edit-id             (some-> params :edit (UUID/fromString))
        search-str          (or (some-> params :search search-str-xform)
                                "")]
    (ui/page
     {}
     [:div
      (side-bar (pot/map-of email)
                [:div.my-4
                 (link-button {:href  "/app/new/meditation"
                               :label "Create Meditation"})]
                (search-component (pot/map-of search-str ))
                [:div {:id "meditations-list"}
                 (->> meditations
                      (filter (fn [{:meditation/keys             [name notes]
                                   id                         :xt/id}]
                                (let [matches-name  (str/includes? (str/lower-case (str name)) search-str)
                                      matches-notes (str/includes? (str/lower-case (str notes)) search-str)]
                                  (or matches-name
                                      matches-notes))))
                      (map (fn [z] (list-item (-> z (assoc :edit-id edit-id))))))])])))

(defn edit! [{:keys [params] :as ctx}]
  (let [id              (-> params :id UUID/fromString)
        meditation (single-for-user-query (merge ctx {:xt/id id}))
        name            (:name params)
        notes           (-> params :notes str)]
    ;; Authz is that the user owns the meditation
    (if (some? meditation)
      (do
        (biff/submit-tx ctx
                        [{:db/op                 :update
                          :db/doc-type           :meditation
                          :xt/id                 id
                          :meditation/name  name
                          :meditation/label name
                          :meditation/notes notes}])
        {:status  303
         :headers {"location" (str "/app/meditations/" id "/edit")}})
      {:status 403
       :body   "Not authorized to edit that meditation"})))

(defn edit-form [{:keys [path-params
                         session
                         biff/db]
                  :as   ctx}]
  (let [meditation-id             (-> path-params :id UUID/fromString)
        user-id              (:uid session)
        {email :user/email}  (db/get-entity-by-id db user-id)
        time-zone            (get-user-time-zone ctx)
        meditation                (single-for-user-query (merge ctx {:xt/id meditation-id}))
        latest-tx-time       (-> (get-last-tx-time (merge ctx {:xt/id meditation-id}))
                                 (t/in (t/zone time-zone))
                                 (->> (t/format (t/formatter zoned-date-time-fmt))))
        formatted-created-at (-> meditation
                                 ::sm/created-at
                                 (t/in (t/zone time-zone))
                                 (->> (t/format (t/formatter zoned-date-time-fmt))))]
    (ui/page
     {}
     [:div {:id "meditation-edit-page"}
      (side-bar (pot/map-of email)

                ;; edit-form
                (biff/form
                 {:hx-post   (str "/app/meditations/" meditation-id)
                  :hx-swap   "outerHTML"
                  :hx-select "#meditation-edit-form"
                  :id        "meditation-edit-form"}

                 [:div.w-full.md:w-96.p-2
                  [:input {:type "hidden" :name "id" :value meditation-id}]

                  [:div.grid.grid-cols-1.gap-y-6

                   ;; Meditation Name
                   [:div
                    [:label.block.text-sm.font-medium.leading-6.text-gray-900
                     {:for "meditation-name"} "Meditation Name"]
                    [:div.mt-2
                     [:input.rounded-md.shadow-sm.block.w-full.border-0.py-1.5.text-gray-900.focus:ring-2.focus:ring-blue-600
                      {:type "text" :name "name" :value (:meditation/name meditation)}]]]

                   ;; Notes
                   [:div
                    [:label.block.text-sm.font-medium.leading-6.text-gray-900 {:for "notes"} "Notes"]
                    [:div.mt-2
                     [:textarea.rounded-md.shadow-sm.block.w-full.border-0.py-1.5.text-gray-900.focus:ring-2.focus:ring-blue-600
                      {:name "notes"} (:meditation/notes meditation)]]]

                   ;; Submit button
                   [:div.mt-2.w-full
                    [:button.bg-blue-500.hover:bg-blue-700.text-white.font-bold.py-2.px-4.rounded.w-full
                     {:type       "submit"}
                     "Update Meditation"]]

                   [:div.mt-4.flex.flex-col
                    [:span.text-gray-500 (str "last updated: " latest-tx-time)]
                    [:span.text-gray-500 (str "created at: " (or formatted-created-at (::sm/created-at meditation)))]]]])

                ;; delete form
                (biff/form
                 {:action (str "/app/meditations/" meditation-id "/delete") :method "post"}
                 [:div.w-full.md:w-96.p-2.my-4
                  [:input.text-center.bg-red-100.hover:bg-red-500.hover:text-white.text-black.font-bold.py-2.px-4.rounded.w-full
                   {:type "submit" :value "Delete"}]]))])))

(defn view [{:keys [path-params
                    session
                    biff/db]
             :as   ctx}]
  (let [meditation-id         (-> path-params :id UUID/fromString)
        user-id             (:uid session)
        {email :user/email} (db/get-entity-by-id db user-id)
        meditation            (single-for-user-query (merge ctx {:xt/id meditation-id}))]
    (ui/page
     {}
     [:div
      (side-bar (pot/map-of email)
                (list-item meditation))])))

(defn soft-delete! [{:keys [path-params params]
                     :as   ctx}]
  (let [meditation-id (-> path-params :id UUID/fromString)
        meditation    (single-for-user-query (merge ctx {:xt/id meditation-id}))
        now         (t/now)]
    (if (some? meditation)
      (do
        (biff/submit-tx ctx
                        [{:db/op              :update
                          :db/doc-type        :meditation
                          :xt/id              meditation-id
                          ::sm/deleted-at now}])
        {:status  303
         :headers {"location" "/app/meditations"}})
      {:status 403
       :body   "Not authorized to edit that meditation"})))
