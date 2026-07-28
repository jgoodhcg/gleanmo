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
  [{:keys [user-id account-url]}]
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
     [:a.link {:href "/app/stats/habit-patterns"} "habit-logs data viz"]
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

(def quick-action-items
  "Logging destinations ordered by measured use (28-day Plausible sample — see
   roadmap/qol-quick-actions.md item 1). Shared by the sidebar and the
   /app/log hub so the two can never drift apart.

   `:lead?` marks the timer workspace, which the sidebar renders among the
   primary surfaces rather than in this list; `:bm?` marks the entry gated
   behind the show-bm-logs setting."
  [{:label "⏱️ timers", :href "/app/timers", :lead? true}
   {:label "habit log", :href "/app/crud/form/habit-log/new"}
   {:label "project log", :href "/app/crud/form/project-log/new"}
   {:label "medication log",
    :href  "/app/crud/form/medication-log/new"}
   {:label "bm log",
    :href  "/app/crud/form/bm-log/new",
    :bm?   true}
   {:label "workout", :href "/app/exercise/session"}
   {:label "bouldering", :href "/app/boulder/session"}
   {:label "symptom log", :href "/app/crud/form/symptom-log/new"}
   {:label "mood log", :href "/app/crud/form/mood-log/new"}
   {:label "meditation log", :href "/app/crud/form/meditation-log/new"}
   {:label "reading log", :href "/app/crud/form/reading-log/new"}
   {:label "calendar event", :href "/app/crud/form/calendar-event/new"}
   {:label "task (full form)", :href "/app/crud/form/task/new"}])

