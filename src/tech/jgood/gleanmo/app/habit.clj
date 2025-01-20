(ns tech.jgood.gleanmo.app.habit
  (:require
   [clojure.string :as str]
   [com.biffweb :as biff :refer [q]]
   [potpuri.core :as pot]
   [tech.jgood.gleanmo.app.shared :refer [get-last-tx-time get-user-time-zone
                                          link-button param-true?
                                          search-str-xform side-bar zoned-date-time-fmt]]
   [tech.jgood.gleanmo.schema.meta :as sm]
   [tech.jgood.gleanmo.ui :as ui]
   [tick.core :as t]
   [xtdb.api :as xt])
  (:import
   [java.util UUID]))

(defn all-for-user-query [{:keys [biff/db session sensitive archived]}]
  (cond->>  (q db '{:find  (pull ?habit [*])
                    :where [[?habit ::sm/type :habit]
                            [?habit :user/id user-id]
                            (not [?habit ::sm/deleted-at])]
                    :in    [user-id]} (:uid session))
    (not sensitive) (remove :habit/sensitive)
    (not archived)  (remove :habit/archived)
    :always         (sort-by ::sm/created-at)))

(defn single-for-user-query [{:keys [biff/db session xt/id]}]
  (first
   (q db '{:find  (pull ?habit [*])
           :where [[?habit :xt/id habit-id]
                   [?habit ::sm/type :habit]
                   [?habit :user/id user-id]
                   (not [?habit ::sm/deleted-at])]
           :in    [user-id habit-id]} (:uid session) id)))

(defn list-item [{:habit/keys [sensitive name notes archived]
                  id          :xt/id}]
  [:a {:href (str "/app/habits/" id "/edit")}
   [:div.relative.hover:bg-gray-100.transition.duration-150.p-4.border-b.border-gray-200.cursor-pointer.w-full.md:w-96
    [:div.flex.justify-between
     [:h2.text-md.font-bold name]]
    [:div.absolute.top-2.right-2
     (when sensitive [:span.text-red-500.mr-2 "🔒"])
     (when archived  [:span.text-red-500.mr-2 "📦"])]
    [:p.text-sm.text-gray-600 notes]]])

(defn new-form [{:keys [session biff/db] :as ctx}]
  (let [user-id              (:uid session)
        {:user/keys [email]} (xt/entity db user-id)
        recent-habits        (->> (all-for-user-query ctx)
                                  (sort-by ::sm/created-at)
                                  (reverse)
                                  (take 3))]
    (ui/page
     {}
     (side-bar (pot/map-of email)
               [:div.m-2.w-full.md:w-96.space-y-8
                (biff/form
                 {:hx-post   "/app/habits"
                  :hx-swap   "outerHTML"
                  :hx-select "#create-habit-form"
                  :id        "create-habit-form"}

                 [:div
                  [:h2.text-base.font-semibold.leading-7.text-gray-900 "Create Habit"]
                  [:p.mt-1.text-sm.leading-6.text-gray-600 "Create a new habit."]]

                 [:div.grid.grid-cols-1.gap-y-6

                  ;; Habit Name
                  [:div
                   [:label.block.text-sm.font-medium.leading-6.text-gray-900 {:for "habit-name"} "Habit Name"]
                   [:div.mt-2
                    [:input.rounded-md.shadow-sm.block.w-full.border-0.py-1.5.text-gray-900.focus:ring-2.focus:ring-blue-600
                     {:type "text" :name "habit-name" :autocomplete "off"}]]]

                  ;; Is Sensitive?
                  ;; TODO explain sensitive
                  [:div.flex.items-center
                   [:input.rounded.shadow-sm.mr-2.text-indigo-600.focus:ring-blue-500.focus:border-indigo-500
                    {:type "checkbox" :name "sensitive" :autocomplete "off"}]
                   [:label.text-sm.font-medium.leading-6.text-gray-900 {:for "sensitive"} "Is Sensitive?"]]

                  ;; Notes
                  [:div
                   [:label.block.text-sm.font-medium.leading-6.text-gray-900 {:for "notes"} "Notes"]
                   [:div.mt-2
                    [:textarea.rounded-md.shadow-sm.block.w-full.border-0.py-1.5.text-gray-900.focus:ring-2.focus:ring-blue-600
                     {:name "notes" :autocomplete "off"}]]]

                  ;; Submit button
                  [:div.mt-2.w-full
                   [:button.bg-blue-500.hover:bg-blue-700.text-white.font-bold.py-2.px-4.rounded.w-full
                    {:type "submit"} "Create Habit"]]]

                 [:div.my-4 [:span "Recents"]]
                 (->> recent-habits
                      (map list-item)))]))))

