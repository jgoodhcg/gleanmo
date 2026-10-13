(ns tech.jgood.gleanmo.fake-cronometer
  "Dev-only fake of Cronometer's mobile API, so the sync runs end to end in dev
   and e2e with no real credentials and no traffic leaving the machine
   (roadmap/056-cronometer-integration.md).

   Two gates keep it out of production. This namespace lives under `dev/`,
   which the uberjar does not include, and every route answers 404 unless
   `:gleanmo.cronometer/fake` is true — hard false under the prod profile, set
   in dev by `FAKE_CRONOMETER=true`. The same flag points
   `cronometer.connection/client-connection` at this server.

   Same request and response shapes as the real API: JSON in a `text/plain`
   body, the auth block in the body, `sessionKey` from login. Any email logs in
   with any password except `invalid`; the data comes from
   `fake-cronometer.data`.

   `POST /dev/cronometer/reject-next-session` with `{\"email\": ...}` makes the
   account's next request fail as an expired session, to exercise re-login."
  (:require
   [cheshire.core :as json]
   [clojure.string :as str]
   [tech.jgood.gleanmo.cronometer.connection :as connection]
   [tech.jgood.gleanmo.fake-cronometer.data :as data])
  (:import
   [java.time LocalDate]))

(defn- respond
  [status body]
  {:status  status
   :headers {"content-type" "application/json"}
   :body    (json/generate-string body)})

(defn- payload
  "The request's JSON. The client sends `text/plain`, which muuntaja leaves
   unread; a JSON content type arrives already decoded."
  [{:keys [body body-params]}]
  (or body-params
      (some-> (if (string? body) body (some-> body slurp))
              not-empty
              (json/parse-string true))))

(defn- parse-day
  "Cronometer's request format, `YYYY-M-D`."
  ^LocalDate [s]
  (let [[y m d] (map parse-long (str/split (str s) #"-"))]
    (LocalDate/of (int y) (int m) (int d))))

(defn- session-token
  [uid]
  (str "fake-session-" uid))

(defonce ^:private reject-next
  ;; fake account ids whose next request fails as an expired session
  (atom #{}))

(defn- authenticate
  "The account id for a request's auth block, or nil when it is not a session
   this fake issued or a rejection is pending for it."
  [{{:keys [userId token]} :auth}]
  (when (and (int? userId) (= token (session-token userId)))
    (let [[before _] (swap-vals! reject-next disj userId)]
      (when-not (contains? before userId)
        userId))))

(defn- endpoint
  "A v2 endpoint: authenticate, then answer with `(f uid payload)`."
  [f]
  (fn [req]
    (let [p (payload req)]
      (if-let [uid (authenticate p)]
        (respond 200 (f uid p))
        (respond 200 {:result "FAIL", :error "Session expired"})))))

(defn- login
  [req]
  (let [{:keys [email password], :as p} (payload req)]
    (cond
      ;; The real API treats a non-null timezone as a write to the account
      ;; (roadmap 056, Constraints and hazards). Refuse it so a regression in
      ;; the client fails loudly here rather than against a real account.
      (some? (:timezone p))
      (respond 400 {:result "FAIL", :error "Fake Cronometer: login must send timezone null"})

      (or (str/blank? email) (= "invalid" password))
      (respond 200 {:result "FAIL", :error "Invalid email or password"})

      :else
      (let [uid (data/user-id email)]
        (respond 200 {:id         uid
                      :sessionKey (session-token uid)
                      :timezone   "America/New_York"})))))

(defn- status
  [_req]
  (respond 200 {:fake true}))

(defn- reject-next-session
  [req]
  (let [uid (data/user-id (:email (payload req)))]
    (swap! reject-next conj uid)
    (respond 200 {:rejecting uid})))

(defn wrap-enabled
  "404 for every route unless the fake is configured on."
  [handler]
  (fn [ctx]
    (if (connection/fake? ctx)
      (handler ctx)
      {:status  404
       :headers {"content-type" "text/plain"}
       :body    "Not found"})))

(def module
  {:api-routes
   [connection/fake-path {:middleware [wrap-enabled]}
    ["" {:get status}]
    ["/reject-next-session" {:post reject-next-session}]
    ["/api/v2/login" {:post login}]
    ["/api/v2/get_diary"
     {:post (endpoint (fn [uid {:keys [day]}] (data/diary uid (parse-day day))))}]
    ["/api/v2/get_foods"
     {:post (endpoint (fn [uid {:keys [ids]}] (data/foods uid ids)))}]
    ["/api/v2/get_metrics"
     {:post (endpoint (fn [_ _] {:metrics data/metric-catalog}))}]
    ["/api/v2/get_nutrients"
     {:post (endpoint (fn [_ _] (data/nutrient-definitions)))}]
    ["/api/v2/get_nutrition_scores"
     {:post (endpoint (fn [uid {:keys [servingIds]}]
                        (data/nutrition-scores uid servingIds)))}]
    ["/api/v2/get_biometrics"
     {:post (endpoint (fn [uid {:keys [metricId unitId start end]}]
                        (data/biometric-series uid metricId unitId
                                               (parse-day start) (parse-day end))))}]]})
