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

   The HTTP calls live in `tech.jgood.gleanmo.cronometer.client`; this task
   only supplies credentials and the file-backed session store.

   Credentials come from `CRONOMETER_EMAIL` / `CRONOMETER_PASSWORD`, read from
   the environment first and `config.env` second.

   Usage:
     clj -M:dev cronometer-probe login
     clj -M:dev cronometer-probe dump [--day 2026-10-11] [--days 30]

   This file also holds the two tasks that write to XTDB, so the sync can be
   run before the status page's connect form exists:
     clj -M:dev cronometer-connect --target dev --email <gleanmo email>
     clj -M:dev cronometer-sync --target dev --email <gleanmo email> [--days 7]
   Locally the dev server must be stopped first — RocksDB takes an exclusive
   lock."
  (:require
   [clojure.java.io :as io]
   [clojure.pprint :as pprint]
   [clojure.string :as str]
   [clojure.tools.cli :refer [parse-opts]]
   [com.biffweb :as biff]
   [tasks.migrate :as migrate]
   [tasks.util :as u]
   [tech.jgood.gleanmo.cronometer.client :as client]
   [tech.jgood.gleanmo.cronometer.connection :as connection]
   [tech.jgood.gleanmo.cronometer.sync :as sync]
   [tech.jgood.gleanmo.db.queries :as queries])
  (:import
   [java.time LocalDate]))

(def ^:private data-dir "cronometer_data")
(def ^:private session-file (str data-dir "/session.edn"))

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

(def ^:private file-store
  "CLI runs are short-lived and logins are rate-limited, so the probe keeps
   its session on disk rather than in the client's in-memory store."
  {:load   load-session
   :save!  save-session!
   :clear! #(io/delete-file session-file true)})

(defn- conn
  []
  (let [[email password] (credentials)]
    (client/connection {:email email, :password password, :store file-store})))

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
    (let [c        (conn)
          diary    (client/get-diary c day)
          servings (filter #(= "Serving" (:type %)) (:diary diary))]
      (write-edn! (str "diary_" stamp ".edn") diary)
      (println "Fetching" (count servings) "servings' foods and nutrition scores")
      (write-edn! (str "foods_" stamp ".edn")
                  {:foods (client/get-foods c (distinct (keep :foodId servings)))})
      (write-edn! (str "nutrition_scores_" stamp ".edn")
                  (client/get-nutrition-scores c (keep :servingId servings)))
      (println "Fetching nutrient definitions")
      (write-edn! "nutrient_definitions.edn"
                  {:nutrients (client/get-nutrient-definitions c day)})
      (println "Fetching metric catalog")
      (let [metrics (client/get-metrics c)]
        (write-edn! "metrics.edn" metrics)
        (doseq [metric-name ["weight" "body fat"]]
          (if-let [{:keys [id units]} (metric-named metrics metric-name)]
            (let [unit (first units)]
              (println "Fetching" metric-name "in" (:name unit) "from" (str start) "to" stamp)
              (write-edn! (str "biometrics_" (str/replace metric-name " " "_") "_" stamp ".edn")
                          {:metric metric-name
                           :unit   unit
                           :result (client/get-biometrics c id (:id unit) start day)}))
            (println "No metric named" (pr-str metric-name) "in the catalog; skipped")))))))

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
      (let [session (client/login! (conn))]
        (println "Logged in as user" (:user-id session)
                 "— account timezone:" (pr-str (:timezone session)))
        (println "Now open Cronometer on your phone and confirm it is still signed in."))

      (= ["dump"] arguments)
      (dump! options)

      :else
      (println "Usage: clj -M:dev cronometer-probe login | dump [--day YYYY-MM-DD] [--days N]"))))

;; =============================================================================
;; Connect and sync (write to XTDB)
;; =============================================================================

(def ^:private db-options
  [["-t" "--target TARGET" "Target database: dev or prod" :default "dev"]
   ["-e" "--email EMAIL" "Email of the Gleanmo user"]
   ["-n" "--days DAYS" "Days to sync, ending today in the user's zone"
    :default sync/default-days :parse-fn parse-long]])

(defn- with-user-ctx
  "Start a node for `target`, resolve `email`, and call `(f ctx user)`. The
   ctx carries `:biff/secret` from config, so the credentials key resolves as
   it does in the app."
  [args f]
  (let [{:keys [options errors]} (parse-opts args db-options)
        {:keys [target email]}   options]
    (cond
      (seq errors) (do (run! u/print-red errors) (System/exit 1))
      (not email)  (do (u/print-red "ERROR: --email is required") (System/exit 1))
      :else
      (let [config (biff/use-aero-config {:biff.config/profile target})
            node   (migrate/start-node target)
            failed (try
                     (let [ctx  (assoc (migrate/build-ctx node)
                                       :biff/secret (:biff/secret config))
                           uid  (queries/user-id-by-email (:biff/db ctx) email)
                           user (when uid (queries/get-entity-by-id (:biff/db ctx) uid))]
                       (cond
                         (not user)
                         (do (u/print-red (str "No user found for email: " email)) true)

                         (not (connection/configured? ctx))
                         (do (u/print-red "CREDENTIALS_KEY is missing or not a base64 32-byte key.")
                             true)

                         :else (f ctx user options)))
                     (finally
                       (.close node)
                       (u/print-cyan "Node closed.")))]
        (when failed (System/exit 1))))))

(defn connect
  "Store CRONOMETER_EMAIL / CRONOMETER_PASSWORD (environment, then config.env)
   as the user's encrypted Cronometer connection, replacing any earlier one.

  Usage: clj -M:dev cronometer-connect --target dev --email <gleanmo email>"
  [& args]
  (with-user-ctx
    args
    (fn [ctx user _]
      (let [[email password] (credentials)]
        (connection/connect! ctx (:xt/id user) email password)
        (u/print-green "Stored an encrypted Cronometer connection.")
        false))))

(defn sync-recent
  "Sync the last N days (default 7) from the user's stored connection.

  Usage: clj -M:dev cronometer-sync --target dev --email <gleanmo email> [--days 7]"
  [& args]
  (with-user-ctx
    args
    (fn [ctx user {:keys [days]}]
      (let [{:keys [error not-connected], synced :days} (sync/sync-recent! ctx user days)]
        (doseq [{:keys [day created updated deleted unchanged warnings]} synced]
          (println day {:created created :updated updated :deleted deleted
                        :unchanged unchanged :warnings (count warnings)}))
        (cond
          not-connected (do (u/print-red "No Cronometer connection; run cronometer-connect first.") true)
          error         (do (u/print-red (str "Sync stopped: " error)) true)
          :else         (do (u/print-green "Sync complete.") false))))))