(defn create! [{:keys [params session] :as ctx}]
  (let [now       (t/now)
        sensitive (boolean (:sensitive params))]
    (biff/submit-tx ctx
                    [(merge {:db/doc-type        :habit
                             ::sm/type       :habit
                             :user/id            (:uid session)
                             :habit/name         (:habit-name params)
                             :habit/label        (:habit-name params)
                             :habit/notes        (:notes params)
                             ::sm/created-at now}
                            (when sensitive
                              {:habit/sensitive sensitive}))]))
  {:status  303
   :headers {"location" "/app/new/habit"}})

(defn search-component [{:keys [sensitive search archived]}]
  [:div.my-2
   (biff/form
    {:id         "habit-search"
     :hx-post    "/app/search-habits"
     :hx-swap    "outerHTML"
     :hx-trigger "search"
     :hx-select  "#habits-list"
     :hx-target  "#habits-list"}
    [:div.flex.flex-col.justify-center.my-6

     [:input.form-control.w-full.md:w-96.mb-2
      (merge {:type        "search"
              :name        "search"
              :placeholder "Begin Typing To Search Habits..."
              :script      "on keyup setURLParameter(me.name, me.value) then htmx.trigger('#habit-search', 'search', {})"}

             (when (not (str/blank? search))
               {:value search}))]

     [:div.flex.flex-row.justify-start.items-center
      [:label.mr-4 {:for "sensitive"} "Sensitive"]
      [:input.rounded.mr-2
       {:type         "checkbox"
        :name         "sensitive"
        :script       "on change setURLParameter(me.name, me.checked) then htmx.trigger('#habit-search', 'search', {})"
        :autocomplete "off"
        :checked      sensitive}]
      [:label.mx-4 {:for "archived"} "Archived"]
      [:input.rounded.mr-2
       {:type         "checkbox"
        :name         "archived"
        :script       "on change setURLParameter(me.name, me.checked) then htmx.trigger('#habit-search', 'search', {})"
        :autocomplete "off"
        :checked      archived}]]])])

(defn list-page
  "Accepts GET and POST. POST is for search form as body."
  [{:keys [session biff/db params]
    :as ctx}]
  (let [user-id             (:uid session)
        {:user/keys
         [email time-zone]} (xt/entity db user-id)
        habits              (all-for-user-query (merge ctx {:sensitive true
                                                            :archived true}))
        edit-id             (some-> params :edit (UUID/fromString))
        sensitive           (some-> params :sensitive param-true?)
        archived            (some-> params :archived param-true?)
        search              (or (some-> params :search search-str-xform)
                                "")]
    (ui/page
     {}
     [:div
      (side-bar (pot/map-of email)
                [:div.my-4
                 (link-button {:href "/app/new/habit"
                               :label "Create Habit"})]
                (search-component (pot/map-of sensitive search archived))
                [:div {:id "habits-list"}
                 (->> habits
                      (filter (fn [{:habit/keys             [name notes]
                                   this-habit-is-sensitive :habit/sensitive
                                   this-habit-is-archived  :habit/archived
                                   id                      :xt/id}]
                                (let [matches-name  (str/includes? (str/lower-case (str name)) search)
                                      matches-notes (str/includes? (str/lower-case (str notes)) search)]
                                  (and (or archived
                                           (not this-habit-is-archived))
                                       (or sensitive
                                           (-> id (= edit-id))
                                           (not this-habit-is-sensitive))
                                       (or matches-name
                                           matches-notes)))))
                      (map (fn [z] (list-item (-> z (assoc :edit-id edit-id))))))])])))

(defn edit! [{:keys [params] :as ctx}]
  (let [id        (-> params :id UUID/fromString)
        habit     (single-for-user-query (merge ctx {:xt/id id}))
        name      (:name params)
        notes     (-> params :notes str)
        sensitive (-> params :sensitive boolean)
        archived  (-> params :archived boolean)]
    ;; Authz is that the user owns the habit
    (if (some? habit)
      (do
        (biff/submit-tx ctx
                        [{:db/op           :update
                          :db/doc-type     :habit
                          :xt/id           id
                          :habit/name      name
                          :habit/label     name
                          :habit/notes     notes
                          :habit/sensitive sensitive
                          :habit/archived  archived}])
        {:status  303
         :headers {"location" (str "/app/habits/" id "/edit")}})
      {:status 403
       :body "Not authorized to edit that habit"})))

