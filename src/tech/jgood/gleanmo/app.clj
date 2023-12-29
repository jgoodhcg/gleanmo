(ns tech.jgood.gleanmo.app
  (:require
   [clojure.pprint :refer [pprint]]
   [clojure.string :as str]
   [com.biffweb :as biff :refer [q]]
   [potpuri.core :as pot]
   [tech.jgood.gleanmo.middleware :as mid]
   [tech.jgood.gleanmo.settings :as settings]
   [tech.jgood.gleanmo.ui :as ui]
   [tech.jgood.gleanmo.schema :as schema]
   [tick.core :as t]
   [xtdb.api :as xt])
  (:import
   [java.time ZoneId]
   [java.time ZonedDateTime]
   [java.time LocalDateTime]
   [java.util UUID]))

(def about-page
  (ui/page
   {:base/title (str "About " settings/app-name)}
   [:p "This app was made with "
    [:a.link {:href "https://biffweb.com"} "Biff"] "."]))

(defn echo [{:keys [params]}]
  {:status 200
   :headers {"content-type" "application/json"}
   :body params})

(defn nav-bar [{:keys [email]}]
  [:div.space-x-8
   [:span email]
   [:a.link {:href "/app"} "home"]
   [:a.link {:href "/app/habits"} "habits"]
   [:a.link {:href "/app/habit-logs"} "habit logs"]
   (biff/form
    {:action "/auth/signout"
     :class  "inline"}
    [:button.text-blue-500.hover:text-blue-800 {:type "submit"}
     "Sign out"])])

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

(defn habit-create-page [{:keys [session biff/db]}]
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

(defn habit-create! [{:keys [params session] :as ctx}]
  (biff/submit-tx ctx
                  [{:db/doc-type     :habit
                    ::schema/type    :habit
                    :user/id         (:uid session)
                    :habit/name      (:habit-name params)
                    :habit/sensitive (boolean (:sensitive params))
                    :habit/notes     (:notes params)}])
  {:status  303
   :headers {"location" "/app/habit/create"}})

(defn format-date-time-local [instant zone-id]
  (->> (t/in instant zone-id)
       (t/format (t/formatter "yyyy-MM-dd'T'HH:mm"))))

(defn habit-log-create-page [{:keys [session biff/db]}]
  (let [user-id              (:uid session)
        {:user/keys [email]} (xt/entity db user-id)
        habits               (q db '{:find  (pull ?habit [*])
                                     :where [[?habit ::schema/type :habit]
                                             [?habit :user/id user-id]]
                                     :in    [user-id]} user-id)
        time-zone            (first (first (q db '{:find  [?tz]
                                                   :where [[?user :xt/id user-id]
                                                           [?user :user/time-zone ?tz]]
                                                   :in    [user-id]} user-id)))
        current-time         (format-date-time-local (t/now) time-zone)]
    (ui/page
     {}
     [:div
      (nav-bar (pot/map-of email))
      [:div.w-full.md:w-96.space-y-8
       (biff/form
        {:hx-post   "/app/habit-log/create"
         :hx-swap   "outerHTML"
         :hx-select "#log-habit-form"
         :id        "log-habit-form"}

        [:div
         [:h2.text-base.font-semibold.leading-7.text-gray-900 "Log Habit"]
         [:p.mt-1.text-sm.leading-6.text-gray-600 "Log the habit with your desired settings."]]

        [:div.grid.grid-cols-1.gap-y-6
         ;; Time Zone selection
         [:div
          [:label.block.text-sm.font-medium.leading-6.text-gray-900 {:for "time-zone"} "Time Zone"]
          [:div.mt-2
           [:select.rounded-md.shadow-sm.block.w-full.border-0.py-1.5.text-gray-900.focus:ring-2.focus:ring-blue-600
            {:name "time-zone" :required true :autocomplete "on"}
            (->> (java.time.ZoneId/getAvailableZoneIds)
                 sort
                 (map (fn [zoneId]
                        [:option {:value    zoneId
                                  :selected (= zoneId time-zone)} zoneId])))]]]

         ;; Notes input
         [:div
          [:label.block.text-sm.font-medium.leading-6.text-gray-900 {:for "notes"} "Notes"]
          [:div.mt-2
           [:textarea.rounded-md.shadow-sm.block.w-full.border-0.py-1.5.text-gray-900.focus:ring-2.focus:ring-blue-600
            {:name "notes" :rows 3 :placeholder "Any additional notes..."}]]]

         ;; Timestamp input
         [:div
          [:label.block.text-sm.font-medium.leading-6.text-gray-900 {:for "timestamp"} "Timestamp"]
          [:div.mt-2
           [:input.rounded-md.shadow-sm.block.w-full.border-0.py-1.5.text-gray-900.focus:ring-2.focus:ring-blue-600
            {:type "datetime-local" :name "timestamp" :required true :value current-time}]]]

         ;; Habits selection
         [:div
          [:label.block.text-sm.font-medium.leading-6.text-gray-900 {:for "habit-refs"} "Habits"]
          [:div.mt-2
           [:select.rounded-md.shadow-sm.block.w-full.border-0.py-1.5.text-gray-900.focus:ring-2.focus:ring-blue-600
            {:name "habit-refs" :multiple true :required true :autocomplete "off"}
            (map (fn [habit]
                   [:option {:value (:xt/id habit)}
                    (:habit/name habit)])
                 habits)]]]

         ;; Submit button
         [:div.mt-2.w-full
          [:button.bg-blue-500.hover:bg-blue-700.text-white.font-bold.py-2.px-4.rounded.w-full
           {:type "submit"} "Log Habit"]]])]])))

