(ns tech.jgood.gleanmo.observability
  "Central helpers for application performance profiling."
  (:require
   [clojure.java.shell    :as shell]
   [clojure.string        :as str]
   [clojure.tools.logging :as log]
   [com.biffweb           :as biff]
   [taoensso.encore       :as enc]
   [taoensso.tufte        :as tufte]
   [taoensso.tufte.impl   :as timpl]
   [tech.jgood.gleanmo.schema.meta :as sm]))

(defonce ^:private profiling-initialized? (atom false))
(defonce ^:private stats-accumulator (atom nil))

(defonce ^:private instance-id
  ;; generate-once identifier so metrics can be grouped per process
  ;; instance
  (str (java.util.UUID/randomUUID)))

(defn- resolve-git-sha
  []
  (let [env-sha (some-> (System/getenv "GLEANMO_COMMIT_HASH")
                        str/trim
                        not-empty)
        git-sha (when-not env-sha
                  (try
                    (let [{:keys [exit out]} (shell/sh "git" "rev-parse" "HEAD")
                          trimmed (some-> out
                                          str/trim
                                          not-empty)]
                      (when (zero? exit) trimmed))
                    (catch Exception _
                      nil)))]
    (or env-sha git-sha "dev-env")))

(defonce ^:private git-sha (resolve-git-sha))

(defonce ^:private instance-started-at
  ;; capture startup instant for easier correlation across deploys
  (java.time.Instant/now))

(defonce ^:private accumulated-pstats (atom {}))

(defn- id->string
  [id]
  (cond
    (instance? clojure.lang.Named id) (name id)
    (string? id) id
    :else (pr-str id)))

