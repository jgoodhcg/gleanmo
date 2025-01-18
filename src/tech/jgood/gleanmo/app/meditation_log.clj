(ns tech.jgood.gleanmo.app.meditation-log
  (:require
   [clojure.string :as str]
   [com.biffweb :as biff :refer [q]]
   [potpuri.core :as pot]
   [tech.jgood.gleanmo.app.location :as location]
   [tech.jgood.gleanmo.app.meditation :as meditation]
   [tech.jgood.gleanmo.app.shared :refer [format-date-time-local
                                          get-last-tx-time get-user-time-zone link-button
                                          local-date-time-fmt param-true? side-bar str->instant time-zone-select
                                          zoned-date-time-fmt]]
   [tech.jgood.gleanmo.schema.meta :as sm]
   [tech.jgood.gleanmo.ui :as ui]
   [tick.core :as t]
   [xtdb.api :as xt])
  (:import
   [java.time ZoneId]
   [java.util UUID]))

;; TODO consolidate with schema
(def positions [:sitting :lying :walking :standing :moving])

(defn all-for-user-query [{:keys [biff/db session]}]
  (let [raw-results (q db '{:find  [(pull ?meditation-log [*])
                                    (pull ?type-id [*])
                                    (pull ?location-id [*])
                                    ?tz]
                            :where [[?meditation-log :user/id user-id]
                                    [?meditation-log ::sm/type :meditation-log]
                                    [?meditation-log :meditation-log/type-id ?type-id]
                                    [?meditation-log :meditation-log/location-id ?location-id]
                                    [?user :xt/id user-id]
                                    [?user :user/time-zone ?tz]
                                    ;; NOTE not sure if I want to limit these ...
                                    ;; (not [?type-id ::schema/deleted-at])
                                    ;; (not [?location-id ::schema/deleted-at])
                                    (not [?meditation-log ::sm/deleted-at])]
                            :in    [user-id]} (:uid session))]
    (cond->> raw-results
      :always (group-by (fn [[meditation-log _ _]] (:xt/id meditation-log))) ; Group by meditation-log id
      :always (map (fn [[_ tuple-vec]]
                     (let [[meditation-log meditation location tz] (first tuple-vec)]
                       (merge meditation-log
                              {:user/time-zone tz}
                              (select-keys meditation [:meditation/name
                                                            :meditation/notes])
                              (select-keys location   [:location/name
                                                       :location/notes])))))
      :always (into [])
      :always (sort-by :meditation-log/beginning)
      :always (reverse))))

(defn single-for-user-query [{:keys [biff/db session xt/id]}]
  (first
   (q db '{:find  (pull ?meditation-log [*])
           :where [[?meditation-log :xt/id meditation-log-id]
                   [?meditation-log ::sm/type :meditation-log]
                   [?meditation-log :user/id user-id]
                   (not [?meditation-log ::sm/deleted-at])]
           :in    [user-id meditation-log-id]} (:uid session) id)))

(defn list-item
  [{:meditation-log/keys [beginning
                          end
                          position
                          guided
                          interrupted]
    id                   :xt/id
    location             :location/name
    meditation      :meditation/name
    user-time-zone       :user/time-zone}]
  (let [formatted-beginning (when beginning
                              (-> beginning
                                  (t/instant)
                                  (t/in (t/zone user-time-zone))
                                  (->> (t/format (t/formatter zoned-date-time-fmt)))))
        formatted-end       (when end
                              (-> end
                                  (t/instant)
                                  (t/in (t/zone user-time-zone))
                                  (->> (t/format (t/formatter zoned-date-time-fmt)))))
        duration            (when (and beginning end)
                              (-> (t/duration {:tick/beginning beginning
                                               :tick/end       end})
                                  t/minutes))]
    [:a {:href (str "/app/meditation-logs/" id "/edit")}
     [:div.hover:bg-gray-100.transition.duration-150.p-4.border-b.border-gray-200.cursor-pointer.w-full.md:w-96
      {:id (str "meditation-log-list-item-" id)}
      [:div.flex.justify-between.flex-col
       [:span formatted-beginning]
       [:span formatted-end]
       (when (some? duration)
         [:span (str duration " minutes")])]
      [:div.mt-2
       [:p.text-sm.font-bold (str "Location: " location)]
       [:p.text-sm.font-bold (str "Type: " meditation)]
       [:p.text-sm (str "Position: " (name position))]
       [:p.text-sm (str "Guided: " (if guided "Yes" "No"))]
       [:p.text-sm (str "Interrupted: " (if interrupted "Yes" "No"))]]]]))

