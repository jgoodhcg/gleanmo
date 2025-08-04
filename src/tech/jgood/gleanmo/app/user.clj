(ns tech.jgood.gleanmo.app.user
  (:require
   [com.biffweb :as    biff]
   [tech.jgood.gleanmo.app.shared :refer [param-true? side-bar
                                          time-zone-select zoned-date-time-fmt]]
   [tech.jgood.gleanmo.db.mutations :as mutations]
   [tech.jgood.gleanmo.db.queries :as queries]
   [tech.jgood.gleanmo.ui :as ui]
   [tick.core :as t]))

(defn turn-off-sensitive!
  "Turn off sensitive item display by updating user to false"
  [{:keys [authorized.user/id], :as ctx}]
  (mutations/update-user! ctx id {:user/show-sensitive false})
  {:status  303,
   :headers {"location" (or (get-in ctx [:params :redirect]) "/app")}})

(defn turn-off-archived!
  "Turn off archived item display by updating user to false"
  [{:keys [authorized.user/id], :as ctx}]
  (mutations/update-user! ctx id {:user/show-archived false})
  {:status  303,
   :headers {"location" (or (get-in ctx [:params :redirect]) "/app")}})


(defn view
  [{:keys [biff/db authorized.user/id], :as ctx}]
  (let [{:user/keys [email time-zone]} (queries/get-entity-by-id db id)]
    (ui/page
      {}
      [:div
       (side-bar ctx
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
  (let [email          (:email params)
        time-zone      (:time-zone params)
        show-sensitive (param-true? (:show-sensitive params))
        show-archived  (param-true? (:show-archived params))]
    ;; Update user info with all fields in one transaction
    (mutations/update-user! ctx
                            id
                            {:user/email          email
                             :user/time-zone      time-zone
                             :user/show-sensitive show-sensitive
                             :user/show-archived  show-archived})
    {:status  303,
     :headers {"location" (str "/app/users/" id "/edit")}}))

(defn edit-form
  [{:keys [biff/db authorized.user/id], :as ctx}]
  (let [{:user/keys [email time-zone show-sensitive show-archived]}
          (queries/get-entity-by-id db id)
        time-zone      (or time-zone (t/zone "UTC"))
        show-sensitive (boolean show-sensitive)
        show-archived  (boolean show-archived)
        latest-tx-time (-> (queries/get-last-tx-time (merge ctx {:xt/id id}))
                           (t/in (t/zone time-zone))
                           (->> (t/format (t/formatter zoned-date-time-fmt))))]
    [:div {:id "user-edit-form-page"}
     (ui/page
       {}
       [:div
        (side-bar
          ctx
          (biff/form
            {:hx-post   (str "/app/users/" id),
             :hx-swap   "outerHTML",
             :hx-target "#user-edit-form-page", ;; Need to udpate nav bar
             ;; as well for email changes
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

             [:div.my-4
              [:h3.form-label.mb-2 "Privacy Settings"]
              [:div.flex.items-center.space-x-2.mb-2
               [:input.form-checkbox
                {:type    "checkbox",
                 :name    "show-sensitive",
                 :id      "show-sensitive",
                 :checked show-sensitive}]
               [:label.text-sm.text-secondary {:for "show-sensitive"}
                "Show sensitive items (habits, locations, etc.)"]]
              [:div.flex.items-center.space-x-2
               [:input.form-checkbox
                {:type    "checkbox",
                 :name    "show-archived",
                 :id      "show-archived",
                 :checked show-archived}]
               [:label.text-sm.text-secondary {:for "show-archived"}
                "Show archived items (habits, locations, etc.)"]]]

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
