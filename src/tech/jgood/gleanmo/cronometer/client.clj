(ns tech.jgood.gleanmo.cronometer.client
  "Cronometer's unofficial mobile REST API. This namespace owns every HTTP
   call to Cronometer, so a change in the API's shape is a one-file fix.

   Endpoints, payloads and hazards come from
   https://github.com/rwestergren/cronometer-api-mcp (client.py), reverse
   engineered from the Android app, and were verified by the probe in
   `dev/tasks/cronometer.clj`. See roadmap/056-cronometer-integration.md.

   Two hazards shape this file:

   - Login sends `timezone: null`. A non-null value *overwrites* the account's
     timezone server side, which changes it for the phone app too.
   - Logins are rate-limited per account, so the session is kept and reused
     until Cronometer rejects it. A connection's `:store` holds it: in memory
     by default (an atom keyed by user id, lost on restart — correct only with
     one backend instance, see roadmap/077), or a file for short-lived CLI runs.

   The client never reads config. Every call takes a connection built by
   `connection` from the user's credentials, and the caller that reads config
   decides the base URL (the dev-only fake, roadmap 056). Never log a
   connection, the password, or the session key."
  (:require
   [cheshire.core :as json]
   [clj-http.client :as http]
   [clojure.tools.logging :as log])
  (:import
   [java.time LocalDate]))

(def default-base-url "https://mobile.cronometer.com")

(def ^:private app-auth
  {:api 3, :os "Android", :build "2807", :flavour "free"})

(def ^:private headers
  {"user-agent"      "Dart/3.9 (dart:io)"
   "content-type"    "text/plain; charset=utf-8"
   "accept-encoding" "gzip"})

;; =============================================================================
;; Session stores
;; =============================================================================

(defonce ^:private sessions
  ;; gleanmo user id -> {:user-id :token :timezone}
  (atom {}))

