(ns tech.jgood.gleanmo.app.shared
  (:require
   [clojure.string :as str]
   [com.biffweb :as biff :refer [q]]
   [tick.core :as t]
   [xtdb.api :as xt])
  (:import
   [java.time ZoneId]
   [java.time LocalDateTime]
   [java.time ZonedDateTime]))

(defn nav-bar [{:keys [email]}]
  [:div.space-x-8
   [:a.link {:href "/app/my-user"} email]
   [:a.link {:href "/app"} "home"]
   [:a.link {:href "/app/habits"} "habits"]
   [:a.link {:href "/app/habit-logs"} "habit-logs"]
   [:a.link {:href "/app/locations"} "locations"]
   [:a.link {:href "/app/meditation-types"} "meditation-types"]
   [:a.link {:href "/app/meditation-logs"} "meditation-logs"]
   [:a.link {:href "/app/ical-urls"} "ical-urls"]
   (biff/form
    {:action "/auth/signout"
     :class  "inline"}
    [:button.text-blue-500.hover:text-blue-800 {:type "submit"}
     "Sign out"])])

(defn side-bar [{:keys [email]} & content]
  [:div.flex.min-h-screen
   ;; Sidebar
   [:div#sidebar.hidden.md:flex.flex-col.space-y-4.bg-gray-100.p-4.z-50
    {:class "md:w-auto"}
    ;; User email link
    [:a.link.text-gray-800 {:href "/app/my-user"} email]

    ;; Navigation links
    [:a.link.text-gray-800 {:href "/app"} "home"]
    [:a.link.text-gray-800 {:href "/app/habits"} "habits"]
    [:a.link.text-gray-800 {:href "/app/habit-logs"} "habit-logs"]
    [:a.link.text-gray-800 {:href "/app/locations"} "locations"]
    [:a.link.text-gray-800 {:href "/app/meditation-types"} "meditation-types"]
    [:a.link.text-gray-800 {:href "/app/meditation-logs"} "meditation-logs"]
    [:a.link.text-gray-800 {:href "/app/ical-urls"} "ical-urls"]

    ;; Subtle Sign out button
    (biff/form
     {:action "/auth/signout"
      :class  "mt-4"}
     [:button.btn.bg-gray-300.hover:bg-gray-400.text-gray-800 {:type "submit"}
      "Sign out"])]

   ;; Main content area
   [:div.flex-grow.bg-white.pt-12.px-4
    {:id "side-bar-page-content"}
    content]

   ;; Mobile menu button
   [:div.fixed.md:hidden.p-2.bg-white.w-full
    {:id "menu-btn"}
    [:div.flex
     [:button.mr-4
      {:type    "button"
       :class   "text-gray-800 focus:outline-none"
       ;; TODO move this to js?
       :onclick "document.getElementById('sidebar').classList.toggle('hidden');
                 document.getElementById('sidebar').classList.toggle('flex');
                 document.getElementById('menu-btn').classList.toggle('hidden');
                 document.getElementById('side-bar-page-content').classList.toggle('hidden');"}
      ;; Menu icon (hamburger)
      [:svg {:class   "h-6 w-6"
             :xmlns   "http://www.w3.org/2000/svg"
             :fill    "none"
             :viewBox "0 0 24 24"
             :stroke  "currentColor"}
       [:path {:stroke-linecap "round" :stroke-linejoin "round" :stroke-width "2"
               :d              "M4 6h16M4 12h16M4 18h16"}]]]
     [:a.link.text-gray-800 {:href "/app/my-user"} email]]]])

(def local-date-time-fmt "yyyy-MM-dd'T'HH:mm")

(def zoned-date-time-fmt "yyyy-MM-dd HH:mm z")

(defn format-date-time-local [instant zone-id]
  (->> (t/in instant zone-id)
       (t/format (t/formatter local-date-time-fmt))))

(defn get-user-time-zone [{:keys [biff/db session]}]
  (let [user-id (:uid session)]
    (or (first (first (q db '{:find  [?tz]
                              :where [[?user :xt/id user-id]
                                      [?user :user/time-zone ?tz]]
                              :in    [user-id]} user-id)))
        (t/zone "UTC"))))

(defn ensure-vector [item]
  (if (vector? item)
    item
    [item]))

(defn param-true?
  "For form checkboxes and query params"
  [v]
  (or (= v "on") (= v "true")))

(defn search-str-xform [s]
  (some-> s str/lower-case str/trim))

(defn link-button [{:keys [href label]}]
  [:a.text-blue-500.hover:underline.outline.outline-blue-500.outline-2.font-bold.py-2.px-4.rounded.w-full.md:w-96.mt-6
   {:href href} label])

(defn get-last-tx-time [{:keys [biff/db xt/id]}]
  (let [history          (xt/entity-history db id :desc)]
    (-> history first :xtdb.api/tx-time)))

(defn time-zone-select [time-zone]
  [:div
   [:label.block.text-sm.font-medium.leading-6.text-gray-900 {:for "time-zone"} "Time Zone"]
          [:div.mt-2
           [:select.rounded-md.shadow-sm.block.w-full.border-0.py-1.5.text-gray-900.focus:ring-2.focus:ring-blue-600
            {:name "time-zone" :required true :autocomplete "off"}
            (->> (ZoneId/getAvailableZoneIds)
                 sort
                 (map (fn [zoneId]
                        [:option {:value    zoneId
                                  :selected (= zoneId time-zone)} zoneId])))]]])

(defn str->instant
  "Return nil if string is empty or invalid format"
  [date-time-str zone-id]
  (when (not (str/blank? date-time-str))
    (some-> date-time-str
            (LocalDateTime/parse)
            (ZonedDateTime/of zone-id)
            (t/instant))))