(defn new-form [{:keys [session biff/db]
                 :as   ctx}]
  (let [user-id             (:uid session)
        {:user/keys [email]} (xt/entity db user-id)
        locations            (location/all-for-user-query ctx)
        meditations     (meditation/all-for-user-query ctx)
        time-zone            (get-user-time-zone ctx)
        time-zone            (if (some? time-zone) time-zone "US/Eastern")
        current-time         (format-date-time-local (t/now) time-zone)
        recent-logs          (->> (all-for-user-query ctx)
                                  (take 3))]
    (ui/page
     {}
     [:div
      (side-bar (pot/map-of email)
                [:div.w-full.md:w-96.space-y-8
                 (biff/form
                  {:hx-post   "/app/meditation-logs"
                   :hx-swap   "outerHTML"
                   :hx-select "#log-meditation-form"
                   :id        "log-meditation-form"}

                  [:div
                   [:h2.text-base.font-semibold.leading-7.text-gray-900 "Log Meditation"]]

                  [:div.grid.grid-cols-1.gap-y-6
                   ;; Time Zone selection
                   (time-zone-select time-zone)

                   ;; Location selection
                   [:div
                    [:label.block.text-sm.font-medium.leading-6.text-gray-900 {:for "location-id"} "Location"]
                    [:div.mt-2
                     [:select.rounded-md.shadow-sm.block.w-full.border-0.py-1.5.text-gray-900.focus:ring-2.focus:ring-blue-600
                      {:name "location-id" :required true :autocomplete "off"}
                      (map (fn [location]
                             [:option {:value (:xt/id location)}
                              (:location/name location)])
                           locations)]]]

                   ;; Meditation Type selection
                   [:div
                    [:label.block.text-sm.font-medium.leading-6.text-gray-900 {:for "type-id"} "Meditation Type"]
                    [:div.mt-2
                     [:select.rounded-md.shadow-sm.block.w-full.border-0.py-1.5.text-gray-900.focus:ring-2.focus:ring-blue-600
                      {:name "type-id" :required true :autocomplete "off"}
                      (map (fn [meditation]
                             [:option {:value (:xt/id meditation)}
                              (:meditation/name meditation)])
                           meditations)]]]

                   ;; Beginning time input
                   [:div
                    [:label.block.text-sm.font-medium.leading-6.text-gray-900 {:for "beginning"} "Beginning Time"]
                    [:div.mt-2
                     [:input.rounded-md.shadow-sm.block.w-full.border-0.py-1.5.text-gray-900.focus:ring-2.focus:ring-blue-600
                      {:type "datetime-local" :name "beginning" :required true :value current-time}]]]

                   ;; End time input
                   [:div
                    [:label.block.text-sm.font-medium.leading-6.text-gray-900 {:for "end"} "End Time"]
                    [:div.mt-2
                     [:input.rounded-md.shadow-sm.block.w-full.border-0.py-1.5.text-gray-900.focus:ring-2.focus:ring-blue-600
                      {:type "datetime-local" :name "end" :required false :value nil}]]]

                   ;; Position selection
                   [:div
                    [:label.block.text-sm.font-medium.leading-6.text-gray-900 {:for "position"} "Position"]
                    [:div.mt-2
                     [:select.rounded-md.shadow-sm.block.w-full.border-0.py-1.5.text-gray-900.focus:ring-2.focus:ring-blue-600
                      {:name "position" :required true :autocomplete "off"}
                      (map (fn [position]
                             [:option {:value (name position)}
                              (str/capitalize (name position))])
                           positions)]]]

                   ;; Guided input
                   [:div
                    [:label.block.text-sm.font-medium.leading-6.text-gray-900 {:for "guided"} "Guided"]
                    [:div.mt-2
                     [:select.rounded-md.shadow-sm.block.w-full.border-0.py-1.5.text-gray-900.focus:ring-2.focus:ring-blue-600
                      {:name "guided" :required true :autocomplete "off"}
                      (map (fn [option]
                             [:option {:value    (str option)
                                       :selected (= option false)}
                              (case option
                                true  "Yes"
                                false "No")])
                           [true false])]]]

                   ;; Interrupted input
                   [:div
                    [:label.block.text-sm.font-medium.leading-6.text-gray-900 {:for "interrupted"} "Interrupted"]
                    [:div.mt-2
                     [:select.rounded-md.shadow-sm.block.w-full.border-0.py-1.5.text-gray-900.focus:ring-2.focus:ring-blue-600
                      {:name "interrupted" :required true :autocomplete "off"}
                      (map (fn [option]
                             [:option {:value    (str option)
                                       :selected (= option false)}
                              (case option
                                true  "Yes"
                                false "No")])
                           [true false])]]]

                   ;; Submit button
                   [:div.mt-2.w-full
                    [:button.bg-blue-500.hover:bg-blue-700.text-white.font-bold.py-2.px-4.rounded.w-full
                     {:type "submit"} "Log Meditation"]]

                   ;; Recent logs
                   [:div.mt-4
                    [:h2.text-base.font-semibold.leading-7.text-gray-900 "Recent logs"]
                    (->> recent-logs
                         (map (fn [z] (list-item z))))]])])])))

