(ns tech.jgood.gleanmo.app.user
  (:require
   [com.biffweb :as biff]
   [potpuri.core :as pot]
   [tech.jgood.gleanmo.app.shared :refer [get-last-tx-time
                                          side-bar
                                          time-zone-select
                                          zoned-date-time-fmt]]
   [tech.jgood.gleanmo.db.queries :as db]
   [tech.jgood.gleanmo.ui :as ui]
   [tick.core :as t]))

(defn view
  [{:keys [biff/db authorized.user/id]}]
  (let [{:user/keys [email time-zone]} (db/get-entity-by-id db id)]
    (ui/page
     {}
     [:div
      (side-bar (pot/map-of email)
                [:h2.form-header "User Details"]
                [:p.text-secondary (str "Email: " email)]
                [:p.text-secondary (str "Time Zone: " time-zone)]
                [:a.link {:href (str "/app/users/" id "/edit")} "edit"])])))

(defn create!
  [_]
  {:status  501,
   :headers {"content-type" "text/plain"},
   :body    "Not Implemented"})

(defn edit!
  [{:keys [params authorized.user/id], :as ctx}]
  (let [email     (:email params)
        time-zone (:time-zone params)]
    (biff/submit-tx ctx
                    [{:db/op          :update,
                      :db/doc-type    :user,
                      :xt/id          id,
                      :user/email     email,
                      :user/time-zone time-zone}])
    {:status  303,
     :headers {"location" (str "/app/users/" id "/edit")}}))

(defn edit-form
  [{:keys [biff/db authorized.user/id], :as ctx}]
  (let [{:user/keys [email time-zone]}
        (db/get-entity-by-id db id)
        time-zone      (or time-zone (t/zone "UTC"))
        latest-tx-time (-> (get-last-tx-time (merge ctx {:xt/id id}))
                           (t/in (t/zone time-zone))
                           (->> (t/format (t/formatter zoned-date-time-fmt))))]
    [:div {:id "user-edit-form-page"}
     (ui/page
      {}
      [:div
       (side-bar
        (pot/map-of email)
        (biff/form
         {:hx-post   (str "/app/users/" id),
          :hx-swap   "outerHTML",
          :hx-target "#user-edit-form-page", ;; Need to udpate nav bar
                                                ;; as
             ;; well for email changes
          :hx-select "#user-edit-form-page",
          :id        "user-edit-form"}

         [:div.w-full.md:w-96.p-2

          [:div.mt-4
           [:label.form-label
            {:for "email"} "Email"]
           [:div.mt-2
            [:input.form-input
             {:type "text", :name "email", :value email}]]]

          [:div.my-4
           (time-zone-select time-zone)]

          [:div.mt-8.w-full
           [:button.form-button-primary.w-full
            {:type "submit"} "Update User"]]

          [:div.mt-4
           [:span.text-secondary
            (str "last updated: " latest-tx-time)]]]))])]))

(defn my-user
  [{:keys [session]}]
  (let [id (:uid session)]
    {:status  303,
     :headers {"location" (str "/app/users/" id)}}))