(defn visible-quick-actions
  "Quick action items with the bm-log entry dropped unless the user has bm
   logs turned on."
  [show-bm-logs]
  (remove #(and (:bm? %) (not show-bm-logs)) quick-action-items))

(def primary-surfaces
  "Layer 1 of the navigation: the handful of places the app is actually used
   from, grouped by intent rather than by implementation. Everything else is
   reachable *through* these rather than sitting beside them in a flat list.
   Shared by the desktop sidebar and the mobile tab bar so the two agree."
  [{:label "home",   :icon "🏠", :href "/app"}
   {:label "timers", :icon "⏱️", :href "/app/timers"}
   {:label "log",    :icon "➕", :href "/app/log"}
   {:label "today",  :icon "✅", :href "/app/task/today"}])

(defn- surface-active?
  "Whether `href` is the surface the current request is on. Home is matched
   exactly; everything else by prefix so drill-down pages keep their tab lit."
  [uri href]
  (if (= href "/app")
    (= uri "/app")
    (and uri (str/starts-with? uri href))))

(defn mobile-tab-bar
  "Fixed bottom navigation for the mobile PWA — the primary layer on a phone,
   where a hamburger-only nav buries the things used most.

   z-40 sits above page content; `side-bar` reserves room for it with
   `pb-24 md:pb-0` on the main content area. The last tab swaps in the
   sidebar, which holds the full layered navigation and its own close."
  [ctx]
  (let [uri (:uri ctx)]
    [:nav.fixed.bottom-0.inset-x-0.z-40.md:hidden.bg-dark-surface.border-t.border-dark
     {:aria-label "Primary"}
     [:div.flex.items-stretch.justify-around
      (for [{:keys [label icon href]} primary-surfaces
            :let [active? (surface-active? uri href)]]
        [:a
         (cond-> {:key   href
                  :href  href
                  ;; Class as a string, not keyword shorthand: Rum splits on
                  ;; "." so `.gap-0.5` would become "gap-0 5".
                  :class (str "flex flex-col items-center justify-center gap-1 "
                              "py-2 flex-1 no-underline transition-colors "
                              (if active? "text-neon-cyan" "text-gray-400"))}
           active? (assoc :aria-current "page"))
         [:span.text-lg.leading-none icon]
         [:span.text-xs.tracking-wide label]])
      [:button
       {:type "button"
        :class (str "flex flex-col items-center justify-center gap-1 py-2 "
                    "flex-1 bg-transparent border-none text-gray-400 "
                    "cursor-pointer")
        :aria-label "Open navigation menu"
        :aria-controls "sidebar"
        ;; Swaps the sidebar in for the page content. The old mobile top bar
        ;; used to be toggled here too; it no longer exists, and referencing
        ;; a missing element would throw and leave the menu unopenable.
        :onclick
        "document.getElementById('sidebar').classList.toggle('hidden');
         document.getElementById('sidebar').classList.toggle('flex');
         document.getElementById('side-bar-page-content').classList.toggle('hidden');"}
       [:span.text-lg.leading-none "☰"]
       [:span.text-xs.tracking-wide "more"]]]]))

(defn side-bar
  [{:keys [session] :as ctx} & content]
  (let [user-id     (:uid session)
        {:keys [show-sensitive show-archived show-bm-logs]}
        (query/resolve-user-settings ctx)
        account-url (str "/app/users/" user-id)
        {:keys [super-user]} (query/get-user-authz (:biff/db ctx) user-id)
        super-user? (true? super-user)]
    [:div.flex.min-h-screen.overflow-x-hidden
     ;; Skip link for keyboard users (visually hidden until focused)
     [:a.link
      {:href "#side-bar-page-content"
       :class
       "sr-only focus:not-sr-only focus:absolute focus:top-2 focus:left-2 focus:z-50 focus:bg-dark-surface focus:px-4 focus:py-2 focus:rounded"}
      "Skip to content"]
     ;; Sidebar
     ;; `w-full md:w-64`: on a phone the sidebar replaces the page rather than
     ;; sitting beside it, so a 16rem column would leave dead space to its
     ;; right and needlessly small tap targets.
     [:div#sidebar.hidden.md:flex.flex-col.space-y-4.bg-dark-surface.p-4.z-50.border-r.border-dark.w-full.md:w-64.flex-shrink-0
      ;; Wordmark, plus a mobile-only close. The sidebar replaces the page
      ;; content when open, so on a phone it hides the tab bar that opened it;
      ;; without this the only way out is to navigate somewhere.
      [:div.mb-2.flex.items-center.justify-between.gap-3
       (gleanmo-wordmark)
       [:button.md:hidden.text-2xl.leading-none.text-gray-400.bg-transparent.border-none.cursor-pointer
        {:type "button"
         :aria-label "Close navigation menu"
         :aria-controls "sidebar"
         :onclick
         "document.getElementById('sidebar').classList.toggle('hidden');
          document.getElementById('sidebar').classList.toggle('flex');
          document.getElementById('side-bar-page-content').classList.toggle('hidden');"}
        "✕"]]
      ;; Turn off sensitive button (when sensitive mode is on)
      (turn-off-sensitive-button show-sensitive user-id)
      ;; Turn off archived button (when archived mode is on)
      (turn-off-archived-button show-archived user-id)
      ;; Hide BM logs button (when BM logs are visible)
      (turn-off-bm-logs-button show-bm-logs user-id)

      ;; Layer 1 — primary surfaces, same set as the mobile tab bar.
      (for [{:keys [label icon href]} primary-surfaces]
        [:a.link.font-semibold {:key href, :href href} (str icon " " label)])
      [:hr.border-dark]

      ;; Layer 2 — log something. Ordered by measured use (28-day Plausible
      ;; sample, see roadmap/qol-quick-actions.md item 1); the order lives in
      ;; `quick-action-items`, shared with the home strip and the /app/log hub.
      [:div.text-xs.text-gray-400.uppercase.tracking-wide.mb-2 "Log Something"]
      (for [{:keys [label href]} (remove :lead? (visible-quick-actions
                                                 show-bm-logs))]
        [:a.link {:key href, :href href} label])
      [:hr.border-dark]

      ;; Layer 3 — look back at what was logged.
      [:div.text-xs.text-gray-400.uppercase.tracking-wide.mb-2 "Review"]
      [:a.link {:href "/app/calendar/year"} "📅 calendar (year)"]
      [:a.link {:href "/app/dashboards/stats"} "📊 stats & charts"]
      [:a.link {:href "/app/stats/medication-history"} "💊 medication history"]
      [:a.link {:href "/app/dashboards/activity-logs"} "📋 activity logs"]
      [:hr.border-dark]

      ;; Layer 4 — manage the data and the account behind it all.
      [:div.text-xs.text-gray-400.uppercase.tracking-wide.mb-2 "Manage"]
      [:a.link {:href "/app/dashboards/entities"} "📦 manage entities"]
      [:a.link {:href "/app/task/focus"} "🎯 task focus"]
      [:a.link {:href account-url} "⚙️ account"]
      (when super-user?
        [:a.link {:href "/app/monitoring/performance"} "🛡️ monitoring"])

      ;; Subtle Sign out button
      (biff/form
       {:action "/auth/signout",
        :class  "mt-4"}
       [:button.btn {:type "submit"}
        "sign out"])]

     ;; Main content area. `pb-24 md:pb-0` reserves room for the fixed mobile
     ;; tab bar rendered just below: the clearance lives here, with the chrome
     ;; that causes the obstruction, so every page gets it — including ones
     ;; that still use their own shell instead of `layout/page-shell`.
     ;;
     ;; Top padding is small and uniform. It used to be `pt-12`, clearing a
     ;; fixed mobile top bar that no longer exists — and because that bar was
     ;; `md:hidden`, desktop was paying 3rem of dead space for a bar that was
     ;; never rendered there.
     [:div.flex-grow.bg-dark.pt-4.px-4.pb-24.md:pb-0.min-w-0
      {:id "side-bar-page-content"
       :tabindex "-1"}
      content
      (mobile-tab-bar ctx)]]))

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

(defn user-zone-id
  "Return the current user's ZoneId from settings, defaulting safely to UTC."
  [ctx]
  (try
    (ZoneId/of (get-user-time-zone ctx))
    (catch Exception _
      (ZoneId/of "UTC"))))

(defn user-local-date
  "Return the current local date in the current user's timezone."
  [ctx]
  (java.time.LocalDate/now (user-zone-id ctx)))

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