(defn create! [{:keys [session params] :as ctx}]
  (let [location-id    (-> params :location-id UUID/fromString)
        type-id        (-> params :type-id UUID/fromString)
        position       (-> params :position keyword)
        guided         (-> params :guided param-true?)
        interrupted    (-> params :interrupted param-true?)
        time-zone      (-> params :time-zone)
        zone-id        (ZoneId/of time-zone)
        beginning      (-> params :beginning (str->instant zone-id))
        end            (-> params :end (str->instant zone-id))
        user-id        (:uid session)
        now            (t/now)
        user-time-zone (get-user-time-zone ctx)
        new-tz         (not= user-time-zone time-zone)]

    (biff/submit-tx ctx
                    (vec (remove nil?
                                 [(merge
                                   {:db/doc-type                :meditation-log
                                    ::sm/type               :meditation-log
                                    :user/id                    user-id
                                    :meditation-log/location-id location-id
                                    :meditation-log/time-zone   time-zone
                                    :meditation-log/beginning   beginning
                                    :meditation-log/position    position
                                    :meditation-log/guided      guided
                                    :meditation-log/type-id     type-id
                                    :meditation-log/interrupted interrupted
                                    ::sm/created-at         now}
                                   (when (some? end)
                                     {:meditation-log/end end}))
                                  (when new-tz
                                    {:db/op :update
                                     :db/doc-type :user
                                     :xt/id user-id
                                     :user/time-zone time-zone})])))

    {:status  303
     :headers {"location" "/app/new/meditation-log"}}))

(defn list-page
  "Accepts GET and POST. POST is for search form as body."
  [{:keys [session biff/db params]
    :as   ctx}]
  (let [user-id             (:uid session)
        {:user/keys
         [email]} (xt/entity db user-id)
        meditation-logs           (all-for-user-query ctx)
        edit-id             (some-> params :edit (UUID/fromString))]
    (ui/page
     {}
     [:div
      (side-bar (pot/map-of email)
                [:div.my-4
                 (link-button {:href  "/app/new/meditation-log"
                               :label "Create Meditation-Log"})]
                [:div {:id "meditation-logs-list"}
                 (->> meditation-logs
                      (map (fn [z] (list-item (-> z (assoc :edit-id edit-id))))))])])))

(defn edit! [{:keys [session params] :as ctx}]
  (let [location-id    (-> params :location-id UUID/fromString)
        type-id        (-> params :type-id UUID/fromString)
        time-zone      (-> params :time-zone)
        zone-id        (ZoneId/of time-zone)
        beginning      (-> params :beginning (str->instant zone-id))
        end            (-> params :end (str->instant zone-id))
        position       (-> params :position keyword)
        guided         (-> params :guided param-true?)
        interrupted    (-> params :interrupted param-true?)
        log-id         (-> params :id UUID/fromString)
        notes          (-> params :notes)
        user-id        (:uid session)
        user-time-zone (get-user-time-zone ctx)
        new-tz         (not= user-time-zone time-zone)
        ops            (->> [(merge {:db/op                      :update
                                     :db/doc-type                :meditation-log
                                     ::sm/type               :meditation-log
                                     :xt/id                      log-id
                                     :meditation-log/location-id location-id
                                     :meditation-log/beginning   beginning
                                     :meditation-log/position    position
                                     :meditation-log/guided      guided
                                     :meditation-log/type-id     type-id
                                     :meditation-log/interrupted interrupted
                                     :meditation-log/time-zone   time-zone}
                                    (when (some? end)
                                      {:meditation-log/end end})
                                    (when (not (str/blank? notes))
                                      {:meditation-log/notes notes}))
                             (when new-tz
                               {:db/op          :update
                                :db/doc-type    :user
                                :xt/id          user-id
                                :user/time-zone time-zone})]
                            (remove nil?))]

    (biff/submit-tx ctx ops)

    {:status  303
     :headers {"location" (str "/app/meditation-logs/" log-id "/edit")}}))

