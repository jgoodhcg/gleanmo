(ns tech.jgood.gleanmo.app.habit-log
  (:require
   [clojure.string :as str]
   [com.biffweb :as biff :refer [q]]
   [potpuri.core :as pot]
   [tech.jgood.gleanmo.app.habit :as habit]
   [tech.jgood.gleanmo.app.shared :refer [ensure-vector format-date-time-local
                                          get-last-tx-time get-user-time-zone
                                          link-button local-date-time-fmt param-true? search-str-xform side-bar
                                          time-zone-select zoned-date-time-fmt]]
   [tech.jgood.gleanmo.schema :as schema]
   [tech.jgood.gleanmo.ui :as ui]
   [tick.core :as t]
   [xtdb.api :as xt])
  (:import
   [java.time ZoneId]
   [java.time LocalDateTime]
   [java.time ZonedDateTime]
   [java.util UUID]))

(defn some-sensitive-habits [{habits :habit-log/habits}]
  (->> habits
       (map :habit/sensitive)
       (some true?)))

(defn some-archived-habits [{habits :habit-log/habits}]
  (->> habits
       (map :habit/archived)
       (some true?)))

(defn consolidate-habit-log [habit-log habits user-tz]
   (assoc habit-log
          :user/time-zone user-tz
          :habit-log/habits habits))

(defn all-for-user-query [{:keys [biff/db session sensitive archived]}]
  (let [raw-results (q db '{:find  [(pull ?habit-log [*]) (pull ?habit [*]) ?tz]
                            :where [[?habit-log :user/id user-id]
                                    [?habit-log :habit-log/timestamp]
                                    [?habit-log :habit-log/habit-ids ?habit-id]
                                    [?habit :xt/id ?habit-id]
                                    [?habit :habit/name ?habit-name]
                                    [?user :xt/id user-id]
                                    [?user :user/time-zone ?tz]
                                    (not [?habit ::schema/deleted-at])
                                    (not [?habit-log ::schema/deleted-at])]
                            :in    [user-id]} (:uid session))]
    (cond->> raw-results
      :always         (group-by (fn [[habit-log _ _]] (:xt/id habit-log))) ; Group by habit-log id
      :always         (map (fn [[log-id grouped-tuples]]
                             ;; Extract the habit-log map from the first tuple and tz from last
                             (let [habit-log-map (-> grouped-tuples first first)
                                   tz            (-> grouped-tuples first last)
                                   habits        (->> grouped-tuples
                                                      (map second))]
                               (consolidate-habit-log habit-log-map
                                                      habits
                                                      tz))))
      :always         (into [])
      :always         (sort-by :habit-log/timestamp)
      :always         (reverse)
      (not sensitive) (remove some-sensitive-habits)
      (not archived)  (remove some-archived-habits))))

(defn single-for-user-query [{:keys [biff/db session :xt/id]}]
  (let [user-id (:uid session)
        result (q db '{:find  [(pull ?habit-log [*]) (pull ?habit [*])]
                   :where [[?habit-log :xt/id log-id]
                           [?habit-log ::schema/type :habit-log]
                           [?habit-log :user/id user-id]
                           [?habit-log :habit-log/habit-ids ?habit-id]
                           [?habit :xt/id ?habit-id]
                           [?habit :habit/name ?habit-name]
                           (not [?habit ::schema/deleted-at])
                           (not [?habit-log ::schema/deleted-at])]
                   :in    [user-id log-id]} user-id id)]
    (consolidate-habit-log (-> result first first)
                           (->> result (map second))
                           (-> result first last))))

(defn list-item [{:habit-log/keys [timestamp habits notes time-zone]
                  user-time-zone  :user/time-zone
                  id              :xt/id}]
  (let [formatted-timestamp (when timestamp
                              (-> timestamp
                                  (t/instant)
                                  (t/in (t/zone (or time-zone user-time-zone)))
                                  (->> (t/format (t/formatter zoned-date-time-fmt)))))]
    [:a {:href (str "/app/habit-logs/" id "/edit")}
     [:div.hover:bg-gray-100.transition.duration-150.p-4.border-b.border-gray-200.cursor-pointer.w-full.md:w-96
      {:id (str "habit-log-list-item-" id)}
      [:span formatted-timestamp]
      (when notes [:p.text-sm.text-gray-600 notes])
      [:div.flex.flex-col.mt-2
       (for [{habit-name :habit/name} habits]
         [:span habit-name])]]]))

