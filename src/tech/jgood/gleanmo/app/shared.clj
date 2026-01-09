(ns tech.jgood.gleanmo.app.shared
  (:require
   [clojure.string :as str]
   [com.biffweb    :as biff]
   [tech.jgood.gleanmo.db.queries :as query]
   [tick.core      :as t])
  (:import
   [java.time ZoneId]
   [java.time LocalDateTime]
   [java.time ZonedDateTime]))

(defn gleanmo-wordmark
  "Render the Gleanmo wordmark used in navigation chrome."
  []
  [:div.gleanmo-wordmark
   [:span.gleanmo-wordmark-bracket "[["]
   [:span.gleanmo-wordmark-core "GLEANMO"]
   [:span.gleanmo-wordmark-bracket "]]"]])

(defn nav-bar
  [{:keys [email user-id account-url]}]
  (let [account-link (or account-url
                         (when user-id (str "/app/users/" user-id))
                         "/app/my-user")]
    [:div.flex.items-center.space-x-6
     (gleanmo-wordmark)
     [:a.link {:href account-link} "account"]
     [:a.link {:href "/app"} "home"]
     [:a.link {:href "/app/habits"} "habits"]
     [:a.link {:href "/app/habit-logs"} "habit-logs"]
     [:a.link {:href "/app/locations"} "locations"]
     [:a.link {:href "/app/meditations"} "meditations"]
     [:a.link {:href "/app/visualizations"} "visualizations"]
     [:a.link {:href "/app/meditation-logs"} "meditation-logs"]
     [:a.link {:href "/app/ical-urls"} "ical-urls"]
     [:a.link {:href "/app/dv/habit-logs"} "habit-logs data viz"]
     (biff/form
      {:action "/auth/signout",
       :class  "inline"}
      [:button.link {:type "submit"}
       "Sign out"])]))

(defn turn-off-sensitive-button
  "Show a button to turn off sensitive display when sensitive mode is enabled.
   Takes a boolean show-sensitive parameter and user-id for the form action."
  [show-sensitive user-id]
  (when show-sensitive
    (biff/form
     {:action (str "/app/users/" user-id "/settings/turn-off-sensitive"),
      :method "post",
      :class  "inline"}
     [:button.mb-3.p-3.bg-dark-surface.border.border-neon-pink.rounded-lg.shadow-sm.w-full.transition-all.duration-200.hover:bg-dark.hover:shadow-lg
      {:style {:box-shadow "0 0 8px rgba(236, 72, 153, 0.2)"}}
      [:div.flex.items-center.justify-between
       [:div.flex.items-center.gap-2
        [:span.font-medium.text-md.text-neon-pink "🔒 Sensitive"]]]])))

(defn turn-off-archived-button
  "Show a button to turn off archived display when archived mode is enabled.
   Takes a boolean show-archived parameter and user-id for the form action."
  [show-archived user-id]
  (when show-archived
    (biff/form
     {:action (str "/app/users/" user-id "/settings/turn-off-archived"),
      :method "post",
      :class  "inline"}
     [:button.mb-3.p-3.bg-dark-surface.border.border-neon-cyan.rounded-lg.shadow-sm.w-full.transition-all.duration-200.hover:bg-dark.hover:shadow-lg
      {:style {:box-shadow "0 0 8px rgba(6, 182, 212, 0.2)"}}
      [:div.flex.items-center.justify-between
       [:div.flex.items-center.gap-2
        [:span.font-medium.text-md.text-neon-cyan "📦 Archived"]]]])))

(defn turn-off-bm-logs-button
  "Show a button to hide BM logs in overview, sidebar, and dashboards when visible."
  [show-bm-logs user-id]
  (when show-bm-logs
    (biff/form
     {:action (str "/app/users/" user-id "/settings/turn-off-bm-logs"),
      :method "post",
      :class  "inline"}
     [:button.mb-3.p-3.bg-dark-surface.border.rounded-lg.shadow-sm.w-full.transition-all.duration-200.hover:bg-dark.hover:shadow-lg
      {:style {:border-color "#0ea5e9",
               :box-shadow   "0 0 8px rgba(14, 165, 233, 0.2)"}}
      [:div.flex.items-center.justify-between
       [:div.flex.items-center.gap-2
        [:span.font-medium.text-md
         {:style {:color "#0ea5e9"}}
         "🧻 BM logs"]]]])))

