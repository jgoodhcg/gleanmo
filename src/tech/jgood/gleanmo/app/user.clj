(ns tech.jgood.gleanmo.app.user
  (:require
   [com.biffweb :as biff]
   [potpuri.core :as pot]
   [tech.jgood.gleanmo.app.shared :refer [get-last-tx-time nav-bar
                                          time-zone-select zoned-date-time-fmt]]
   [tech.jgood.gleanmo.ui :as ui]
   [tick.core :as t]
   [xtdb.api :as xt])
  (:import
   [java.util UUID]))

(defn view [{:keys [biff/db authorized.user/id]}]
  (let [{:user/keys [email time-zone]} (xt/entity db id)]
    (ui/page
     {}
     (nav-bar (pot/map-of email))
     [:div
      [:h2 "User Details"]
      [:p (str "Email: " email)]
      [:p (str "Time Zone: " time-zone)]])))

(defn post! [_]
  {:status 501
   :headers {"content-type" "text/plain"}
   :body "Not Implemented"})

(defn put! [{:keys [params authorized.user/id] :as ctx}]
  (let [email        (:email params)
        time-zone    (:time-zone params)]
    (biff/submit-tx ctx
                    [{:db/op          :update
                      :db/doc-type    :user
                      :xt/id          id
                      :user/email     email
                      :user/time-zone time-zone}])
    {:status  303
     :headers {"location" (str "/app/users/" id "/edit")}}))

(defn patch! [_]
  {:status 501
   :headers {"content-type" "text/plain"}
   :body "Not Implemented"})

(defn delete! [_]
  {:status 501
   :headers {"content-type" "text/plain"}
   :body "Not Implemented"})

(defn edit-form [{:keys [biff/db authorized.user/id] :as ctx}]
  (let [{:user/keys [email time-zone]
         :as        user} (xt/entity db id)
        latest-tx-time    (-> (get-last-tx-time (merge ctx {:xt/id id}))
                              (t/in (t/zone time-zone))
                              (->> (t/format (t/formatter zoned-date-time-fmt))))]
    [:div {:id "user-edit-form-page"}
     (ui/page
      {}
      (nav-bar (pot/map-of email))
      (biff/form
       {:hx-post   "/app/users"
        :hx-swap   "outerHTML"
        :hx-target "#user-edit-form-page" ;; Need to udpate nav bar as well for email changes
        :hx-select "#user-edit-form-page"
        :id        "user-edit-form"}

       [:div.w-full.md:w-96.p-2
            ;; email
        [:div.mt-4
         [:label.block.text-sm.font-medium.leading-6.text-gray-900
          {:for "email"} "Email"]
         [:div.mt-2
          [:input.rounded-md.shadow-sm.block.w-full.border-0.py-1.5.text-gray-900.focus:ring-2.focus:ring-blue-600
           {:type "text" :name "email" :value email}]]]

            ;; time-zone
        [:div.my-4
         (time-zone-select time-zone)]

            ;; Submit button
        [:div.mt-8.w-full
         [:button.bg-blue-500.hover:bg-blue-700.text-white.font-bold.py-2.px-4.rounded.w-full
          {:type "submit"} "Update User"]]

        [:div.mt-4
         [:span.text-gray-500 (str "last updated: " latest-tx-time)]]]))]))

(defn me [{:keys [session]}]
  (let [id (:uid session)]
    {:status 303
     :headers {"location" (str "/app/users/" id)}}))
