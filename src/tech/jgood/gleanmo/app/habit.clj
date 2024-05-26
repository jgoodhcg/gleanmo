(ns tech.jgood.gleanmo.app.habit
  (:require
   [clojure.pprint :refer [pprint]]
   [clojure.string :as str]
   [com.biffweb :as biff :refer [q]]
   [potpuri.core :as pot]
   [tech.jgood.gleanmo.app.shared :refer [checkbox-true? get-last-tx-time
                                          get-user-time-zone link-button nav-bar
                                          search-str-xform zoned-date-time-fmt]]
   [tech.jgood.gleanmo.schema :as schema]
   [tech.jgood.gleanmo.ui :as ui]
   [tick.core :as t]
   [xtdb.api :as xt])
  (:import
   [java.util UUID]))

(defn create-page [{:keys [session biff/db]}]
  (let [user-id              (:uid session)
        {:user/keys [email]} (xt/entity db user-id)]
    (ui/page
     {}
     (nav-bar (pot/map-of email))
     [:div.m-2.w-full.md:w-96.space-y-8
      (biff/form
       {:hx-post   "/app/habit/create"
        :hx-swap   "outerHTML"
        :hx-select "#create-habit-form"
        :id        "create-habit-form"}

       [:div
        [:h2.text-base.font-semibold.leading-7.text-gray-900 "Create Habit"]
        [:p.mt-1.text-sm.leading-6.text-gray-600 "Create a new habit to your list."]]

       [:div.grid.grid-cols-1.gap-y-6

        ;; Habit Name
        [:div
         [:label.block.text-sm.font-medium.leading-6.text-gray-900 {:for "habit-name"} "Habit Name"]
         [:div.mt-2
          [:input.rounded-md.shadow-sm.block.w-full.border-0.py-1.5.text-gray-900.focus:ring-2.focus:ring-blue-600
           {:type "text" :name "habit-name" :autocomplete "off"}]]]

        ;; Is Sensitive?
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
          {:type "submit"} "Create Habit"]]])])))

(defn create! [{:keys [params session] :as ctx}]
  (biff/submit-tx ctx
                  [{:db/doc-type     :habit
                    ::schema/type    :habit
                    :user/id         (:uid session)
                    :habit/name      (:habit-name params)
                    :habit/sensitive (boolean (:sensitive params))
                    :habit/notes     (:notes params)}])
  {:status  303
   :headers {"location" "/app/habit/create"}})

(defn edit-form [{id             :xt/id
                        sensitive      :habit/sensitive
                        latest-tx-time :latest-tx-time
                        :as            habit}]
  (biff/form
   {:hx-post   "/app/habit/edit"
    :hx-swap   "outerHTML"
    :hx-select "#habit-edit-form"
    :id        "habit-edit-form"}

   [:div.w-full.md:w-96.p-2
    [:input {:type "hidden" :name "id" :value id}]

    [:div.grid.grid-cols-1.gap-y-6

     ;; Habit Name
     [:div
      [:label.block.text-sm.font-medium.leading-6.text-gray-900
       {:for "habit-name"} "Habit Name"]
      [:div.mt-2
       [:input.rounded-md.shadow-sm.block.w-full.border-0.py-1.5.text-gray-900.focus:ring-2.focus:ring-blue-600
        {:type "text" :name "name" :value (:habit/name habit)}]]]

     ;; Is Sensitive?
     [:div.flex.items-center
      [:input.rounded.shadow-sm.mr-2.text-indigo-600.focus:ring-blue-500.focus:border-indigo-500
       {:type "checkbox" :name "sensitive" :checked (:habit/sensitive habit)}]
      [:label.text-sm.font-medium.leading-6.text-gray-900 {:for "sensitive"} "Is Sensitive?"]]

     [:div.flex.items-center
      [:input.rounded.shadow-sm.mr-2.text-indigo-600.focus:ring-blue-500.focus:border-indigo-500
       {:type "checkbox" :name "archived" :checked (:habit/archived habit)}]
      [:label.text-sm.font-medium.leading-6.text-gray-900 {:for "archived"} "Is Archived?"]]

     ;; Notes
     [:div
      [:label.block.text-sm.font-medium.leading-6.text-gray-900 {:for "notes"} "Notes"]
      [:div.mt-2
       [:textarea.rounded-md.shadow-sm.block.w-full.border-0.py-1.5.text-gray-900.focus:ring-2.focus:ring-blue-600
        {:name "notes"} (:habit/notes habit)]]]

     ;; Submit button
     [:div.mt-2.w-full
      [:button.bg-blue-500.hover:bg-blue-700.text-white.font-bold.py-2.px-4.rounded.w-full
       {:type   "submit"
        ;; maybe bring this back when inline editing is re-enabled
        #_#_:script "on click setURLParameter('edit', '')"}
       "Update Habit"]]

     [:div.mt-4
      [:span.text-gray-500 (str "last updated: " latest-tx-time)]]
 ]]))

(defn list-item [{:habit/keys [sensitive name notes archived]
                        edit-id     :edit-id
                        id          :xt/id
                        :as         habit}]
  (let [url (str "/app/habits?edit=" id (when sensitive "&sensitive=true"))]
    (if (= edit-id id)
      (edit-form habit)
      ;; remove this link if bringing back inline editing
      [:a {:href (str "/app/habit/edit/" id)}
       [:div.relative.hover:bg-gray-100.transition.duration-150.p-4.border-b.border-gray-200.cursor-pointer.w-full.md:w-96
        ;; Disabling inline editing for now in favor of full page refresh
        ;; Maybe bring this back later
        #_{:id          (str "habit-list-item-" id)
           :hx-get      url
           :hx-swap     "outerHTML"
           :script      (str "on click setURLParameter('edit', '" id
                             "') then setURLParameter('sensitive', '" sensitive "')")
           :hx-trigger  "click"
           :hx-select   (str "#habit-list-item-" id)}
        [:div.flex.justify-between
         [:h2.text-md.font-bold name]]
        [:div.absolute.top-2.right-2
         (when sensitive [:span.text-red-500.mr-2 "🔒"])
         (when archived  [:span.text-red-500.mr-2 "📦"])]
        [:p.text-sm.text-gray-600 notes]]])))

