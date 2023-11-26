(ns tech.jgood.gleanmo.app
  (:require
   [clojure.pprint :refer [pprint]]
   [clojure.string :as str]
   [com.biffweb :as biff :refer [q]]
   [potpuri.core :as pot]
   [tech.jgood.gleanmo.middleware :as mid]
   [tech.jgood.gleanmo.settings :as settings]
   [tech.jgood.gleanmo.ui :as ui]
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
             (map #(into (sorted-map) %)))
        all-attributes
        (->> all-entities
             (mapcat keys)
             distinct
             sort)
        table-rows
        (map (fn [entity]
               (map (fn [attr]
                      (get entity attr "_"))  ; Replace "N/A" with your preferred placeholder for missing values
                    all-attributes))
             all-entities)]
    (ui/page
     {}
     [:div "Signed in as " email ". "
      (biff/form
       {:action "/auth/signout"
        :class  "inline"}
       [:button.text-blue-500.hover:text-blue-800 {:type "submit"}
        "Sign out"])
      "."]
     [:.h-6]
     ;; a table of all-entities in db
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
             (str attr-val)])])]]

     [:.h-6])))

(defn header [{:keys [email]}]
  [:div.space-x-8
   [:span email]
   [:a.link {:href "/app/habits"} "habits"]
   [:a.link {:href "/app/habit/logs"} "habit logs"]
   (biff/form
    {:action "/auth/signout"
     :class  "inline"}
    [:button.text-blue-500.hover:text-blue-800 {:type "submit"}
     "Sign out"])])

(defn habit-create-page [{:keys [session biff/db]}]
  (let [user-id              (:uid session)
        {:user/keys [email]} (xt/entity db user-id)]
    (ui/page
     {}
     (header (pot/map-of email))
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
          {:type "submit"} "Create Habit"]]]

       )])))

(defn habit-create! [{:keys [params session] :as ctx}]
  (biff/submit-tx ctx
                  [{:db/doc-type        :habit
                    :user/id            (:uid session)
                    :habit/name      (:habit-name params)
                    :habit/sensitive (boolean (:sensitive params))
                    :habit/notes     (:notes params)}])
  {:status  303
   :headers {"location" "/app/habit/create"}})

(defn habit-log-create-form [{:keys [habits time-zone]}]
  [:div.w-full.md:w-96.space-y-8
   (biff/form
    {:hx-post   "/app/habit/log"
     :hx-swap   "outerHTML"
     :hx-select "#log-habit-form"
     :id        "log-habit-form"}

    [:div
     [:h2.text-base.font-semibold.leading-7.text-gray-900 "Log Habit"]
     [:p.mt-1.text-sm.leading-6.text-gray-600 "Log the habit with your desired settings."]]

    [:div.grid.grid-cols-1.gap-y-6
     ;; Timestamp input
     [:div
      [:label.block.text-sm.font-medium.leading-6.text-gray-900 {:for "timestamp"} "Timestamp"]
      [:div.mt-2
       [:input.rounded-md.shadow-sm.block.w-full.border-0.py-1.5.text-gray-900.focus:ring-2.focus:ring-blue-600
        {:type "datetime-local" :name "timestamp" :required true}]]]

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
     ;; Submit button
     [:div.mt-2.w-full
      [:button.bg-blue-500.hover:bg-blue-700.text-white.font-bold.py-2.px-4.rounded.w-full
       {:type "submit"} "Log Habit"]]])])

(defn ensure-vector [item]
  (if (vector? item)
    item
    [item]))

