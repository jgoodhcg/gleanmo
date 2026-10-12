(ns tech.jgood.gleanmo.app.cronometer
  "The Cronometer status page (roadmap/056-cronometer-integration.md, Views):
   the stored connection, the last sync, recent row counts, the latest day's
   macros, and a Sync now button.

   Every action writes and 303s back here, so the follow-up GET renders from a
   fresh snapshot. Outcomes travel in the `status` query parameter."
  (:require
   [clojure.string :as str]
   [com.biffweb :as biff]
   [tech.jgood.gleanmo.app.layout :as layout]
   [tech.jgood.gleanmo.cronometer.connection :as connection]
   [tech.jgood.gleanmo.cronometer.nutrients :as nutrients]
   [tech.jgood.gleanmo.cronometer.sync :as sync]
   [tech.jgood.gleanmo.cronometer.transform :as transform]
   [tech.jgood.gleanmo.db.queries :as queries]
   [tech.jgood.gleanmo.ui :as ui]
   [tick.core :as t]))

(def ^:private base-path "/app/cronometer")

(def ^:private window-days sync/default-days)

(defn- redirect
  ([status] (redirect status {}))
  ([status params]
   {:status  303
    :headers {"location"
              (str base-path "?"
                   (str/join "&"
                             (for [[k v] (assoc params :status status)]
                               (str (name k) "=" v))))}}))

(defn- fmt-instant
  [inst zone]
  (if inst
    (t/format (t/formatter "yyyy-MM-dd HH:mm z") (t/in inst (t/zone zone)))
    "never"))

(defn- fmt-amount
  [n]
  (let [d (double n)]
    (if (>= (Math/abs d) 10)
      (format "%.0f" d)
      (format "%.1f" d))))

;; =============================================================================
;; Sections
;; =============================================================================

(def ^:private status-messages
  {"connected"       "Connection saved. Run Sync now to check it."
   "disconnected"    "Disconnected. The stored credentials were erased."
   "connect-invalid" "Enter both the Cronometer email and password."
   "not-configured"  "The credentials key is not configured."
   "not-connected"   "Connect an account before syncing."
   "sync-running"    "A sync is already running. Try again in a minute."
   "sync-failed"     "The sync stopped with an error. Days before it were saved; details are in the server log."})

(defn- flash
  [{:keys [status created updated deleted days]}]
  (let [message (if (= "synced" status)
                  (str "Synced " days " days: " created " created, " updated
                       " updated, " deleted " removed.")
                  (status-messages status))]
    (when message
      [:p.text-sm.rounded-lg.border.border-dark.px-3.py-2
       {:class       (if (= "sync-failed" status) "text-red-400" "text-gray-200")
        :data-testid "cronometer-flash"}
       message])))

(defn- unconfigured-notice
  []
  (layout/card
   {:data-testid "cronometer-unconfigured"}
   [:p.text-sm.text-gray-300
    "Syncing is off: no credentials key is configured, so Cronometer credentials cannot be stored."]
   [:p.text-xs.text-gray-500.mt-2
    "Set " [:code "CREDENTIALS_KEY"] " to a base64 32-byte key ("
    [:code "openssl rand -base64 32"] ") and restart the server."]))

(defn- connect-form
  [connected?]
  (biff/form
   {:action (str base-path "/connect")
    :method "post"
    :id     "cronometer-connect-form"}
   [:div.space-y-4
    [:div
     [:label.form-label {:for "cronometer-email"} "Cronometer email"]
     [:div.mt-2
      [:input.form-input
       {:type                "email"
        :name                "email"
        :id                  "cronometer-email"
        :required            true
        :autocomplete        "off"
        :data-original-value ""}]]]
    [:div
     [:label.form-label {:for "cronometer-password"} "Cronometer password"]
     [:div.mt-2
      ;; Never re-rendered with a stored value.
      [:input.form-input
       {:type                "password"
        :name                "password"
        :id                  "cronometer-password"
        :required            true
        :autocomplete        "new-password"
        :data-original-value ""}]]]
    [:p.text-xs.text-gray-500
     "Stored encrypted. Gleanmo uses Cronometer's unofficial mobile API and only reads from it."]
    [:button.form-button-primary.w-full {:type "submit"}
     (if connected? "Replace credentials" "Connect")]]))

(defn- connection-section
  [ctx user-id]
  (let [email (connection/connected-email ctx user-id)]
    [:div.space-y-3
     (layout/section-header "CONNECTION")
     (if email
       (layout/card
        [:div.flex.flex-wrap.items-center.justify-between.gap-3
         [:div.min-w-0
          [:div.text-sm.text-gray-400 "Connected as"]
          [:div.text-sm.font-semibold.text-gray-200.break-all
           {:data-testid "cronometer-connected-email"} email]]
         [:div.flex.gap-2
          (biff/form
           {:action (str base-path "/sync"), :method "post"}
           [:button.form-button-primary
            {:type "submit", :data-testid "cronometer-sync-now"}
            "Sync now"])
          (biff/form
           {:action (str base-path "/disconnect"), :method "post"}
           [:button.form-button-secondary
            {:type "submit", :data-testid "cronometer-disconnect"}
            "Disconnect"])]])
       (when (queries/cronometer-connection-for-user (:biff/db ctx) user-id)
         [:p.text-sm.text-red-400
          "A connection is stored but cannot be decrypted with the current key. Reconnect to replace it."]))
     [:details {:open (nil? email)}
      [:summary.text-sm.text-gray-400.cursor-pointer.py-1
       (if email "Change credentials" "Connect an account")]
      [:div.mt-3 (layout/card (connect-form (some? email)))]]]))