(defn any-sensitive? [habits]
  (->> habits
       (some (fn [{:keys [habit/sensitive]}] sensitive))))

(defn any-archived? [habits]
  (->> habits
       (some (fn [{:keys [habit/archived]}] archived))))

(defn new-form [{:keys [session biff/db params]
                 :as   ctx}]
  (let [sensitive            (some-> params :sensitive param-true?)
        archived             (some-> params :archived param-true?)
        persist-query-params (or sensitive archived)
        user-id              (:uid session)
        {:user/keys [email]} (xt/entity db user-id)
        ctx*                 (merge ctx (pot/map-of sensitive archived))
        habits               (habit/all-for-user-query ctx*)
        recent-logs          (->> (all-for-user-query ctx*)
                                  (take 3))
        time-zone            (get-user-time-zone ctx)
        time-zone            (if (some? time-zone) time-zone "US/Eastern")
        current-time         (format-date-time-local (t/now) time-zone)]
    (ui/page
     {}
     [:div
      (side-bar (pot/map-of email)
                [:div.my-4
                 (if sensitive
                   [:a.link {:href "/app/new/habit-log"} "hide sensitive"]
                   [:a.link {:href "/app/new/habit-log?sensitive=true"} "sensitive"])]
                [:div.w-full.md:w-96.space-y-8
                 (biff/form
                  {:hx-post   (str "/app/habit-logs"
                                   (when persist-query-params "?")
                                   (when sensitive "sensitive=true")
                                   (when archived  "&archived=true"))
                   :hx-swap   "outerHTML"
                   :hx-select "#log-habit-form"
                   :id        "log-habit-form"}

                  [:div
                   [:h2.text-base.font-semibold.leading-7.text-gray-900 "Log Habit"]]

                  [:div.grid.grid-cols-1.gap-y-6
                   ;; Time Zone selection
                   ;; TODO add search
                   (time-zone-select time-zone)

                   ;; Notes input
                   [:div
                    [:label.block.text-sm.font-medium.leading-6.text-gray-900 {:for "notes"} "Notes"]
                    [:div.mt-2
                     [:textarea.rounded-md.shadow-sm.block.w-full.border-0.py-1.5.text-gray-900.focus:ring-2.focus:ring-blue-600
                      {:name         "notes"
                       :rows         3
                       :placeholder  "Any additional notes..."
                       :autocomplete "off"}]]]

                   ;; Timestamp input
                   [:div
                    [:label.block.text-sm.font-medium.leading-6.text-gray-900 {:for "timestamp"} "Timestamp"]
                    [:div.mt-2
                     [:input.rounded-md.shadow-sm.block.w-full.border-0.py-1.5.text-gray-900.focus:ring-2.focus:ring-blue-600
                      {:type "datetime-local" :name "timestamp" :required true :value current-time}]]]

                   ;; TODO add search and filters
                   ;; Consider something better than a select list, maybe icons?
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
                     {:type "submit"} "Log Habit"]]

                   ;; Recent logs
                   [:div.mt-4
                    [:h2.text-base.font-semibold.leading-7.text-gray-900 "Recent logs"]
                    (->> recent-logs
                         (map (fn [z] (list-item z))))]])])])))

