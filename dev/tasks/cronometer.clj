(ns tasks.cronometer
  "Dev-only probe of Cronometer's unofficial mobile API — Phase 0 and Phase 1 of
   roadmap/056-cronometer-integration.md. Reads only; writes nothing to XTDB.

   The endpoints, payloads and hazards come from
   https://github.com/rwestergren/cronometer-api-mcp (client.py), which was
   reverse-engineered from the Android app. Two of those hazards shape this
   file:

   - Login sends `timezone: null`. A non-null value *overwrites* the account's
     timezone server side, which changes it for the phone app too.
   - Logins are rate-limited per account, so the session is cached in
     `cronometer_data/session.edn` (gitignored — it is a bearer token) and
     reused until Cronometer rejects it.

   Credentials come from `CRONOMETER_EMAIL` / `CRONOMETER_PASSWORD`, read from
   the environment first and `config.env` second.

   Usage:
     clj -M:dev cronometer-probe login
     clj -M:dev cronometer-probe dump [--day 2026-10-11] [--days 30]"
  (:require
   [cheshire.core :as json]
   [clj-http.client :as http]
   [clojure.java.io :as io]
   [clojure.pprint :as pprint]
   [clojure.string :as str]
   [clojure.tools.cli :refer [parse-opts]])
  (:import
   [java.time LocalDate]))

(def ^:private base-url "https://mobile.cronometer.com")
(def ^:private data-dir "cronometer_data")
(def ^:private session-file (str data-dir "/session.edn"))

(def ^:private app-auth
  {:api 3, :os "Android", :build "2807", :flavour "free"})

(def ^:private headers
  {"user-agent"      "Dart/3.9 (dart:io)"
   "content-type"    "text/plain; charset=utf-8"
   "accept-encoding" "gzip"})

;; =============================================================================
;; Credentials and session
;; =============================================================================