(defn- count-row
  [label n]
  [:div.flex.items-baseline.justify-between.gap-3.py-2.border-t.border-dark
   {:data-testid "cronometer-count"}
   [:span.text-sm.text-gray-400 label]
   [:span.text-sm.font-semibold.text-gray-200 n]])

(def ^:private macro-keys [:energy :protein :carbs :fat :fiber])

(defn- macros
  [food-logs]
  (when-let [latest (->> food-logs (keep :cronometer/day) sort last)]
    (let [totals (transform/macro-totals
                  (filter #(= latest (:cronometer/day %)) food-logs))]
      [:div {:data-testid "cronometer-macros"}
       [:div.text-xs.text-gray-500.mb-2 (str "Latest day with food: " latest)]
       [:div.grid.grid-cols-2.sm:grid-cols-5.gap-3
        (for [k macro-keys
              :let [{:keys [label unit]} (nutrients/by-key k)]]
          [:div.rounded-lg.border.border-dark.p-3 {:key (name k)}
           [:div.text-xs.text-gray-500 label]
           [:div.text-lg.font-semibold.text-gray-200
            (fmt-amount (get totals k 0))
            [:span.text-xs.text-gray-500.ml-1 (name unit)]]])]])))

(defn- status-section
  [db user-id zone]
  (let [day-strs  (map str (sync/recent-days zone window-days))
        food-logs (queries/cronometer-docs-for-days db user-id :food-log day-strs)
        readings  (queries/cronometer-docs-for-days db user-id :biometric-log day-strs)
        days      (queries/cronometer-docs-for-days db user-id :nutrition-day day-strs)]
    [:div.space-y-3
     (layout/section-header "STATUS" (str "last " window-days " days"))
     (layout/card
      {:data-testid "cronometer-status"}
      [:div.text-sm.text-gray-400.mb-3
       "Last sync: "
       [:span.text-gray-200
        (fmt-instant (queries/last-cronometer-sync db user-id) zone)]]
      [:div
       (count-row "Food logs" (count food-logs))
       (count-row "Biometric readings" (count readings))
       (count-row "Diary days" (count days))
       (count-row "Foods and recipes (all time)"
                  (queries/count-entities-for-user db user-id :food))])
     (or (macros food-logs)
         (layout/empty-state
          {:message (str "No food logged in the last " window-days " days.")}))]))

;; =============================================================================
;; Page and actions
;; =============================================================================

(defn page
  [{:keys [biff/db session params], :as ctx}]
  (let [user-id (:uid session)
        user    (queries/get-entity-by-id db user-id)
        zone    (sync/user-zone user)]
    (ui/page
     ctx
     (layout/page-shell
      ctx {:width :narrow}
      (layout/page-header
       {:title    "Cronometer"
        :subtitle "Food, recipes, biometrics and daily nutrition, pulled from Cronometer for the agent API."})
      [:div.space-y-6
       (flash params)
       (if (connection/configured? ctx)
         (connection-section ctx user-id)
         (unconfigured-notice))
       (status-section db user-id zone)
       [:p.text-xs.text-gray-500
        "A daily job re-pulls the last " window-days
        " days for every connected account. Times are read in your account time zone ("
        zone ")."]]))))

(defn connect!
  [{:keys [params session], :as ctx}]
  (let [email    (str/trim (str (:email params)))
        password (str (:password params))]
    (cond
      (not (connection/configured? ctx)) (redirect "not-configured")
      (or (str/blank? email) (str/blank? password)) (redirect "connect-invalid")
      :else (do (connection/connect! ctx (:uid session) email password)
                (redirect "connected")))))

(defn disconnect!
  [{:keys [session], :as ctx}]
  (connection/disconnect! ctx (:uid session))
  (redirect "disconnected"))

(defn sync!
  "Run the recent-days sync inside the request, then 303 back. It takes a few
   seconds: one diary request per day plus a pause between days."
  [{:keys [biff/db session], :as ctx}]
  (let [user    (queries/get-entity-by-id db (:uid session))
        outcome (sync/sync-recent-locked! ctx user)]
    (cond
      (= ::sync/already-running outcome) (redirect "sync-running")
      (:not-connected outcome)           (redirect "not-connected")
      (:error outcome)                   (redirect "sync-failed")
      :else
      (let [total (fn [k] (reduce + 0 (map k (:days outcome))))]
        (redirect "synced" {:days    (count (:days outcome))
                            :created (total :created)
                            :updated (total :updated)
                            :deleted (total :deleted)})))))

(def routes
  [["/cronometer" {:get page}]
   ["/cronometer/connect" {:post connect!}]
   ["/cronometer/disconnect" {:post disconnect!}]
   ["/cronometer/sync" {:post sync!}]])
