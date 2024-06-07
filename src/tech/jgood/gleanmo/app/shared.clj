(ns tech.jgood.gleanmo.app.shared
  (:require
   [clojure.string :as str]
   [com.biffweb :as biff :refer [q]]
   [tick.core :as t]
   [xtdb.api :as xt])
  (:import
   [java.time ZoneId])
  )

(defn nav-bar [{:keys [email]}]
  [:div.space-x-8
   [:a.link {:href "/app/my-user"} email]
   [:a.link {:href "/app"} "home"]
   [:a.link {:href "/app/habits"} "habits"]
   [:a.link {:href "/app/habit-logs"} "habit logs"]
   (biff/form
    {:action "/auth/signout"
     :class  "inline"}
    [:button.text-blue-500.hover:text-blue-800 {:type "submit"}
     "Sign out"])])

(def local-date-time-fmt "yyyy-MM-dd'T'HH:mm:ss")

(def zoned-date-time-fmt "yyyy-MM-dd HH:mm:ss z")

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