(defn habit-log-create! [{:keys [session params] :as ctx}]
  (let [id-strs        (-> params :habit-refs ensure-vector)
        tz             (-> params :time-zone)
        timestamp-str  (-> params :timestamp)
        local-datetime (java.time.LocalDateTime/parse timestamp-str)
        zone-id        (java.time.ZoneId/of tz)
        zdt            (java.time.ZonedDateTime/of local-datetime zone-id)
        timestamp      (-> zdt (t/inst))
        habit-ids      (->> id-strs
                            (map #(some-> % java.util.UUID/fromString))
                            set)
        user-id        (:uid session)]

    (biff/submit-tx ctx
                    [{:db/doc-type         :habit-log
                      :user/id             user-id
                      :habit-log/timestamp timestamp
                      :habit-log/habit-ids habit-ids}
                     {:db/op          :update
                      :db/doc-type    :user
                      :xt/id          user-id
                      :user/time-zone tz}]))

  {:status  303
   :headers {"location" "/app"}})

(defn app [{:keys [session biff/db]}]
  (let [user-id              (:uid session)
        {:user/keys [email]} (xt/entity db user-id)]
    (ui/page
     {}
     [:div
      (header (pot/map-of email))
      [:div.flex.flex-col.md:flex-row.justify-center
       (let [habits    (q db '{:find  (pull ?habit [*])
                               :where [[?habit :habit/name]
                                       [?habit :user/id user-id]]
                               :in    [user-id]} user-id)
             time-zone (first (first (q db '{:find  [?tz]
                                             :where [[?user :xt/id user-id]
                                                     [?user :user/time-zone ?tz]]
                                             :in    [user-id]} user-id)))]
         (habit-log-create-form (pot/map-of habits time-zone)))]])))

(defn habit-edit-form [{id :xt/id
                        sensitive :habit/sensitive
                        :as habit}]
  (biff/form
   {:hx-post   "/app/habit/edit"
    :hx-swap   "outerHTML"
    :hx-select (str "#habit-list-item-" id)
    :id        (str "habit-list-item-" id)}

   [:div.w-full.md:w-96.ring-4.ring-blue-500.rounded.p-2
    [:input {:type "hidden" :name "id" :value (:xt/id habit)}]

    [:div.grid.grid-cols-1.gap-y-6

     ;; Habit Name
     [:div
      [:label.block.text-sm.font-medium.leading-6.text-gray-900 {:for "habit-name"} "Habit Name"]
      [:div.mt-2
       [:input.rounded-md.shadow-sm.block.w-full.border-0.py-1.5.text-gray-900.focus:ring-2.focus:ring-blue-600
        {:type "text" :name "name" :value (:habit/name habit)}]]]

     ;; Is Sensitive?
     [:div.flex.items-center
      [:input.rounded.shadow-sm.mr-2.text-indigo-600.focus:ring-blue-500.focus:border-indigo-500
       {:type "checkbox" :name "sensitive" :checked (:habit/sensitive habit)}]
      [:label.text-sm.font-medium.leading-6.text-gray-900 {:for "sensitive"} "Is Sensitive?"]]

     ;; Notes
     [:div
      [:label.block.text-sm.font-medium.leading-6.text-gray-900 {:for "notes"} "Notes"]
      [:div.mt-2
       [:textarea.rounded-md.shadow-sm.block.w-full.border-0.py-1.5.text-gray-900.focus:ring-2.focus:ring-blue-600
        {:name "notes"} (:habit/notes habit)]]]

     ;; Submit button
     [:div.mt-2.w-full
      [:button.bg-blue-500.hover:bg-blue-700.text-white.font-bold.py-2.px-4.rounded.w-full
       {:type "submit"
        :script "on click setURLParameter('edit', '')"}
       "Update Habit"]]]]))

(defn habit-list-item [{:habit/keys [sensitive name notes]
                        edit-id     :edit-id
                        id          :xt/id
                        :as         habit}]
  (let [url (str "/app/habits?edit=" id (when sensitive "&sensitive=true"))]
    (if (= edit-id id)
      (habit-edit-form habit)
      [:div.hover:bg-gray-100.transition.duration-150.p-4.border-b.border-gray-200.cursor-pointer.w-full.md:w-96
       {:id          (str "habit-list-item-" id)
        :hx-get      url
        :hx-swap     "outerHTML"
        :script      (str "on click setURLParameter('edit', '" id
                          "') then setURLParameter('sensitive', '" sensitive "')")
        :hx-trigger  "click"
        :hx-select   (str "#habit-list-item-" id)}
       [:div.flex.justify-between
        [:h2.text-md.font-bold name]]
       (when sensitive [:span.text-red-500.mr-2 "🙈"])
       [:p.text-sm.text-gray-600 notes]])))