(defn ensure-vector [item]
  (if (vector? item)
    item
    [item]))

(defn habit-log-create! [{:keys [session params biff/db] :as ctx}]
  (let [id-strs        (-> params :habit-refs ensure-vector)
        tz             (-> params :time-zone)
        timestamp-str  (-> params :timestamp)
        notes          (-> params :notes)
        local-datetime (java.time.LocalDateTime/parse timestamp-str)
        zone-id        (java.time.ZoneId/of tz)
        zdt            (java.time.ZonedDateTime/of local-datetime zone-id)
        timestamp      (-> zdt (t/inst))
        habit-ids      (->> id-strs
                            (map #(some-> % java.util.UUID/fromString))
                            set)
        user-id        (:uid session)
        {:user/keys
         [time-zone]}  (xt/entity db user-id)
        new-tz         (not= time-zone tz)]

    (biff/submit-tx ctx
                    (vec (remove nil?
                                 [(merge
                                   {:db/doc-type         :habit-log
                                    ::schema/type        :habit-log
                                    :user/id             user-id
                                    :habit-log/timestamp timestamp
                                    :habit-log/habit-ids habit-ids}
                                   (when (not (str/blank? notes))
                                     {:habit-log/notes notes}))
                                  (when new-tz
                                    {:db/op          :update
                                     :db/doc-type    :user
                                     :xt/id          user-id
                                     :user/time-zone tz})]))))

  {:status  303
   :headers {"location" "/app/habit-log/create"}})

(defn app [{:keys [session biff/db]}]
  (let [user-id              (:uid session)
        {:user/keys [email]} (xt/entity db user-id)]
    (ui/page
     {}
     [:div
      (nav-bar (pot/map-of email))
      [:div.flex.flex-col.md:flex-row.justify-center
       [:h1.text-3xl.font-bold "Home page"]]])))

(defn habit-edit-form [{id             :xt/id
                        sensitive      :habit/sensitive
                        latest-tx-time :latest-tx-time
                        :as            habit}]
  (biff/form
   {:hx-post   "/app/habit/edit"
    :hx-swap   "outerHTML"
    :hx-select "#habit-edit-form"
    :id        "habit-edit-form"}

   [:div.w-full.md:w-96.p-2
    [:input {:type "hidden" :name "id" :value (:xt/id habit)}]

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

     [:span.text-gray-500 (str "last updated: " latest-tx-time)]

     ;; Submit button
     [:div.mt-2.w-full
      [:button.bg-blue-500.hover:bg-blue-700.text-white.font-bold.py-2.px-4.rounded.w-full
       {:type   "submit"
        ;; maybe bring this back when inline editing is re-enabled
        #_#_:script "on click setURLParameter('edit', '')"}
       "Update Habit"]]]]))

(defn habit-list-item [{:habit/keys [sensitive name notes archived]
                        edit-id     :edit-id
                        id          :xt/id
                        :as         habit}]
  (let [url (str "/app/habits?edit=" id (when sensitive "&sensitive=true"))]
    (if (= edit-id id)
      (habit-edit-form habit)
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

(defn habit-search-component [{:keys [sensitive search archived]}]
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

(defn habits-query [{:keys [db user-id]}]
  (q db '{:find  (pull ?habit [*])
          :where [[?habit ::schema/type :habit]
                  [?habit :user/id user-id]]
          :in    [user-id]} user-id))

(defn habit-query [{:keys [db user-id habit-id]}]
  (first
   (q db '{:find  (pull ?habit [*])
           :where [[?habit ::schema/type :habit]
                   [?habit :user/id user-id]
                   [?habit :xt/id habit-id]]
           :in    [user-id habit-id]} user-id habit-id)))

(defn checkbox-true? [v]
  (or (= v "on") (= v "true")))

(defn search-str-xform [s]
  (some-> s str/lower-case str/trim))

(defn link-button [{:keys [href label]}]
  [:a.text-blue-500.hover:underline.outline.outline-blue-500.outline-2.font-bold.py-2.px-4.rounded.w-full.md:w-96.mt-6
   {:href href} label])

(defn habits-page
  "Accepts GET and POST. POST is for search form as body."
  [{:keys [session biff/db params query-params]}]
  (let [user-id             (:uid session)
        {:user/keys
         [email time-zone]} (xt/entity db user-id)
        habits              (habits-query (pot/map-of db user-id))
        edit-id             (some-> params :edit (java.util.UUID/fromString))
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
      (habit-search-component (pot/map-of sensitive search archived))
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
            (map (fn [z] (habit-list-item (-> z (assoc :edit-id edit-id))))))]])))