(defn create! [{:keys [session params] :as ctx}]
  (let [sensitive            (some-> params :sensitive param-true?)
        archived             (some-> params :archived param-true?)
        persist-query-params (or sensitive archived)
        id-strs              (-> params :habit-refs ensure-vector)
        time-zone            (-> params :time-zone)
        timestamp-str        (-> params :timestamp)
        notes                (-> params :notes)
        local-datetime       (LocalDateTime/parse timestamp-str)
        zone-id              (ZoneId/of time-zone)
        zdt                  (ZonedDateTime/of local-datetime zone-id)
        timestamp            (-> zdt (t/instant))
        habit-ids            (->> id-strs
                                  (map #(some-> % UUID/fromString))
                                  set)
        user-id              (:uid session)
        user-time-zone       (get-user-time-zone ctx)
        new-tz               (not= user-time-zone time-zone)
        now                  (t/now)]

    (biff/submit-tx ctx
                    (vec (remove nil?
                                 [(merge
                                   {:db/doc-type :habit-log
                                    ::schema/type :habit-log
                                    :user/id user-id
                                    :habit-log/timestamp timestamp
                                    :habit-log/time-zone time-zone
                                    :habit-log/habit-ids habit-ids
                                    ::schema/created-at now}
                                   (when (not (str/blank? notes))
                                     {:habit-log/notes notes}))
                                  (when new-tz
                                    {:db/op :update
                                     :db/doc-type :user
                                     :xt/id user-id
                                     :user/time-zone time-zone})])))

    {:status  303
     :headers {"location"
               (str "/app/new/habit-log"
                    (when persist-query-params "?")
                    (when sensitive "sensitive=true")
                    (when archived  "&archived=true"))}}))

(defn list-page
  "Accepts GET and POST. POST is for search form as body."
  [{:keys [session biff/db params]
    :as   ctx}]
  (let [user-id                        (:uid session)
        {:user/keys [email time-zone]} (xt/entity db user-id)
        sensitive                      (some-> params :sensitive param-true?)
        archived                       (some-> params :archived param-true?)
        habit-logs                     (->> (merge ctx (pot/map-of sensitive archived))
                                            (all-for-user-query)
                                            (take 15))
        search                         (or (some-> params :search search-str-xform)
                                           "")]
    (ui/page
     {}
     [:div
      (side-bar (pot/map-of email)
                [:div.my-4
                 (link-button {:href  "/app/new/habit-log"
                               :label "Create Habit Log"})]
                [:div.my-4
                 (if sensitive
                   [:a.link {:href "/app/habit-logs"} "hide sensitive"]
                   [:a.link {:href "/app/habit-logs?sensitive=true"} "sensitive"])]
                ;; TODO add filter options and search
                [:div {:id "habit-logs-list"}
                 (cond->> habit-logs
                   :always (map (fn [log] (list-item log))))])])))

(defn edit-form [{:keys [path-params
                         params
                         session
                         biff/db]
                  :as   ctx}]
  (let [log-id               (-> path-params :id UUID/fromString)
        sensitive            (some-> params :sensitive param-true?)
        archived             (some-> params :archived param-true?)
        user-id              (:uid session)
        {:user/keys [email]} (xt/entity db user-id)
        habit-log            (single-for-user-query (merge ctx {:xt/id log-id}))
        habits               (habit/all-for-user-query (merge ctx (pot/map-of sensitive archived)))
        time-zone            (or (get-in habit-log [:habit-log/time-zone])
                                 (get-user-time-zone ctx))
        formatted-timestamp  (-> (get-in habit-log [:habit-log/timestamp])
                                 (t/in (t/zone time-zone))
                                 (->> (t/format (t/formatter local-date-time-fmt))))
        latest-tx-time       (-> (get-last-tx-time (merge ctx {:xt/id log-id}))
                                 (t/in (t/zone time-zone))
                                 (->> (t/format (t/formatter zoned-date-time-fmt))))
        formatted-created-at (-> habit-log
                                 ::schema/created-at
                                 (t/in (t/zone time-zone))
                                 (->> (t/format (t/formatter zoned-date-time-fmt))))]
    (ui/page
     {}
     [:div
      (side-bar (pot/map-of email)
                [:div.my-4
                 (if sensitive
                   [:a.link {:href (str "/app/habit-logs/" log-id "/edit")} "hide sensitive"]
                   [:a.link {:href (str "/app/habit-logs/" log-id "/edit?sensitive=true")} "sensitive"])]
                [:div.w-full.md:w-96.space-y-8
                 (biff/form
                  {:hx-post   (str "/app/habit-logs/" log-id)
                   :hx-swap   "outerHTML"
                   :hx-select "#edit-habit-log-form"
                   :id        "edit-habit-log-form"}

                  [:input {:type "hidden" :name "id" :value log-id}]

                  [:div
                   [:h2.text-base.font-semibold.leading-7.text-gray-900 "Edit Habit Log"]
                   [:p.mt-1.text-sm.leading-6.text-gray-600 "Edit your habit log entry."]]

                  ;; Time Zone selection
                  (time-zone-select time-zone)


                  ;; Notes input
                  [:div
                   [:label.block.text-sm.font-medium.leading-6.text-gray-900 {:for "notes"} "Notes"]
                   [:div.mt-2
                    [:textarea.rounded-md.shadow-sm.block.w-full.border-0.py-1.5.text-gray-900.focus:ring-2.focus:ring-blue-600
                     {:name         "notes"
                      :rows         3
                      :placeholder  "Any additional notes..."
                      :autocomplete "off"} (get-in habit-log [:habit-log/notes])]]]

                  ;; Timestamp input
                  [:div
                   [:label.block.text-sm.font-medium.leading-6.text-gray-900 {:for "timestamp"} "Timestamp"]
                   [:div.mt-2
                    [:input.rounded-md.shadow-sm.block.w-full.border-0.py-1.5.text-gray-900.focus:ring-2.focus:ring-blue-600
                     {:type "datetime-local" :name "timestamp" :required true :value formatted-timestamp}]]]

                  ;; Habits selection
                  [:div
                   [:label.block.text-sm.font-medium.leading-6.text-gray-900 {:for "habit-refs"} "Habits"]
                   [:div.mt-2
                    [:select.rounded-md.shadow-sm.block.w-full.border-0.py-1.5.text-gray-900.focus:ring-2.focus:ring-blue-600
                     {:name "habit-refs" :multiple true :required true :autocomplete "off"}
                     (map (fn [habit]
                            [:option {:value    (:xt/id habit)
                                      :selected (contains? (set (get-in habit-log [:habit-log/habit-ids])) (:xt/id habit))}
                             (:habit/name habit)])
                          habits)]]]

                  ;; Submit button
                  [:div.mt-2.w-full
                   [:button.bg-blue-500.hover:bg-blue-700.text-white.font-bold.py-2.px-4.rounded.w-full
                    {:type "submit"} "Update Habit Log"]]

                  [:div.mt-4.flex.flex-col
                   [:span.text-gray-500 (str "last updated: " latest-tx-time)]
                   [:span.text-gray-500 (str "created at: " formatted-created-at)]])

                 ;; delete form
                 (biff/form
                  {:action (str "/app/habit-logs/" log-id "/delete") :method "post"}
                  [:div.w-full.md:w-96.p-2.my-4
                   [:input.text-center.bg-red-100.hover:bg-red-500.hover:text-white.text-black.font-bold.py-2.px-4.rounded.w-full
                    {:type "submit" :value "Delete"}]])])])))

(defn edit! [{:keys [session params] :as ctx}]
  (let [id-strs        (-> params :habit-refs ensure-vector)
        time-zone      (-> params :time-zone)
        timestamp-str  (-> params :timestamp)
        notes          (-> params :notes)
        local-datetime (LocalDateTime/parse timestamp-str)
        zone-id        (ZoneId/of time-zone)
        zdt            (ZonedDateTime/of local-datetime zone-id)
        timestamp      (-> zdt (t/instant))
        habit-ids      (->> id-strs
                            (map #(some-> % UUID/fromString))
                            set)
        log-id         (-> params :id UUID/fromString)
        user-id        (:uid session)
        user-time-zone (get-user-time-zone ctx)
        new-tz         (not= user-time-zone time-zone)
        ops            (->> [(merge {:db/op               :update
                                     :db/doc-type         :habit-log
                                     ::schema/type        :habit-log
                                     :xt/id               log-id
                                     :habit-log/timestamp timestamp
                                     :habit-log/time-zone time-zone
                                     :habit-log/habit-ids habit-ids}
                                    (when (not (str/blank? notes))
                                      {:habit-log/notes notes}))
                             (when new-tz
                               {:db/op          :update
                                :db/doc-type    :user
                                :xt/id          user-id
                                :user/time-zone time-zone})]
                            (remove nil?))]

    (biff/submit-tx ctx ops)

    {:status  303
     :headers {"location" (str "/app/habit-logs/" log-id "/edit")}}))

(defn view [{:keys [path-params session biff/db] :as ctx}]
  (let [log-id               (-> path-params :id UUID/fromString)
        user-id              (:uid session)
        {:user/keys [email]} (xt/entity db user-id)
        habit-log            (single-for-user-query (merge ctx {:xt/id log-id}))]
    (ui/page
     {}
     (side-bar (pot/map-of email)
               [:div (list-item habit-log)]))))

(defn soft-delete! [{:keys [path-params params]
                     :as   ctx}]
  (let [log-id (-> path-params :id UUID/fromString)
        log    (single-for-user-query (merge ctx {:xt/id log-id}))
        now    (t/now)]
    (if (some? log)
      (do
        (biff/submit-tx ctx
                        [{:db/op              :update
                          :db/doc-type        :habit-log
                          :xt/id              log-id
                          ::schema/deleted-at now}])
        {:status  303
         :headers {"location" "/app/habit-logs"}})
      {:status 403
       :body   "Not authorized to edit that habit log"})))
