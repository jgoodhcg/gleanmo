(ns tech.jgood.gleanmo.app.user
  (:require
   [com.biffweb :as biff]
   [potpuri.core :as pot]
   [tech.jgood.gleanmo.app.shared :refer [get-last-tx-time nav-bar
                                          time-zone-select zoned-date-time-fmt]]
   [tech.jgood.gleanmo.ui :as ui]
   [tick.core :as t]
   [xtdb.api :as xt]))

(defn edit-page [{:keys [session biff/db] :as ctx}]
  (let [id (:uid session)
        {:user/keys [email time-zone]
         :as        user} (xt/entity db id)
        latest-tx-time    (-> (get-last-tx-time (merge ctx {:xt/id id}))
                              (t/in (t/zone time-zone))
                              (->> (t/format (t/formatter zoned-date-time-fmt))))]

    [:div {:id "user-edit-page"}
     (ui/page
      {}
      (nav-bar (pot/map-of email))
      (biff/form
       {:hx-post   "/app/user/edit"
        :hx-swap   "outerHTML"
        :hx-target "#user-edit-page" ;; Need to udpate nav bar as well for email changes
        :hx-select "#user-edit-page"
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

(defn edit! [{:keys [session params] :as ctx}]
  (let [id        (:uid session)
        email     (:email params)
        time-zone (:time-zone params)]
    (biff/submit-tx ctx
                    [{:db/op          :update
                      :db/doc-type    :user
                      :xt/id          id
                      :user/email     email
                      :user/time-zone time-zone}])

    {:status  303
     :headers {"location" (str "/app/user/edit")}}))