(defn habit-search-component [{:keys [sensitive search]}]
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
      [:label.mx-4.text-gray-500.line-through {:for "archived"} "Archived"]
      [:input.rounded.mr-2
       {:type         "checkbox"
        :name         "archived"
        :script       "on change setURLParameter(me.name, me.checked) then htmx.trigger('#habit-search', 'search', {})"
        :autocomplete "off"
        :disabled     true
        :checked      false}]]])])

(defn habits-query [{:keys [db user-id]}]
  (q db '{:find  (pull ?habit [*])
          :where [[?habit :habit/name]
                  [?habit :user/id user-id]]
          :in    [user-id]} user-id))

(defn checkbox-true? [v]
  (or (= v "on") (= v "true")))

(defn search-str-xform [s]
  (some-> s str/lower-case str/trim))

(defn habits-page
  "Accepts GET and POST. POST is for search form as body."
  [{:keys [session biff/db params query-params]}]
  (let [user-id                        (:uid session)
        {:user/keys [email time-zone]} (xt/entity db user-id)
        habits                         (habits-query (pot/map-of db user-id))
        edit-id                        (some-> params :edit (java.util.UUID/fromString))
        sensitive                      (or (some-> params :sensitive checkbox-true?)
                                           (some-> query-params :sensitive checkbox-true?))
        search                         (or (some-> params :search search-str-xform)
                                           (some-> query-params :search search-str-xform)
                                           "")]
    (pprint (pot/map-of :habits-page params query-params sensitive search))
    (ui/page
     {}
     [:div
      (header (pot/map-of email))
      [:div.my-4
       [:a.text-blue-500.hover:underline.outline.outline-blue-500.outline-2.font-bold.py-2.px-4.rounded.w-full.md:w-96.mt-6
        {:href "/app/habit/create"}
        "Create habit"]]
      (habit-search-component {:sensitive sensitive :search search})
      [:div {:id "habits-list"}
       (->> habits
            (filter (fn [{:habit/keys [name notes]
                          this-habit-is-sensitive :habit/sensitive
                          id          :xt/id}]
                      (let [matches-name  (str/includes? (str/lower-case name) search)
                            matches-notes (str/includes? (str/lower-case notes) search)]
                        (and (or sensitive
                                 (-> id (= edit-id))
                                 (not this-habit-is-sensitive))
                             (or matches-name
                                 matches-notes)))))
            (map (fn [z] (habit-list-item (-> z (assoc :edit-id edit-id))))))]])))

(defn habit-edit! [{:keys [session params] :as ctx}]
  (let [id        (-> params :id java.util.UUID/fromString)
        name      (:name params)
        notes     (-> params :notes str)
        sensitive (-> params :sensitive boolean)]
    (biff/submit-tx ctx
                    [{:db/op           :update
                      :db/doc-type     :habit
                      :xt/id           id
                      :habit/name      name
                      :habit/notes     notes
                      :habit/sensitive sensitive}])
    {:status  303
     :headers {"location" (str "/app/habits" (when sensitive "?sensitive=true"))}}))

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
      (header (pot/map-of email))
      [:button.bg-blue-500.hover:bg-blue-700.text-white.font-bold.py-2.px-4.rounded.w-full.md:w-96.mt-6
       "Create Habit Log"]
      [:div {:id "habit-logs-list"}
       (->> habit-logs
            (map (fn [z] (habit-log-list-item (-> z (assoc :edit-id edit-id))))))]])))

(def plugin
  {:static {"/about/" about-page}
   :routes ["/app" {:middleware [mid/wrap-signed-in]}
            ["" {:get app}]
            ["/db" {:get db-viz}]
            ["/habits" {:get  habits-page
                        :post habits-page}]
            ["/habit/logs" {:get habit-logs-page}]
            ["/habit/create" {:post habit-create!
                              :get  habit-create-page}]
            ["/habit/log" {:post habit-log-create!}]
            ["/habit/edit" {:post habit-edit!}]]})

(comment

  (t/now)
  (java.time.ZoneId/getAvailableZoneIds))