(defn habit-edit! [{:keys [session params] :as ctx}]
  (let [id        (-> params :id java.util.UUID/fromString)
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

(defn habit-logs-query [{:keys [db user-id]}]
  (let [raw-results (q db '{:find  [(pull ?habit-log [*]) ?habit-id ?habit-name ?tz]
                            :where [[?habit-log :habit-log/timestamp]
                                    [?habit-log :user/id user-id]
                                    [?habit-log :habit-log/habit-ids ?habit-id]
                                    [?habit :xt/id ?habit-id]
                                    [?habit :habit/name ?habit-name]
                                    [?user :xt/id user-id]
                                    [?user :user/time-zone ?tz]]
                            :in    [user-id]} user-id)]
    (->> raw-results
         (group-by (fn [[habit-log _ _]] (:xt/id habit-log))) ; Group by habit-log id
         (map (fn [[log-id grouped-tuples]]
                ;; Extract the habit-log map from the first tuple and tz from last
                (let [habit-log-map (-> grouped-tuples first first)
                      tz            (-> grouped-tuples first last)]
                  (assoc habit-log-map
                         :user/time-zone tz
                         :habit-log/habits
                         (->> grouped-tuples
                              (map (fn [[_ ?habit-id ?habit-name]] ; Construct habit maps
                                     {:habit/id   ?habit-id
                                      :habit/name ?habit-name})))))))
         (into [])
         (sort-by :habit-log/timestamp)
         (reverse))))

(defn habit-log-list-item [{:habit-log/keys [timestamp habits notes]
                            user-id         :user/id
                            tz              :user/time-zone
                            id              :xt/id
                            :as             habit-log}]
  (let [url (str "/app/habit-logs?view=" id)
        formatted-timestamp (when timestamp
                              (-> timestamp
                                  (t/instant)
                                  (t/in (t/zone tz))
                                  (->> (t/format (t/formatter "yyyy-MM-dd HH:mm:ss z")))))]
    [:div.hover:bg-gray-100.transition.duration-150.p-4.border-b.border-gray-200.cursor-pointer.w-full.md:w-96
     {:id (str "habit-log-list-item-" id)}
     [:span formatted-timestamp]
     (when notes [:p.text-sm.text-gray-600 notes])
     [:div.flex.flex-col.mt-2
      (for [{habit-id   :habit/id
             habit-name :habit/name} habits]
        [:span habit-name])]]))

(defn habit-logs-page
  "Accepts GET and POST. POST is for search form as body."
  [{:keys [session biff/db params query-params]}]
  (let [user-id                        (:uid session)
        {:user/keys [email time-zone]} (xt/entity db user-id)
        habit-logs                     (habit-logs-query (pot/map-of db user-id))
        edit-id                        (some-> params :edit (java.util.UUID/fromString))
        sensitive                      (or (some-> params :sensitive checkbox-true?)
                                           (some-> query-params :sensitive checkbox-true?))
        search                         (or (some-> params :search search-str-xform)
                                           (some-> query-params :search search-str-xform)
                                           "")]
    (ui/page
     {}
     [:div
      (nav-bar (pot/map-of email))
      [:div.my-4
       (link-button {:href "/app/habit-log/create"
                     :label "Create Habit Log"})]
      [:div {:id "habit-logs-list"}
       (->> habit-logs
            (map (fn [z] (habit-log-list-item (-> z (assoc :edit-id edit-id))))))]])))

(defn get-last-tx-time [{:keys [db id]}]
  (let [history          (xt/entity-history db id :desc)]
    (-> history first :xtdb.api/tx-time)))

(defn habit-edit-page [{:keys [path-params
                               session
                               biff/db]
                        :as   ctx}]
  (let [habit-id            (-> path-params :id java.util.UUID/fromString)
        user-id             (:uid session)
        {email :user/email} (xt/entity db user-id)
        habit               (habit-query (pot/map-of db habit-id user-id))
        latest-tx-time      (get-last-tx-time {:db db :id habit-id})]
    (pprint (pot/map-of habit latest-tx-time))
    (ui/page
     {}
     [:div
      (nav-bar (pot/map-of email))
      (habit-edit-form (merge habit (pot/map-of latest-tx-time)))])))

(def plugin
  {:static {"/about/" about-page}
   :routes ["/app" {:middleware [mid/wrap-signed-in]}
            [""                    {:get app}]
            ["/db"                 {:get db-viz}]
            ["/habits"             {:get habits-page :post habits-page}]
            ["/habit/create"       {:get habit-create-page :post habit-create!}]
            ["/habit/edit/:id"     {:get habit-edit-page}]
            ["/habit/edit"         {:post habit-edit!}]
            ["/habit-logs"         {:get habit-logs-page}]
            ["/habit-log/create"   {:get habit-log-create-page :post habit-log-create!}]
            #_#_["/habit-log/edit/:id" {:get habit-log-edit-page}]
              ["/habit-log/edit"     {:post habit-log-edit!}]]})