(defn memory-store
  "The default session store: process memory, keyed by `id`."
  [id]
  {:load   #(get @sessions id)
   :save!  #(swap! sessions assoc id %)
   :clear! #(swap! sessions dissoc id)})

(defn forget-session!
  "Drop the in-memory session for `id`, e.g. after a disconnect."
  [id]
  (swap! sessions dissoc id))

(defn connection
  "A connection for one user. `id` keys the default in-memory session store;
   pass `store` (`{:load :save! :clear!}`) to keep the session elsewhere, and
   `base-url` to target something other than Cronometer itself."
  [{:keys [id email password store base-url]}]
  {:email    email
   :password password
   :store    (or store (memory-store id))
   :base-url (or base-url default-base-url)})

;; =============================================================================
;; HTTP
;; =============================================================================

(defn- post
  [base-url endpoint payload]
  (let [resp (http/post (str base-url endpoint)
                        {:headers            headers
                         :body               (json/generate-string payload)
                         :throw-exceptions   false
                         ;; Cronometer's load balancer sets cookies whose
                         ;; `Expires` the default policy rejects with a warning.
                         :cookie-policy      :standard
                         :socket-timeout     30000
                         :connection-timeout 30000})]
    {:status (:status resp)
     :body   (some-> (:body resp) not-empty (json/parse-string true))}))

(defn login!
  "Log in, keep `{:user-id :token :timezone}` in the connection's store, and
   return it. `:timezone` is the account zone Cronometer reports."
  [{:keys [email password store base-url]}]
  (let [{:keys [status body]}
        (post base-url "/api/v2/login"
              {:email         email
               :password      password
               ;; Must stay null: a non-null timezone is a write to the account.
               :timezone      nil
               :userCode      nil
               :build         "4.48.2 b2807-a"
               :device        "Android 14 (SDK 34), Google Pixel 6 Pro"
               ;; Unverified: an empty push token may clobber the phone's push
               ;; registration (roadmap 056, Constraints and hazards).
               :firebaseToken ""
               :features      {:food_search_config
                               "{\"newSearch\": true, \"newSpellcheck\": true}"
                               :use_gpt_autofill "true"}
               :auth          (merge {:userId nil, :token nil} app-auth)
               :lastSeen      0
               :config        {:call_version 2}})]
    (when-not (and (= 200 status) (:sessionKey body))
      (throw (ex-info "Cronometer login failed"
                      {:type   ::login-failed
                       :status status
                       :error  (or (:error body) (:result body))})))
    (let [session {:user-id  (:id body)
                   :token    (:sessionKey body)
                   :timezone (:timezone body)}]
      ((:save! store) session)
      session)))

(defn- auth-failed?
  [{:keys [status body]}]
  (or (#{401 403} status)
      (and (map? body) (#{"FAIL" "FAILURE"} (:result body)))))

(defn- request
  "POST a v2 endpoint with the stored session, logging in at most once: when
   there is no session, or when Cronometer rejects it."
  [{:keys [store base-url], :as conn} endpoint payload]
  (letfn [(send [{:keys [user-id token]}]
            (post base-url endpoint
                  (assoc payload
                         :auth     (merge {:userId user-id, :token token} app-auth)
                         :lastSeen 0)))]
    (let [session (or ((:load store)) (login! conn))
          resp    (send session)
          resp    (if (auth-failed? resp)
                    (do (log/info "Cronometer session rejected; logging in again")
                        ((:clear! store))
                        (send (login! conn)))
                    resp)]
      (when (or (auth-failed? resp) (not= 200 (:status resp)))
        (throw (ex-info (str "Cronometer request failed: " endpoint)
                        {:type   ::request-failed
                         :status (:status resp)
                         :error  (when (map? (:body resp))
                                   (or (:error (:body resp))
                                       (:result (:body resp))))})))
      (:body resp))))

;; =============================================================================
;; Endpoints
;; =============================================================================

(defn format-day
  "Cronometer wants a non-zero-padded `YYYY-M-D`."
  [^LocalDate d]
  (str (.getYear d) "-" (.getMonthValue d) "-" (.getDayOfMonth d)))

(defn get-diary
  "One day's diary: servings, biometrics, exercises, `summary`, `template`,
   `info`, `fasts`."
  [conn ^LocalDate day]
  (request conn "/api/v2/get_diary"
           {:day (format-day day), :config {:call_version 1}}))

(defn get-foods
  "Full food records — name, tags, measures, nutrients, recipe ingredients —
   for `food-ids`."
  [conn food-ids]
  (if (seq food-ids)
    (:foods (request conn "/api/v2/get_foods"
                     {:ids (vec food-ids), :config {:call_version 1}}))
    []))

(defn get-metrics
  "The biometric catalog: metrics with their units."
  [conn]
  (:metrics (request conn "/api/v2/get_metrics" {:config {:call_version 1}})))

(defn get-nutrient-definitions
  "The nutrient definition catalog — ids, names, units, RDIs. The endpoint
   takes a day but carries no consumed amounts."
  [conn ^LocalDate day]
  (:nutrients (request conn "/api/v2/get_nutrients"
                       {:day (format-day day), :config {:call_version 1}})))

(defn get-nutrition-scores
  "Per-nutrient consumed amounts for diary servings. The server ignores the
   date fields when `servingIds` is given; the reference client sends this
   placeholder."
  [conn serving-ids]
  (request conn "/api/v2/get_nutrition_scores"
           {:startDay    "1900-1-1"
            :endDay      "1900-1-1"
            :servingIds  (vec serving-ids)
            :supplements "true"
            :config      {:call_version 1}}))

(defn get-biometrics
  "`{:data [{:day :value}]}` for one metric and unit over an inclusive range."
  [conn metric-id unit-id ^LocalDate start ^LocalDate end]
  (request conn "/api/v2/get_biometrics"
           {:metricId metric-id
            :unitId   unit-id
            :start    (format-day start)
            :end      (format-day end)
            :config   {:call_version 1}}))