(defn edit-form [{:keys [path-params
                         session
                         biff/db]
                  :as   ctx}]
  (let [log-id               (-> path-params :id UUID/fromString)
        user-id              (:uid session)
        {:user/keys [email]} (xt/entity db user-id)
        meditation-log       (single-for-user-query (merge ctx {:xt/id log-id}))
        locations            (location/all-for-user-query ctx)
        meditations     (meditation/all-for-user-query ctx)
        time-zone            (or (get-in meditation-log [:meditation-log/time-zone])
                                 (get-user-time-zone ctx))
        formatted-beginning  (-> (get-in meditation-log [:meditation-log/beginning])
                                 (t/in (t/zone time-zone))
                                 (->> (t/format (t/formatter local-date-time-fmt))))
        end-val              (get-in meditation-log [:meditation-log/end])
        formatted-end        (if (some? end-val)
                               (-> end-val
                                   (t/in (t/zone time-zone))
                                   (->> (t/format (t/formatter local-date-time-fmt))))
                               "")
        latest-tx-time       (-> (get-last-tx-time (merge ctx {:xt/id log-id}))
                                 (t/in (t/zone time-zone))
                                 (->> (t/format (t/formatter zoned-date-time-fmt))))
        formatted-created-at (-> meditation-log
                                 ::sm/created-at
                                 (t/in (t/zone time-zone))
                                 (->> (t/format (t/formatter zoned-date-time-fmt))))]
    (ui/page
     {}
     [:div
      (side-bar (pot/map-of email)
                [:div.w-full.md:w-96.space-y-8
                 (biff/form
                  {:hx-post   (str "/app/meditation-logs/" log-id)
                   :hx-swap   "outerHTML"
                   :hx-select "#edit-meditation-log-form"
                   :id        "edit-meditation-log-form"}

                  [:input {:type "hidden" :name "id" :value log-id}]

                  [:div
                   [:h2.text-base.font-semibold.leading-7.text-gray-900 "Edit Meditation Log"]
                   [:p.mt-1.text-sm.leading-6.text-gray-600 "Edit your meditation log entry."]]

                  ;; Location selection
                  [:div
                   [:label.block.text-sm.font-medium.leading-6.text-gray-900 {:for "location-id"} "Location"]
                   [:div.mt-2
                    [:select.rounded-md.shadow-sm.block.w-full.border-0.py-1.5.text-gray-900.focus:ring-2.focus:ring-blue-600
                     {:name "location-id" :required true :autocomplete "off"}
                     (map (fn [location]
                            [:option {:value    (:xt/id location)
                                      :selected (= (:xt/id location) (get-in meditation-log [:meditation-log/location-id]))}
                             (:location/name location)])
                          locations)]]]

                  ;; Meditation selection
                  [:div
                   [:label.block.text-sm.font-medium.leading-6.text-gray-900 {:for "type-id"} "Meditation Type"]
                   [:div.mt-2
                    [:select.rounded-md.shadow-sm.block.w-full.border-0.py-1.5.text-gray-900.focus:ring-2.focus:ring-blue-600
                     {:name "type-id" :required true :autocomplete "off"}
                     (map (fn [meditation]
                            [:option {:value    (:xt/id meditation)
                                      :selected (= (:xt/id meditation)
                                                   (get-in meditation-log [:meditation-log/type-id]))}
                             (:meditation/name meditation)])
                          meditations)]]]

                  ;; Time zone selection
                  (time-zone-select time-zone)

                  ;; Beginning time input
                  [:div
                   [:label.block.text-sm.font-medium.leading-6.text-gray-900 {:for "beginning"} "Beginning Time"]
                   [:div.mt-2
                    [:input.rounded-md.shadow-sm.block.w-full.border-0.py-1.5.text-gray-900.focus:ring-2.focus:ring-blue-600
                     {:type "datetime-local" :name "beginning" :required true :value formatted-beginning}]]]

                  ;; End time input
                  [:div
                   [:label.block.text-sm.font-medium.leading-6.text-gray-900 {:for "end"} "End Time"]
                   [:div.mt-2
                    [:input.rounded-md.shadow-sm.block.w-full.border-0.py-1.5.text-gray-900.focus:ring-2.focus:ring-blue-600
                     {:type "datetime-local" :name "end" :required false :value formatted-end}]]]

                  ;; Position selection
                  [:div
                   [:label.block.text-sm.font-medium.leading-6.text-gray-900 {:for "position"} "Position"]
                   [:div.mt-2
                    [:select.rounded-md.shadow-sm.block.w-full.border-0.py-1.5.text-gray-900.focus:ring-2.focus:ring-blue-600
                     {:name "position" :required true :autocomplete "off"}
                     (map (fn [position]
                            [:option {:value    (name position)
                                      :selected (= position (get-in meditation-log [:meditation-log/position]))}
                             (str/capitalize (name position))])
                          positions)]]]

                  ;; Guided input
                  [:div
                   [:label.block.text-sm.font-medium.leading-6.text-gray-900 {:for "guided"} "Guided"]
                   [:div.mt-2
                    [:select.rounded-md.shadow-sm.block.w-full.border-0.py-1.5.text-gray-900.focus:ring-2.focus:ring-blue-600
                     {:name "guided" :required true :autocomplete "off"}
                     (map (fn [option]
                            [:option {:value    (str option)
                                      :selected (= option (get-in meditation-log [:meditation-log/guided]))}
                             (case option
                               true  "Yes"
                               false "No")])
                          [true false])]]]

                  ;; Interrupted input
                  [:div
                   [:label.block.text-sm.font-medium.leading-6.text-gray-900 {:for "interrupted"} "Interrupted"]
                   [:div.mt-2
                    [:select.rounded-md.shadow-sm.block.w-full.border-0.py-1.5.text-gray-900.focus:ring-2.focus:ring-blue-600
                     {:name "interrupted" :required true :autocomplete "off"}
                     (map (fn [option]
                            [:option {:value    (str option)
                                      :selected (= option (get-in meditation-log [:meditation-log/interrupted]))}
                             (case option
                               true  "Yes"
                               false "No")])
                          [true false])]]]

                  ;; Submit button
                  [:div.mt-2.w-full
                   [:button.bg-blue-500.hover:bg-blue-700.text-white.font-bold.py-2.px-4.rounded.w-full
                    {:type "submit"} "Update Meditation Log"]]

                  [:div.mt-4.flex.flex-col
                   [:span.text-gray-500 (str "last updated: " latest-tx-time)]
                   [:span.text-gray-500 (str "created at: " formatted-created-at)]])

                 ;; delete form
                 (biff/form
                  {:action (str "/app/meditation-logs/" log-id "/delete") :method "post"}
                  [:div.w-full.md:w-96.p-2.my-4
                   [:input.text-center.bg-red-100.hover:bg-red-500.hover:text-white.text-black.font-bold.py-2.px-4.rounded.w-full
                    {:type "submit" :value "Delete"}]])])])))

(defn view [{:keys [path-params
                    session
                    biff/db]
             :as   ctx}]
  (let [meditation-log-id   (-> path-params :id UUID/fromString)
        user-id             (:uid session)
        {email :user/email} (xt/entity db user-id)
        meditation-log      (single-for-user-query (merge ctx {:xt/id meditation-log-id}))]
    (ui/page
     {}
     [:div
      (side-bar (pot/map-of email)
                (list-item meditation-log))])))

(defn soft-delete! [{:keys [path-params params]
                     :as   ctx}]
  (let [meditation-log-id (-> path-params :id UUID/fromString)
        meditation-log    (single-for-user-query (merge ctx {:xt/id meditation-log-id}))
        now         (t/now)]
    (if (some? meditation-log)
      (do
        (biff/submit-tx ctx
                        [{:db/op              :update
                          :db/doc-type        :meditation-log
                          :xt/id              meditation-log-id
                          ::sm/deleted-at now}])
        {:status  303
         :headers {"location" "/app/meditation-logs"}})
      {:status 403
       :body   "Not authorized to edit that meditation-log"})))