(def ^:private uuid-pattern #"(?i)[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}")
(def ^:private compact-uuid-pattern #"(?i)[0-9a-f]{32}")
(def ^:private numeric-id-pattern #"[0-9]{6,}")

(defn current-instance-id
  []
  instance-id)

(defn instance-started-at-inst
  []
  instance-started-at)

(defn current-git-sha
  []
  git-sha)

(def ^:private handler-config
  {:handler-id :gleanmo.accumulator
   :ns-pattern "*"})

(defn- register-accumulator!
  []
  (reset! stats-accumulator
          (tufte/add-accumulating-handler! handler-config)))

(defn init!
  "Idempotently configure tufte for the application.

  - Registers an aggregator so we can periodically persist summaries.
  - Captures per-process metadata for inclusion in persisted snapshots."
  []
  (when-not @profiling-initialized?
    (register-accumulator!)
    (reset! profiling-initialized? true)
    (log/info "Tufte profiling initialized for instance" instance-id
              "started" instance-started-at)))

(defn- normalize-token
  [token]
  (let [s (-> token id->string str/lower-case)]
    (-> s
        (str/replace uuid-pattern "uuid")
        (str/replace compact-uuid-pattern "uuid")
        (str/replace numeric-id-pattern "num")
        (str/replace #"uuid+" "uuid")
        (str/replace #"num+" "num")
        (str/replace #"-{2,}" "-")
        (str/replace #"/{2,}" "/"))))

(defn group-id->keyword
  [id]
  (cond
    (keyword? id)
    (let [ns-part (some-> (namespace id) normalize-token)
          name-part (normalize-token (name id))]
      (if ns-part
        (keyword ns-part name-part)
        (keyword name-part)))

    (string? id)
    (keyword (normalize-token id))

    (sequential? id)
    (let [segments (map normalize-token id)
          joined (str/join "/" segments)]
      (keyword joined))

    :else
    (keyword (normalize-token id))))

(defn format-pstats+
  [pstats]
  (let [formatted (tufte/format-pstats pstats {:columns [:n :mean :total :min :max]})]
    formatted))

(defn aggregator-snapshot
  "Return a snapshot of the current metrics. When `reset?` is true, the accumulator
   is cleared after the snapshot is taken."
  ([] (aggregator-snapshot {:reset? false}))
  ([{:keys [reset?] :or {reset? false}}]
   (when-not @profiling-initialized?
     (init!))
   (let [delta (when-let [sacc @stats-accumulator]
                 (not-empty @sacc))]
     (when (seq delta)
       (swap! accumulated-pstats
              (fn [current]
                (reduce-kv
                 (fn [acc id pstats]
                   (let [kid (group-id->keyword id)]
                     (update acc kid
                             (fn [existing]
                               (if existing
                                 (timpl/merge-pstats existing pstats)
                                 pstats)))))
                 current
                 delta)))))
   (let [aggregate @accumulated-pstats]
     (when (seq aggregate)
       (let [report {:performance-report/instance-id instance-id,
                     :performance-report/instance-started-at instance-started-at,
                     :performance-report/generated-at (java.time.Instant/now),
                     :performance-report/git-sha git-sha,
                     :performance-report/pstats
                     (reduce-kv
                      (fn [m id pstats]
                        (let [realized (enc/force-ref pstats)]
                          (assoc m id {:clock   (:clock realized)
                                       :stats   (:stats realized)
                                       :summary (format-pstats+ realized)})))
                      {}
                      aggregate)}]
         (when reset?
           (reset! accumulated-pstats {}))
         report)))))

(defn current-metrics
  "Return the latest metrics snapshot without mutating the accumulator.
   Returns nil if no profiling data has been captured yet."
  []
  (aggregator-snapshot))

(defn- instance-doc-id
  []
  (keyword "performance-report" instance-id))

(defn persist-instance-snapshot!
  "Flush accumulated metrics and persist them to XTDB.
   Returns the document that was written, or nil if no metrics were available."
  [ctx]
  (when-let [snapshot (aggregator-snapshot {:reset? true})]
    (let [doc (merge {:xt/id          (instance-doc-id),
                      :db/doc-type    :performance-report,
                      ::sm/type       :performance-report,
                      ::sm/created-at instance-started-at}
                     snapshot)]
      (biff/submit-tx ctx [doc])
      doc)))

(defn profile-request
  "Wrap an arbitrary thunk in a tufte profile block keyed by a readable descriptor."
  [route-key f]
  (when-not @profiling-initialized?
    (init!))
  (tufte/profile {:id       [:http route-key],
                  :dynamic? true}
                 (tufte/p :handler (f))))

(defn wrap-request-profiling
  "Ring middleware that profiles each incoming request."
  [handler]
  (fn [req]
    (let [{:keys [request-method uri]} req
          match     (:reitit.core/match req)
          route-key (if-let [named-route (some-> match
                                                 :data
                                                 :name)]
                      (if (keyword? named-route)
                        named-route
                        (keyword (str named-route)))
                      (let [raw  (str (-> request-method
                                          name
                                          str/lower-case)
                                      "-"
                                      (str/replace uri #"^/" ""))
                            slug (-> raw
                                     (str/lower-case)
                                     (str/replace #"[^a-z0-9]+" "-")
                                     (str/replace #"^-+" "")
                                     (str/replace #"-+$" ""))]
                        (keyword (if (str/blank? slug) "request" slug))))]
      (profile-request route-key #(handler req)))))

#_{:clj-kondo/ignore [:shadowed-var]}
(defmacro profile-block
  "Convenience macro that profiles a block as a standalone unit.
   Wraps the body in both `tufte/profile` and `tufte/p` so the accumulator records it."
  [key & body]
  `(tufte/profile {:id ~key}
                  (tufte/p ~key (do ~@body))))

(defn use-observability
  "Biff component initializer that ensures profiling is active and records instance metadata."
  #_{:clj-kondo/ignore [:unused-binding]}
  [{:keys [biff/stop], :as system}]
  (init!)
  (log/info "Observability component active for" instance-id)
  (-> system
      (assoc :observability/instance {:id         instance-id,
                                      :started-at instance-started-at,
                                      :git-sha    git-sha})
      (update :biff/stop
              (fnil conj [])
              (fn []
                (log/info "Stopping observability component for" instance-id)
                ;; Ensure we don't leave buffered metrics on shutdown.
                (aggregator-snapshot)))))

(def module
  {})
