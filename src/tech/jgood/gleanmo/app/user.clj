(ns tech.jgood.gleanmo.app.user
  (:require
   [clojure.string :as str]
   [com.biffweb :as    biff]
   [tech.jgood.gleanmo.app.layout :as layout]
   [tech.jgood.gleanmo.app.shared :refer [local-path param-true?
                                          time-zone-select zoned-date-time-fmt]]
   [tech.jgood.gleanmo.db.mutations :as mutations]
   [tech.jgood.gleanmo.db.queries :as queries]
   [tech.jgood.gleanmo.ui :as ui]
   [tick.core :as t]
   [xtdb.api :as xt])
  (:import
   [java.security MessageDigest SecureRandom]))

(defn turn-off-sensitive!
  "Turn off sensitive item display by updating user to false"
  [{:keys [authorized.user/id], :as ctx}]
  (mutations/update-user! ctx id {:user/show-sensitive false})
  {:status  303,
   :headers {"location" (local-path (get-in ctx [:params :redirect]) "/app")}})

(defn turn-off-archived!
  "Turn off archived item display by updating user to false"
  [{:keys [authorized.user/id], :as ctx}]
  (mutations/update-user! ctx id {:user/show-archived false})
  {:status  303,
   :headers {"location" (local-path (get-in ctx [:params :redirect]) "/app")}})

(defn turn-off-bm-logs!
  "Turn off BM log display in the overview/sidebar/dashboards by updating user to false"
  [{:keys [authorized.user/id], :as ctx}]
  (mutations/update-user! ctx id {:user/show-bm-logs false})
  {:status  303,
   :headers {"location" (local-path (get-in ctx [:params :redirect]) "/app")}})

(defn view
  [{:keys [biff/db authorized.user/id], :as ctx}]
  (let [{:user/keys [email time-zone]} (queries/get-entity-by-id db id)]
    (ui/page
     {}
     [:div
      (layout/page-shell
       ctx {:width :narrow}
       (layout/page-header {:title "Account"})
       [:p.text-secondary (str "Email: " email)]
       [:p.text-secondary (str "Time Zone: " time-zone)]
       [:a.link {:href (str "/app/users/" id "/edit")} "edit"]
       [:p.mt-4 [:a.link {:href "/app/api-tokens"} "API tokens"]]
       [:p.mt-2 [:a.link {:href "/app/cronometer"} "Cronometer"]])])))

(defn create!
  [_]
  {:status  501,
   :headers {"content-type" "text/plain"},
   :body    "Not Implemented"})

;; ---------------------------------------------------------------------------
;; Email change verification
;;
;; Sign-in finds a user by `user/email`, so an address only becomes the
;; account's email after a code sent to that address comes back. Until then
;; it sits in `user/pending-email` with a hashed code, the time it was sent,
;; and a count of failed attempts — the same limits as Biff's sign-in codes,
;; with a longer expiry because the user is switching to another inbox.
;; ---------------------------------------------------------------------------

(def email-change-code-minutes 15)
(def email-change-max-attempts 3)

(def ^:private pending-email-keys
  [:user/pending-email
   :user/pending-email-code-hash
   :user/pending-email-timestamp
   :user/pending-email-failed-attempts])

(def ^:private clear-pending-email
  (zipmap pending-email-keys (repeat :db/dissoc)))

(defn- new-email-change-code
  []
  (format "%06d" (.nextInt (SecureRandom.) 1000000)))

(defn- code-matches?
  [code code-hash]
  (boolean
   (and (string? code)
        (string? code-hash)
        (MessageDigest/isEqual (.getBytes (biff/sha256 (str/trim code)) "UTF-8")
                               (.getBytes ^String code-hash "UTF-8")))))

