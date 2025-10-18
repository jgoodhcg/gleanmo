(ns tech.jgood.gleanmo.observability
  "Central helpers for application performance profiling."
  (:require
   [clojure.java.shell    :as shell]
   [clojure.string        :as str]
   [clojure.tools.logging :as log]
   [com.biffweb           :as biff]
   [taoensso.encore       :as enc]
   [taoensso.tufte        :as tufte]
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

(defn current-instance-id
  []
  instance-id)

(defn instance-started-at-inst
  []
  instance-started-at)

(defn current-git-sha
  []
  git-sha)

(defn init!
  "Idempotently configure tufte for the application.

  - Registers an aggregator so we can periodically persist summaries.
  - Captures per-process metadata for inclusion in persisted snapshots."
  []
  (when-not @profiling-initialized?
    (reset! stats-accumulator
            (tufte/add-accumulating-handler!
             {:handler-id :gleanmo.accumulator,
              :ns-pattern "*"}))
    (reset! profiling-initialized? true)
    (log/info "Tufte profiling initialized for instance" instance-id
              "started" instance-started-at)))

(defn aggregator-snapshot
  "Flush and return the aggregated metrics map.
   Callers are responsible for persisting the result and, if desired, resetting the aggregator state."
  []
  (when-not @profiling-initialized?
    (init!))
  (when-let [acc @stats-accumulator]
    (when-let [grouped (not-empty @acc)]
      {:performance-report/instance-id instance-id,
       :performance-report/instance-started-at instance-started-at,
       :performance-report/generated-at (java.time.Instant/now),
       :performance-report/git-sha git-sha,
       :performance-report/pstats
       (reduce-kv
        (fn [m group-id pstats]
          (let [key       (cond
                            (keyword? group-id) group-id
                            (and (vector? group-id)
                                 (keyword? (second group-id)))
                            (second group-id)
                            (coll? group-id)
                            (keyword (str/join "/"
                                               (map #(if (keyword? %)
                                                       (name %)
                                                       (str %))
                                                    group-id)))
                            :else
                            (keyword (str group-id)))
                realized  (enc/force-ref pstats)
                {:keys [clock stats]} realized
                formatted (tufte/format-pstats realized
                                               {:columns [:n :mean :total
                                                          :min :max]})
                stats-map (into {}
                                (map (fn [[pid entry]]
                                       [pid (into {} entry)]))
                                stats)]
            (assoc m
                   key {:clock   clock,
                        :stats   stats-map,
                        :summary formatted})))
        {}
        grouped)})))

(defn- instance-doc-id
  []
  (keyword "performance-report" instance-id))

(defn persist-instance-snapshot!
  "Flush accumulated metrics and persist them to XTDB.
   Returns the document that was written, or nil if no metrics were available."
  [ctx]
  (when-let [snapshot (aggregator-snapshot)]
    (let [doc (merge {:xt/id          (instance-doc-id),
                      :db/doc-type    :performance-report,
                      ::sm/type       :performance-report,
                      ::sm/created-at instance-started-at}
                     snapshot)]
      (biff/submit-tx ctx [doc])
      doc)))

(defn every-n-seconds
  [n]
  (iterate #(biff/add-seconds % n) (java.util.Date.)))

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

(defmacro profile-block
  "Convenience macro that profiles a block as a standalone unit.
   Wraps the body in both `tufte/profile` and `tufte/p` so the accumulator records it."
  [key & body]
  `(tufte/profile {:id ~key}
                  (tufte/p ~key (do ~@body))))

(defn use-observability
  "Biff component initializer that ensures profiling is active and records instance metadata."
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

(defn persist-task
  [ctx]
  (if-let [doc (persist-instance-snapshot! ctx)]
    (log/info "Persisted performance snapshot for instance"
              instance-id
              {:git git-sha, :pstats (keys (:performance-report/pstats doc))})
    (log/info "No performance metrics captured for instance"
              instance-id
              "yet")))

(def module
  {:tasks [{:task     #'persist-task,
            :schedule #(every-n-seconds 60)}]})