(defn- config-env-value
  "`k` from a `KEY=value` line of the local `config.env`, or nil."
  [k]
  (let [f (io/file "config.env")]
    (when (.exists f)
      (some (fn [line]
              (let [[lk v] (str/split (str/trim line) #"=" 2)]
                (when (= lk k)
                  (str/replace (str v) #"^\"|\"$" ""))))
            (str/split-lines (slurp f))))))

(defn- setting
  [k]
  (or (not-empty (System/getenv k)) (not-empty (config-env-value k))))

(defn- credentials
  []
  (let [email    (setting "CRONOMETER_EMAIL")
        password (setting "CRONOMETER_PASSWORD")]
    (when-not (and email password)
      (throw (ex-info "Set CRONOMETER_EMAIL and CRONOMETER_PASSWORD in the environment or config.env"
                      {})))
    [email password]))

(defn- load-session
  []
  (let [f (io/file session-file)]
    (when (.exists f)
      (read-string (slurp f)))))

(defn- save-session!
  [session]
  (io/make-parents session-file)
  (spit session-file (pr-str session)))

;; =============================================================================
;; HTTP
;; =============================================================================

(defn- post
  [endpoint payload]
  (let [resp (http/post (str base-url endpoint)
                        {:headers          headers
                         :body             (json/generate-string payload)
                         :throw-exceptions false
                         ;; Cronometer's load balancer sets cookies whose
                         ;; `Expires` the default policy rejects with a warning.
                         :cookie-policy    :standard
                         :socket-timeout   30000
                         :connection-timeout 30000})]
    {:status (:status resp)
     :body   (some-> (:body resp) not-empty (json/parse-string true))}))

(defn login!
  "Log in and cache `{:user-id :token :timezone}`. Returns the raw response with
   the session key redacted."
  []
  (let [[email password] (credentials)
        {:keys [status body]}
        (post "/api/v2/login"
              {:email         email
               :password      password
               ;; Must stay null: a non-null timezone is a write to the account.
               :timezone      nil
               :userCode      nil
               :build         "4.48.2 b2807-a"
               :device        "Android 14 (SDK 34), Google Pixel 6 Pro"
               :firebaseToken ""
               :features      {:food_search_config
                               "{\"newSearch\": true, \"newSpellcheck\": true}"
                               :use_gpt_autofill "true"}
               :auth          (merge {:userId nil, :token nil} app-auth)
               :lastSeen      0
               :config        {:call_version 2}})]
    (when-not (and (= 200 status) (:sessionKey body))
      (throw (ex-info "Cronometer login failed"
                      {:status status
                       :error  (or (:error body) (:result body))})))
    (let [session {:user-id  (:id body)
                   :token    (:sessionKey body)
                   :timezone (:timezone body)}]
      (save-session! session)
      (assoc body :sessionKey "<redacted>"))))

(defn- auth-failed?
  [{:keys [status body]}]
  (or (#{401 403} status)
      (and (map? body) (#{"FAIL" "FAILURE"} (:result body)))))

(defn- request
  "POST a v2 endpoint with the cached session, logging in at most once when
   there is no session or Cronometer rejects it."
  [endpoint payload]
  (letfn [(send [{:keys [user-id token]}]
            (post endpoint
                  (assoc payload
                         :auth     (merge {:userId user-id, :token token} app-auth)
                         :lastSeen 0)))]
    (let [session (or (load-session)
                      (do (println "No cached session; logging in.")
                          (login!)
                          (load-session)))
          resp    (send session)
          resp    (if (auth-failed? resp)
                    (do (println "Session rejected; logging in again.")
                        (login!)
                        (send (load-session)))
                    resp)]
      (when (or (auth-failed? resp) (not= 200 (:status resp)))
        (throw (ex-info (str "Cronometer request failed: " endpoint)
                        {:status (:status resp), :body (:body resp)})))
      (:body resp))))

;; =============================================================================
;; Endpoints
;; =============================================================================

(defn- format-day
  "Cronometer wants a non-zero-padded `YYYY-M-D`."
  [^LocalDate d]
  (str (.getYear d) "-" (.getMonthValue d) "-" (.getDayOfMonth d)))

(defn get-diary
  "Diary entries for one day."
  [day]
  (request "/api/v2/get_diary"
           {:day (format-day day), :config {:call_version 1}}))

(defn get-nutrients
  "The nutrient definition catalog — ids, names, units, RDIs. Despite the name
   and the `day` it takes, it carries no consumed amounts."
  [day]
  (request "/api/v2/get_nutrients"
           {:day (format-day day), :config {:call_version 1}}))

(defn get-foods
  "Full food records — name, measures, per-100g nutrients — for `food-ids`.
   Diary servings carry only ids, so this is how they get names."
  [food-ids]
  (request "/api/v2/get_foods"
           {:ids (vec food-ids), :config {:call_version 1}}))

(defn get-nutrition-scores
  "Per-nutrient consumed amounts for the given diary servings. The date fields
   are ignored by the server when `servingIds` is given; the reference client
   sends this placeholder."
  [serving-ids]
  (request "/api/v2/get_nutrition_scores"
           {:startDay   "1900-1-1"
            :endDay     "1900-1-1"
            :servingIds (vec serving-ids)
            :supplements "true"
            :config     {:call_version 1}}))

(defn get-metrics
  "The biometric catalog: metric ids and their unit ids."
  []
  (request "/api/v2/get_metrics" {:config {:call_version 1}}))

(defn get-biometrics
  "`{:data [{:day :value}]}` for one metric and unit over an inclusive range."
  [metric-id unit-id start end]
  (request "/api/v2/get_biometrics"
           {:metricId metric-id
            :unitId   unit-id
            :start    (format-day start)
            :end      (format-day end)
            :config   {:call_version 1}}))

;; =============================================================================
;; CLI
;; =============================================================================

(defn- write-edn!
  [file-name data]
  (let [path (str data-dir "/" file-name)]
    (io/make-parents path)
    (with-open [w (io/writer path)]
      (pprint/pprint data w))
    (println "  wrote" path)))

(defn- metric-named
  [metrics metric-name]
  (some #(when (= (str/lower-case (str (:name %))) metric-name) %) metrics))

(defn- dump!
  [{:keys [day days]}]
  (let [day   (if day (LocalDate/parse day) (.minusDays (LocalDate/now) 1))
        start (.minusDays day (dec days))
        stamp (str day)]
    (println "Fetching diary for" stamp)
    (let [diary    (get-diary day)
          servings (filter #(= "Serving" (:type %)) (:diary diary))]
      (write-edn! (str "diary_" stamp ".edn") diary)
      (println "Fetching" (count servings) "servings' foods and nutrition scores")
      (write-edn! (str "foods_" stamp ".edn")
                  (get-foods (distinct (keep :foodId servings))))
      (write-edn! (str "nutrition_scores_" stamp ".edn")
                  (get-nutrition-scores (keep :servingId servings))))
    (println "Fetching nutrient definitions")
    (write-edn! "nutrient_definitions.edn" (get-nutrients day))
    (println "Fetching metric catalog")
    (let [metrics (:metrics (get-metrics))]
      (write-edn! "metrics.edn" metrics)
      (doseq [metric-name ["weight" "body fat"]]
        (if-let [{:keys [id units]} (metric-named metrics metric-name)]
          (let [unit (first units)]
            (println "Fetching" metric-name "in" (:name unit) "from" (str start) "to" stamp)
            (write-edn! (str "biometrics_" (str/replace metric-name " " "_") "_" stamp ".edn")
                        {:metric metric-name
                         :unit   unit
                         :result (get-biometrics id (:id unit) start day)}))
          (println "No metric named" (pr-str metric-name) "in the catalog; skipped"))))))

(def ^:private cli-options
  [["-d" "--day DAY" "Day to fetch, YYYY-MM-DD (default: yesterday)"]
   ["-n" "--days DAYS" "Biometric range length in days, ending on --day"
    :default 30 :parse-fn parse-long]])

(defn probe
  "Probe Cronometer's mobile API. Read-only; nothing is written to XTDB.

  Usage:
  - clj -M:dev cronometer-probe login — log in once, print user id and the
    account timezone, cache the session. Run this, then check the phone app
    is still signed in (roadmap 056 Phase 0).
  - clj -M:dev cronometer-probe dump [--day YYYY-MM-DD] [--days N] — dump one
    day of diary and nutrients, the metric catalog, and weight/body-fat over N
    days into cronometer_data/."
  [& args]
  (let [{:keys [arguments options errors]} (parse-opts args cli-options)]
    (cond
      (seq errors)
      (run! println errors)

      (= ["login"] arguments)
      (let [body (login!)]
        (write-edn! "login.edn" body)
        (println "Logged in as user" (:id body)
                 "— account timezone:" (pr-str (:timezone body)))
        (println "Now open Cronometer on your phone and confirm it is still signed in."))

      (= ["dump"] arguments)
      (dump! options)

      :else
      (println "Usage: clj -M:dev cronometer-probe login | dump [--day YYYY-MM-DD] [--days N]"))))