(defn- email-valid?
  "Same shape check Biff's sign-in applies."
  [email]
  (boolean (and email
                (re-matches #".+@.+\..+" email)
                (not (re-find #"\s" email)))))

(defn- email-taken?
  [db user-id email]
  (boolean (seq (disj (queries/user-ids-by-email-ignoring-case db email)
                      user-id))))

(defn- edit-location
  ([id] (str "/app/users/" id "/edit"))
  ([id email-status] (str (edit-location id) "?email-status=" email-status)))

(defn- request-email-change!
  "Send a code to `new-email` and record it as pending alongside
   `other-fields`. Returns the email-status for the edit page."
  [{:keys [biff/db biff/send-email], :as ctx} id new-email other-fields]
  (let [code (new-email-change-code)]
    (cond
      (not (email-valid? new-email))
      (do (mutations/update-user! ctx id other-fields) "invalid")

      (email-taken? db id new-email)
      (do (mutations/update-user! ctx id other-fields) "taken")

      (not (send-email ctx
                       {:template :email-change-code,
                        :to       new-email,
                        :code     code}))
      (do (mutations/update-user! ctx id other-fields) "send-failed")

      :else
      (do (mutations/update-user!
           ctx
           id
           (merge other-fields
                  {:user/pending-email                 new-email,
                   :user/pending-email-code-hash       (biff/sha256 code),
                   :user/pending-email-timestamp       (t/now),
                   :user/pending-email-failed-attempts 0}))
          "code-sent"))))

(defn edit!
  "Save the account form. Settings save immediately; a changed email is
   only recorded as pending until the code sent to it is verified."
  [{:keys [biff/db params authorized.user/id], :as ctx}]
  (let [current-email (:user/email (queries/get-entity-by-id db id))
        new-email     (biff/normalize-email (:email params))
        other-fields  {:user/time-zone      (:time-zone params),
                       :user/show-sensitive (param-true? (:show-sensitive params)),
                       :user/show-archived  (param-true? (:show-archived params)),
                       :user/show-bm-logs   (param-true? (:show-bm-logs params))}
        changed?      (and (some? new-email)
                           (not= new-email (biff/normalize-email current-email)))]
    (if changed?
      {:status  303,
       :headers {"location"
                 (edit-location
                  id
                  (request-email-change! ctx id new-email other-fields))}}
      (do (mutations/update-user! ctx id other-fields)
          {:status  303,
           :headers {"location" (edit-location id)}}))))

(defn verify-email!
  "Apply the pending email change when the posted code matches."
  [{:keys [biff/db params authorized.user/id], :as ctx}]
  (let [{:user/keys [pending-email pending-email-code-hash
                     pending-email-timestamp pending-email-failed-attempts]}
        (queries/get-entity-by-id db id)
        attempts (or pending-email-failed-attempts 0)
        expired? (or (nil? pending-email-timestamp)
                     (t/> (t/now)
                          (t/>> pending-email-timestamp
                                (t/new-duration email-change-code-minutes
                                                :minutes))))
        status
        (cond
          (nil? pending-email)
          "no-pending"

          expired?
          (do (mutations/update-user! ctx id clear-pending-email) "expired")

          (>= attempts email-change-max-attempts)
          (do (mutations/update-user! ctx id clear-pending-email)
              "too-many-attempts")

          (not (code-matches? (:code params) pending-email-code-hash))
          (if (>= (inc attempts) email-change-max-attempts)
            (do (mutations/update-user! ctx id clear-pending-email)
                "too-many-attempts")
            (do (mutations/update-user!
                 ctx
                 id
                 {:user/pending-email-failed-attempts [:db/add 1]})
                "wrong-code"))

          :else
          ;; Checked again here, against the db as it is now, and once more
          ;; by the unique constraint when the write commits.
          (if (and (not (email-taken? (xt/db (:biff.xtdb/node ctx))
                                      id
                                      pending-email))
                   (mutations/set-user-email! ctx id pending-email
                                              clear-pending-email))
            "changed"
            (do (mutations/update-user! ctx id clear-pending-email)
                "taken")))]
    {:status  303,
     :headers {"location" (edit-location id status)}}))

(defn cancel-email-change!
  "Drop a pending email change."
  [{:keys [authorized.user/id], :as ctx}]
  (mutations/update-user! ctx id clear-pending-email)
  {:status  303,
   :headers {"location" (edit-location id)}})

(def ^:private email-status-messages
  {"code-sent"         [:info "We sent a code to the new address. Enter it below to finish changing your email."]
   "changed"           [:info "Your email has been changed."]
   "invalid"           [:error "That doesn't look like an email address. Your other settings were saved."]
   "taken"             [:error "That email address is already in use. Your other settings were saved."]
   "send-failed"       [:error "We couldn't send a code to that address. Your other settings were saved."]
   "wrong-code"        [:error "That code is incorrect."]
   "expired"           [:error "That code has expired. Submit the new address again to get another."]
   "too-many-attempts" [:error "Too many incorrect codes. Submit the new address again to get another."]
   "no-pending"        [:error "There is no email change waiting to be verified."]})

(defn- email-status-banner
  [email-status]
  (when-let [[kind message] (get email-status-messages email-status)]
    [:p.my-4.text-sm.rounded-lg.border.border-dark.px-3.py-2
     {:id    "email-status",
      :class (if (= kind :error) "text-red-400" "text-gray-200")}
     message]))

(def ^:private account-page-swap
  {:hx-swap   "outerHTML",
   :hx-target "#user-edit-form-page",
   :hx-select "#user-edit-form-page"})

(defn- pending-email-panel
  [id pending-email]
  [:div.mt-4.p-4.rounded-lg.border.border-dark {:id "pending-email"}
   [:p.text-sm.text-secondary
    "Waiting to confirm " [:span.font-semibold pending-email]
    ". Enter the code we emailed to that address."]
   ;; htmx like the account form below: that form's swap re-inserts the
   ;; page's scripts, which leaves main.js's double-submit guard registered
   ;; twice and cancels the next native submit. htmx forms skip that guard.
   (biff/form
    (merge account-page-swap
           {:hx-post (str "/app/users/" id "/verify-email"),
            :id      "verify-email-form"})
    [:div.mt-2
     [:label.form-label {:for "email-code"} "Code"]
     [:div.mt-2
      [:input.form-input
       {:type         "text",
        :name         "code",
        :id           "email-code",
        :inputmode    "numeric",
        :autocomplete "one-time-code",
        :required     true}]]]
    [:div.mt-4
     [:button.form-button-primary.w-full {:type "submit"} "Verify email"]])
   (biff/form
    (merge account-page-swap
           {:hx-post (str "/app/users/" id "/cancel-email-change"),
            :id      "cancel-email-change-form"})
    [:button.link.mt-2.text-sm {:type "submit"} "Cancel email change"])])

(defn edit-form
  [{:keys [biff/db authorized.user/id params], :as ctx}]
  (let [{:user/keys [email time-zone show-sensitive show-archived show-bm-logs
                     pending-email]
         :as user}
        (queries/get-entity-by-id db id)
        time-zone      (or time-zone (t/zone "UTC"))
        show-sensitive (boolean show-sensitive)
        show-archived  (boolean show-archived)
        show-bm-logs   (cond
                         (contains? user :user/show-bm-logs)
                         (boolean show-bm-logs)
                         (contains? user :user/hide-bm-logs)
                         (not (boolean (:user/hide-bm-logs user)))
                         :else
                         true)
        latest-tx-time (-> (queries/get-last-tx-time (merge ctx {:xt/id id}))
                           (t/in (t/zone time-zone))
                           (->> (t/format (t/formatter zoned-date-time-fmt))))]
    [:div {:id "user-edit-form-page"}
     (ui/page
      {}
      [:div
       (layout/page-shell
        ctx {:width :narrow}
        (layout/page-header {:title "Edit Account"})
        (email-status-banner (:email-status params))
        (when pending-email
          (pending-email-panel id pending-email))
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
             {:type                "text",
              :name                "email",
              :value               email,
              :data-original-value email}]]
           [:p.mt-1.text-xs.text-secondary
            "Changing your email sends a code to the new address."]]

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
             "Show archived items (habits, locations, etc.)"]]
           [:div.flex.items-center.space-x-2
            [:input.form-checkbox
             {:type    "checkbox",
              :name    "show-bm-logs",
              :id      "show-bm-logs",
              :checked show-bm-logs}]
            [:label.text-sm.text-secondary {:for "show-bm-logs"}
             "Show BM logs in overview, sidebar, and dashboards"]]]

          [:div.mt-8.w-full
           [:button.form-button-primary.w-full
            {:type "submit"} "Update User"]]

          [:div.mt-4
           [:a.link {:href "/app/api-tokens"} "Manage API tokens"]]

          [:div.mt-2
           [:a.link {:href "/app/cronometer"} "Cronometer sync"]]

          [:div.mt-4
           [:span.text-secondary
            (str "last updated: " latest-tx-time)]]]))])]))

(defn my-user
  [{:keys [session]}]
  (let [id (:uid session)]
    {:status  303,
     :headers {"location" (str "/app/users/" id)}}))