(defn edit-form [{:keys [path-params
                         session
                         biff/db]
                  :as   ctx}]
  (let [habit-id             (-> path-params :id UUID/fromString)
        user-id              (:uid session)
        {email :user/email}  (xt/entity db user-id)
        time-zone            (get-user-time-zone ctx)
        habit                (single-for-user-query (merge ctx {:xt/id habit-id}))
        latest-tx-time       (-> (get-last-tx-time (merge ctx {:xt/id habit-id}))
                                 (t/in (t/zone time-zone))
                                 (->> (t/format (t/formatter zoned-date-time-fmt))))
        formatted-created-at (-> habit
                                 ::sm/created-at
                                 (t/in (t/zone time-zone))
                                 (->> (t/format (t/formatter zoned-date-time-fmt))))]
    (ui/page
     {}
     [:div {:id "habit-edit-page"}
      (side-bar (pot/map-of email)

                ;; edit-form
                (biff/form
                 {:hx-post   (str "/app/habits/" habit-id)
                  :hx-swap   "outerHTML"
                  :hx-select "#habit-edit-form"
                  :id        "habit-edit-form"}

                 [:div.w-full.md:w-96.p-2
                  [:input {:type "hidden" :name "id" :value habit-id}]

                  [:div.grid.grid-cols-1.gap-y-6

                   ;; Habit Name
                   [:div
                    [:label.block.text-sm.font-medium.leading-6.text-gray-900
                     {:for "habit-name"} "Habit Name"]
                    [:div.mt-2
                     [:input.rounded-md.shadow-sm.block.w-full.border-0.py-1.5.text-gray-900.focus:ring-2.focus:ring-blue-600
                      {:type "text" :name "name" :value (:habit/name habit)}]]]

                   ;; TODO explain sensitive and archived
                   [:div.flex.flex-col
                    [:div.flex.items-center
                     [:input.rounded.shadow-sm.mr-2.text-indigo-600.focus:ring-blue-500.focus:border-indigo-500
                      {:type "checkbox" :name "sensitive" :checked (:habit/sensitive habit)}]
                     [:label.text-sm.font-medium.leading-6.text-gray-900 {:for "sensitive"} "Is Sensitive?"]]
                    [:span.text-gray-500 "Exclude from entry and reports unless explicilty included."]]

                   [:div.flex.flex-col
                    [:div.flex.items-center
                     [:input.rounded.shadow-sm.mr-2.text-indigo-600.focus:ring-blue-500.focus:border-indigo-500
                      {:type "checkbox" :name "archived" :checked (:habit/archived habit)}]
                     [:label.text-sm.font-medium.leading-6.text-gray-900 {:for "archived"} "Is Archived?"]]
                    [:span.text-gray-500 "Exclude from entry and reports indefinitely"]]

                   ;; Notes
                   [:div
                    [:label.block.text-sm.font-medium.leading-6.text-gray-900 {:for "notes"} "Notes"]
                    [:div.mt-2
                     [:textarea.rounded-md.shadow-sm.block.w-full.border-0.py-1.5.text-gray-900.focus:ring-2.focus:ring-blue-600
                      {:name "notes"} (:habit/notes habit)]]]

                   ;; Submit button
                   [:div.mt-2.w-full
                    [:button.bg-blue-500.hover:bg-blue-700.text-white.font-bold.py-2.px-4.rounded.w-full
                     {:type       "submit"
                      ;; maybe bring this back when inline editing is re-enabled
                      #_#_:script "on click setURLParameter('edit', '')"}
                     "Update Habit"]]

                   [:div.mt-4.flex.flex-col
                    [:span.text-gray-500 (str "last updated: " latest-tx-time)]
                    [:span.text-gray-500 (str "created at: " (or formatted-created-at (::sm/created-at habit)))]]]])

                ;; delete form
                (biff/form
                 {:action (str "/app/habits/" habit-id "/delete") :method "post"}
                 [:div.w-full.md:w-96.p-2.my-4
                  [:input.text-center.bg-red-100.hover:bg-red-500.hover:text-white.text-black.font-bold.py-2.px-4.rounded.w-full
                   {:type "submit" :value "Delete"}]]))])))

(defn view [{:keys [path-params
                    session
                    biff/db]
             :as   ctx}]
  (let [habit-id            (-> path-params :id UUID/fromString)
        user-id             (:uid session)
        {email :user/email} (xt/entity db user-id)
        habit               (single-for-user-query (merge ctx {:xt/id habit-id}))]
    (ui/page
     {}
     [:div
      (side-bar (pot/map-of email)
                (list-item habit))])))

(defn soft-delete! [{:keys [path-params params]
                     :as   ctx}]
  (let [habit-id            (-> path-params :id UUID/fromString)
        habit               (single-for-user-query (merge ctx {:xt/id habit-id}))
        now                 (t/now)]
    (if (some? habit)
      (do
        (biff/submit-tx ctx
                        [{:db/op              :update
                          :db/doc-type        :habit
                          :xt/id              habit-id
                          ::sm/deleted-at now}])
        {:status  303
         :headers {"location" "/app/habits"}})
      {:status 403
       :body   "Not authorized to edit that habit"})))