(defn search-component [{:keys [sensitive search archived]}]
  [:div.my-2
   (biff/form
    {:id         "habit-search"
     :hx-post    "/app/habits"
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

;; TODO change params to be fully namespaced keys
(defn all-for-user-query [{:keys [db user-id]}]
  (q db '{:find  (pull ?habit [*])
          :where [[?habit ::schema/type :habit]
                  [?habit :user/id user-id]]
          :in    [user-id]} user-id))

(defn single-for-user-query [{:keys [db user-id habit-id]}]
  (first
   (q db '{:find  (pull ?habit [*])
           :where [[?habit ::schema/type :habit]
                   [?habit :user/id user-id]
                   [?habit :xt/id habit-id]]
           :in    [user-id habit-id]} user-id habit-id)))

(defn list-page
  "Accepts GET and POST. POST is for search form as body."
  [{:keys [session biff/db params query-params]}]
  (let [user-id             (:uid session)
        {:user/keys
         [email time-zone]} (xt/entity db user-id)
        habits              (all-for-user-query (pot/map-of db user-id))
        edit-id             (some-> params :edit (UUID/fromString))
        sensitive           (or (some-> params :sensitive checkbox-true?)
                                (some-> query-params :sensitive checkbox-true?))
        archived            (or (some-> params :archived checkbox-true?)
                                (some-> query-params :archived checkbox-true?))
        search              (or (some-> params :search search-str-xform)
                                (some-> query-params :search search-str-xform)
                                "")]
    (ui/page
     {}
     [:div
      (nav-bar (pot/map-of email))
      [:div.my-4
       (link-button {:href "/app/habit/create"
                     :label "Create Habit"})]
      (search-component (pot/map-of sensitive search archived))
      [:div {:id "habits-list"}
       (->> habits
            (filter (fn [{:habit/keys             [name notes]
                          this-habit-is-sensitive :habit/sensitive
                          this-habit-is-archived  :habit/archived
                          id                      :xt/id}]
                      (let [matches-name  (str/includes? (str/lower-case name) search)
                            matches-notes (str/includes? (str/lower-case notes) search)]
                        (and (or archived
                                 (not this-habit-is-archived))
                             (or sensitive
                                 (-> id (= edit-id))
                                 (not this-habit-is-sensitive))
                             (or matches-name
                                 matches-notes)))))
            (map (fn [z] (list-item (-> z (assoc :edit-id edit-id))))))]])))

(defn edit! [{:keys [session params] :as ctx}]
  (let [id        (-> params :id UUID/fromString)
        name      (:name params)
        notes     (-> params :notes str)
        sensitive (-> params :sensitive boolean)
        archived  (-> params :archived boolean)]
    (biff/submit-tx ctx
                    [{:db/op           :update
                      :db/doc-type     :habit
                      :xt/id           id
                      :habit/name      name
                      :habit/notes     notes
                      :habit/sensitive sensitive
                      :habit/archived  archived}])

    {:status  303
     :headers {"location" (str "/app/habit/edit/" id)}}))

(defn edit-page [{:keys [path-params
                               session
                               biff/db]
                        :as   ctx}]
  (let [habit-id            (-> path-params :id UUID/fromString)
        user-id             (:uid session)
        {email :user/email} (xt/entity db user-id)
        time-zone           (get-user-time-zone ctx)
        habit               (single-for-user-query (pot/map-of db habit-id user-id))
        latest-tx-time      (-> (get-last-tx-time (merge ctx {:xt/id habit-id}))
                                (t/in (t/zone time-zone))
                                (->> (t/format (t/formatter zoned-date-time-fmt))))]
    (pprint (pot/map-of habit latest-tx-time))
    (ui/page
     {}
     [:div
      (nav-bar (pot/map-of email))
      (edit-form (merge habit (pot/map-of latest-tx-time)))])))