(defn side-bar
  [{:keys [biff/db session], :as ctx} & content]
  (let [user-id     (:uid session)
        {:keys [show-sensitive show-archived show-bm-logs]}
        (query/get-user-settings db user-id)
        account-url (str "/app/users/" user-id)
        {:keys [super-user]} (query/get-user-authz db user-id)
        super-user? (true? super-user)]
    [:div.flex.min-h-screen
     ;; Sidebar
     [:div#sidebar.hidden.md:flex.flex-col.space-y-4.bg-dark-surface.p-4.z-50.border-r.border-dark.w-64.flex-shrink-0
      ;; Wordmark
      [:div.mb-2 (gleanmo-wordmark)]
      ;; Turn off sensitive button (when sensitive mode is on)
      (turn-off-sensitive-button show-sensitive user-id)
      ;; Turn off archived button (when archived mode is on)
      (turn-off-archived-button show-archived user-id)
      ;; Hide BM logs button (when BM logs are visible)
      (turn-off-bm-logs-button show-bm-logs user-id)

      ;; Navigation
      [:a.link {:href "/app"} "home"]
      [:a.link {:href account-url} "account"]
      [:hr.border-dark]

      ;; Tasks
      [:a.link.font-semibold {:href "/app/task/triage"} "Task Triage"]
      [:hr.border-dark]

      ;; Quick Add
      [:div.text-xs.text-gray-400.uppercase.tracking-wide.mb-2 "Quick Add"]
      [:a.link {:href "/app/crud/form/task/new"} "task (full form)"]
      [:a.link {:href "/app/crud/form/calendar-event/new"} "calendar event"]
      [:a.link {:href "/app/crud/form/habit-log/new"} "habit log"]
      [:a.link {:href "/app/crud/form/meditation-log/new"} "meditation log"]
      (when show-bm-logs
        [:a.link {:href "/app/crud/form/bm-log/new"} "bm log"])
      [:a.link {:href "/app/crud/form/medication-log/new"} "medication log"]
      [:a.link {:href "/app/crud/form/project-log/new"} "project log"]
      [:hr.border-dark]

      ;; Calendar
      [:div.text-xs.text-gray-400.uppercase.tracking-wide.mb-2 "Calendar"]
      [:a.link {:href "/app/calendar/year"} "📅 calendar (year)"]
      [:hr.border-dark]

      ;; Dashboards
      [:div.text-xs.text-gray-400.uppercase.tracking-wide.mb-2 "Dashboards"]
      [:a.link {:href "/app/dashboards/entities"} "📦 manage entities"]
      [:a.link {:href "/app/dashboards/activity-logs"} "📋 activity logs"]
      [:a.link {:href "/app/dashboards/analytics"} "📊 analytics & insights"]
      [:a.link {:href "/app/timers"} "⏱️ timers"]
      (when super-user?
        [:a.link {:href "/app/monitoring/performance"} "🛡️ monitoring"])

      ;; Subtle Sign out button
      (biff/form
       {:action "/auth/signout",
        :class  "mt-4"}
       [:button.btn {:type "submit"}
        "sign out"])]

     ;; Main content area
     [:div.flex-grow.bg-dark.pt-12.px-4
      {:id "side-bar-page-content"}
      content]

     ;; Mobile menu button
     [:div.fixed.md:hidden.p-2.bg-dark-surface.w-full.border-b.border-dark
      {:id "menu-btn"}
      [:div.flex.items-center.gap-3
       [:button
        {:type "button",
         :class "text-primary focus:outline-none",
         ;; TODO move this to js?
         :onclick
         "document.getElementById('sidebar').classList.toggle('hidden');
                 document.getElementById('sidebar').classList.toggle('flex');
                 document.getElementById('menu-btn').classList.toggle('hidden');
                 document.getElementById('side-bar-page-content').classList.toggle('hidden');"}
        ;; Menu icon (hamburger)
        [:svg
         {:class   "h-6 w-6",
          :xmlns   "http://www.w3.org/2000/svg",
          :fill    "none",
          :viewBox "0 0 24 24",
          :stroke  "currentColor"}
         [:path
          {:stroke-linecap "round",
           :stroke-linejoin "round",
           :stroke-width "2",
           :d "M4 6h16M4 12h16M4 18h16"}]]]
       (gleanmo-wordmark)]]]))

(def local-date-time-fmt "yyyy-MM-dd'T'HH:mm")

(def zoned-date-time-fmt "yyyy-MM-dd HH:mm z")

(defn format-date-time-local
  [instant zone-id]
  (->> (t/in instant zone-id)
       (t/format (t/formatter local-date-time-fmt))))

(defn get-user-time-zone
  "Takes biff context (db, session) and queries for user time zone. If it doesn't exist returns UTC. All returns are Strings."
  [{:keys [biff/db session]}]
  (let [user-id (:uid session)]
    (or (-> (query/get-entity-by-id db user-id)
            :user/time-zone)
        "UTC")))

(defn ensure-vector
  [item]
  (if (vector? item)
    item
    [item]))

(defn param-true?
  "For form checkboxes and query params"
  [v]
  (or (= v "on") (= v "true")))

(defn search-str-xform
  [s]
  (some-> s
          str/lower-case
          str/trim))

(defn link-button
  [{:keys [href label]}]
  [:a.form-button-primary.font-bold.py-2.px-4.rounded.w-full.md:w-96.mt-6
   {:href href} label])

(defn time-zone-select
  [time-zone]
  [:div
   [:label.form-label {:for "time-zone"}
    "Time Zone"]
   [:div.mt-2
    [:select.form-select
     {:name "time-zone", :required true, :autocomplete "off"}
     (->> (ZoneId/getAvailableZoneIds)
          sort
          (map (fn [zoneId]
                 [:option
                  {:value    zoneId,
                   :selected (= zoneId time-zone)} zoneId])))]]])

(defn str->instant
  "Return nil if string is empty or invalid format"
  [date-time-str zone-id]
  (when (not (str/blank? date-time-str))
    (some-> date-time-str
            (LocalDateTime/parse)
            (ZonedDateTime/of zone-id)
            (t/instant))))

(defn str->instant!
  "Convert string to instant, throwing exception if conversion fails.
   Throws IllegalArgumentException if input is blank or parsing fails."
  [date-time-str zone-id]
  (if (str/blank? date-time-str)
    (throw (IllegalArgumentException. "Date time string cannot be blank"))
    (try
      (-> date-time-str
          (LocalDateTime/parse)
          (ZonedDateTime/of zone-id)
          (t/instant))
      (catch Exception e
        (throw (IllegalArgumentException.
                (str "Failed to parse date time string: " date-time-str
                     " with zone ID: " zone-id
                     ". Error: "       (.getMessage e))
                e))))))
