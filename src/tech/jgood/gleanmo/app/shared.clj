(ns tech.jgood.gleanmo.app.shared
  (:require
   [clojure.string :as str]
   [com.biffweb    :as    biff
    :refer [q]]
   [tick.core      :as t]
   [xtdb.api       :as xt])
  (:import
   [java.time ZoneId]
   [java.time LocalDateTime]
   [java.time ZonedDateTime]))

(defn nav-bar
  [{:keys [email]}]
  [:div.space-x-8
   [:a.link {:href "/app/my-user"} email]
   [:a.link {:href "/app"} "home"]
   [:a.link {:href "/app/habits"} "habits"]
   [:a.link {:href "/app/habit-logs"} "habit-logs"]
   [:a.link {:href "/app/locations"} "locations"]
   [:a.link {:href "/app/meditations"} "meditations"]
   [:a.link {:href "/app/meditation-logs"} "meditation-logs"]
   [:a.link {:href "/app/ical-urls"} "ical-urls"]
   [:a.link {:href "/app/dv/habit-logs"} "habit-logs data viz"]
   (biff/form
    {:action "/auth/signout",
     :class  "inline"}
    [:button.link {:type "submit"}
     "Sign out"])])

(defn turn-off-sensitive-button
  "Show a button to turn off sensitive display when sensitive mode is enabled.
   Takes a boolean show-sensitive parameter and user-id for the form action."
  [show-sensitive user-id]
  (when show-sensitive
    [:div.mb-2.p-2.bg-gray-100.border.border-gray-300.rounded.text-sm
     [:span.text-gray-700.text-xs "Sensitive items visible"]
     (biff/form
      {:action (str "/app/users/" user-id "/settings/turn-off-sensitive"),
       :method "post",
       :class  "inline ml-2"}
      [:button.text-xs.bg-gray-200.hover:bg-gray-300.px-2.py-1.rounded.transition-colors.text-gray-600
       {:type    "submit",
        :onclick "return confirm('Turn off sensitive item display?')",
        :title   "Click to hide all sensitive items"}
       "Hide"])]))

(defn side-bar
  [{:keys [biff/db session], :as ctx} & content]
  (let
   [user-id (:uid session)
    {:user/keys [email]} (when user-id
                           (first (q db
                                     {:find  '(pull ?e [:user/email]),
                                      :where [['?e :xt/id user-id]],
                                      :in    ['user-id]}
                                     user-id)))
    show-sensitive
    (when user-id
      (if-let
       [settings
        (first
         (q db
            {:find '(pull ?e [*]),
             :where
             ['[?e :user/id user-id]
              '[?e :tech.jgood.gleanmo.schema.meta/type :user-settings]
              '(not [?e :tech.jgood.gleanmo.schema.meta/deleted-at])],
             :in ['user-id]}
            user-id))]
        (boolean (:user-settings/show-sensitive settings))
        false))]
    [:div.flex.min-h-screen
     ;; Sidebar
     [:div#sidebar.hidden.md:flex.flex-col.space-y-4.bg-dark-surface.p-4.z-50.border-r.border-dark.w-64.flex-shrink-0
      ;; User email link
      [:a.link {:href "/app/my-user"} email]
      ;; Turn off sensitive button (when sensitive mode is on)
      (turn-off-sensitive-button show-sensitive user-id)
      ;; Navigation links
      [:a.link {:href "/app"} "home"]
      [:hr.border-dark]
      [:a.link {:href "/app/crud/form/habit-log/new"} "add habit-log"]
      [:a.link {:href "/app/crud/form/meditation-log/new"} "add meditation-log"]
      [:a.link {:href "/app/crud/form/bm-log/new"} "add bm-log"]
      [:a.link {:href "/app/crud/form/medication-log/new"} "add medication-log"]
      [:hr.border-dark]
      ;; CRUD
      [:a.link {:href "/app/crud/habit"} "habits"]
      [:a.link {:href "/app/crud/habit-log"} "habit-logs"]
      [:a.link {:href "/app/crud/bm-log"} "bm-logs"]
      [:a.link {:href "/app/crud/medication"} "medications"]
      [:a.link {:href "/app/crud/medication-log"} "medication-logs"]
      [:a.link {:href "/app/crud/location"} "locations"]
      [:a.link {:href "/app/crud/meditation"} "meditations"]
      [:a.link {:href "/app/crud/meditation-log"} "meditation-logs"]
      ;; [:a.link {:href "/app/crud/ical-url"} "ical-urls"]
      ;; Insight
      [:hr.border-dark]
      [:a.link {:href "/app/dv/habit-logs"} "habit-logs data viz"]
      [:a.link {:href "/app/dv/habit-dates"} "habit-logs predictions"]
      [:a.link {:href "/app/dv/meditation-stats"} "mediation-logs stats"]

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
      [:div.flex
       [:button.mr-4
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
       [:a.link {:href "/app/my-user"} email]]]]))

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
    (or (first (first (q db
                         '{:find  [?tz],
                           :where [[?user :xt/id user-id]
                                   [?user :user/time-zone ?tz]],
                           :in    [user-id]}
                         user-id)))
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

(defn get-last-tx-time
  [{:keys [biff/db xt/id]}]
  (let [history (xt/entity-history db id :desc)]
    (-> history
        first
        :xtdb.api/tx-time)))

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
