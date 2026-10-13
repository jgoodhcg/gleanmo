(ns tech.jgood.gleanmo.test.cronometer.fake-test
  "The dev-only fake Cronometer API (`dev/tech/jgood/gleanmo/fake_cronometer`):
   its generated data, its contract with the real client, and the gates that
   keep it out of production. Every HTTP call here is routed into the fake's
   ring handler; nothing leaves the process."
  (:require
   [aero.core :as aero]
   [clj-http.client :as http]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [com.biffweb :refer [test-xtdb-node]]
   [malli.core :as m]
   [malli.error :as me]
   [reitit.ring :as ring]
   [tech.jgood.gleanmo :as main]
   [tech.jgood.gleanmo.crypto :as crypto]
   [tech.jgood.gleanmo.cronometer.client :as client]
   [tech.jgood.gleanmo.cronometer.connection :as connection]
   [tech.jgood.gleanmo.cronometer.sync :as sync]
   [tech.jgood.gleanmo.cronometer.transform :as transform]
   [tech.jgood.gleanmo.db.mutations :as mutations]
   [tech.jgood.gleanmo.db.queries :as queries]
   [tech.jgood.gleanmo.fake-cronometer :as fake]
   [tech.jgood.gleanmo.fake-cronometer.data :as data]
   [tech.jgood.gleanmo.schema.meta :as sm]
   [tick.core :as t]
   [xtdb.api :as xt])
  (:import
   [java.time Instant LocalDate]))

(def ^:private email "fake-user@example.com")

(def ^:private uid (data/user-id email))

(def ^:private day (LocalDate/parse "2030-03-10"))

(def ^:private week
  (mapv #(.plusDays ^LocalDate day (long %)) (range 7)))

(def ^:private user-id #uuid "00000000-0000-4000-8000-0000000000fa")

(def ^:private zone "America/New_York")

(def ^:private synced-at (Instant/parse "2030-03-20T12:00:00Z"))

(defn- approx=
  [a b]
  (< (Math/abs (- (double a) (double b))) 1e-6))

(defn- entries
  [diary type-name]
  (filter #(= type-name (:type %)) (:diary diary)))

(defn- foods-for
  "Every food a diary needs, ingredients included, as the sync fetches them."
  [diary]
  (with-redefs [client/get-foods (fn [_ ids] (:foods (data/foods uid ids)))]
    (sync/fetch-foods nil (keep :foodId (transform/servings diary)))))

(defn- docs-for
  [^LocalDate d]
  (let [diary (data/diary uid d)]
    (transform/diary->docs {:user-id        user-id
                            :zone           zone
                            :day            (str d)
                            :diary          diary
                            :foods          (foods-for diary)
                            :metric-catalog data/metric-catalog
                            :synced-at      synced-at})))

;; =============================================================================
;; Generated data
;; =============================================================================

(deftest determinism-test
  (testing "the same account and day give the same diary"
    (is (= (data/diary uid day) (data/diary uid day))))
  (testing "another account sees a different day"
    (is (not= (:diary (data/diary uid day))
              (:diary (data/diary (data/user-id "someone-else@example.com") day)))))
  (testing "emails map to accounts case-insensitively"
    (is (= uid (data/user-id (str/upper-case email))))))

(deftest range-test
  (let [diaries (map #(data/diary uid %) week)
        weights (for [d diaries
                      e (entries d "Biometric")
                      :when (= 1 (:metricId e))]
                  (:amount e))]
    (testing "days in a range differ"
      (is (= 7 (count (distinct weights))))
      (is (< 2 (count (distinct (map #(get-in % [:summary :consumed :total]) diaries))))))
    (testing "ids are unique across the range"
      (let [ids (for [d diaries
                      e (:diary d)]
                  (or (:servingId e) (:biometricId e) (:exerciseId e)))]
        (is (apply distinct? ids))))
    (testing "get_biometrics covers the range inclusively, converting units"
      (let [lbs (:data (data/biometric-series uid 1 2 (first week) (last week)))
            kg  (:data (data/biometric-series uid 1 1 (first week) (last week)))]
        (is (= (map str week) (map :day lbs)))
        (is (= weights (map :value lbs)))
        (is (every? true? (map #(< (Math/abs (- (:value %2) (* (:value %1) 0.45359237))) 0.01)
                               lbs kg)))))
    (testing "systolic comes from the blood pressure composite"
      (let [series (:data (data/biometric-series uid 4 6 day day))]
        (is (= 1 (count series)))
        (is (<= 100 (:value (first series)) 140))))))

(deftest diary-shape-test
  (let [diary      (data/diary uid day)
        biometrics (entries diary "Biometric")
        by-metric  (group-by :metricId biometrics)
        sleep      (first (by-metric 764))]
    (is (boolean? (get-in diary [:info :complete])))
    (is (map? (:template diary)))
    (is (= "Daily Activity (Oura)" (:name (first (entries diary "Exercise")))))
    (testing "over a week, every Exercise shape a real diary carries"
      (let [exercises (mapcat #(entries (data/diary uid %) "Exercise") week)]
        (is (every? #(nil? (:time %))
                    (filter #(str/starts-with? (:name %) "Daily Activity") exercises)))
        (is (some #(and (:time %) (pos? (:minutes %))) exercises) "a timed session")
        (is (some #(and (nil? (:time %)) (= "Google Health" (:source %))) exercises))))
    (is (= 2 (:unitId (first (by-metric 1)))) "weight in lbs")
    (is (= 9 (:unitId (first (by-metric 6)))) "glucose in mg/dL")
    (let [bp (first (by-metric 32524))]
      (is (= 1 (:amount bp)))
      (is (= #{4 5} (set (map :metricId (get-in bp [:meta :composite]))))))
    (is (some? (get-in sleep [:meta :wakeup_time])))
    (is (some #(nil? (:time %)) biometrics) "Oura per-day values carry no time")
    (testing "meals and manual readings come after waking, every day"
      (doseq [d    week
              :let [diary  (data/diary uid d)
                    wakeup (some #(get-in % [:meta :wakeup_time]) (:diary diary))]
              e    (:diary diary)
              :when (and (:time e) (nil? (:source e)))]
        (is (pos? (compare (:time e) wakeup)) (pr-str [(str d) wakeup e]))))
    (testing "a custom recipe with a nested recipe ingredient"
      (let [foods   (foods-for diary)
            recipe? (fn [f] (some #(= "Recipe" (:type %)) (:measures f)))
            by-id   (into {} (map (juxt :id identity)) foods)]
        (is (some (fn [f]
                    (and (recipe? f)
                         (= uid (:owner f))
                         (empty? (:foodTags f))
                         (some #(recipe? (by-id (:foodId %))) (:ingredients f))))
                  foods))))))

(deftest transform-round-trip-test
  (doseq [d week
          :let [diary  (data/diary uid d)
                result (docs-for d)
                totals (transform/macro-totals (:food-logs result))
                summary (get-in diary [:summary :consumed])]]
    (testing (str d)
      (is (empty? (:warnings result)) (pr-str (:warnings result)))
      (testing "summary totals match the summed servings"
        (is (approx= (:total summary) (:energy totals)))
        (is (approx= (:protein_g summary) (:protein totals)))
        (is (approx= (:carbs_g summary) (:carbs totals)))
        (is (approx= (:fat_g summary) (:fat totals)))
        (is (approx= (:fiber_g summary) (:fiber totals))))
      (testing "every document validates"
        (doseq [doc  (concat (:foods result) (:food-logs result)
                             (:biometric-logs result)
                             (:activity-observations result)
                             [(:nutrition-day result)])
                :let [schema (::sm/type doc)
                      stored (assoc doc ::sm/created-at synced-at)]]
          (is (m/validate schema stored main/malli-opts)
              (pr-str [schema (me/humanize (m/explain schema stored main/malli-opts))]))))
      (testing "timeless readings land on the wake time"
        (is (some #(= :wake-time (:biometric-log/timestamp-basis %))
                  (:biometric-logs result)))))))

(deftest nutrition-scores-test
  (let [serving-ids (keep :servingId (entries (data/diary uid day) "Serving"))
        scores      (into {} (map (juxt :id :amount))
                          (:scores (data/nutrition-scores uid serving-ids)))]
    (is (approx= (get-in (data/diary uid day) [:summary :consumed :total])
                 (get scores 208)))))

;; =============================================================================
;; HTTP: the real client against the fake
;; =============================================================================

(def ^:private host "http://fake.test")

(def ^:private handler
  (ring/ring-handler (ring/router [(:api-routes fake/module)])))

(defn- call
  "Run a request through the fake's routes with the flag set to `enabled?`."
  [enabled? request]
  (or (handler (assoc request :gleanmo.cronometer/fake enabled?))
      {:status 404}))

(defn- via-fake
  "A stand-in for `clj-http.client/post` that answers from the fake and
   records every URL."
  [urls]
  (fn [url {:keys [headers body]}]
    (swap! urls conj url)
    (is (str/starts-with? url (str host connection/fake-path)))
    (call true {:request-method :post
                :uri            (subs url (count host))
                :headers        headers
                :body           body})))

(def ^:private test-key (crypto/generate-key))

(defn- ctx
  [node & {:keys [fake?] :or {fake? true}}]
  {:biff.xtdb/node          node
   :biff/db                 (xt/db node)
   :biff/base-url           host
   :biff/malli-opts         #'main/malli-opts
   :biff/secret             (fn [k] (when (= k :gleanmo/credentials-key) test-key))
   :gleanmo.cronometer/fake fake?})

(defn- login-count
  [urls]
  (count (filter #(str/ends-with? % "/api/v2/login") urls)))

(deftest gate-test
  (testing "with the flag off every route is absent"
    (is (= 404 (:status (call false {:request-method :get, :uri "/dev/cronometer"}))))
    (is (= 404 (:status (call false {:request-method :post
                                     :uri            "/dev/cronometer/api/v2/login"
                                     :body           "{\"email\":\"a@b.c\"}"})))))
  (testing "with it on they answer"
    (is (= 200 (:status (call true {:request-method :get, :uri "/dev/cronometer"})))))
  (testing "the prod profile forces the flag off, whatever the env says"
    (let [config (fn [profile env]
                   (:gleanmo.cronometer/fake
                    (aero/read-config (io/resource "config.edn")
                                      {:profile profile, :biff.aero/env env})))]
      (is (false? (config :prod {"FAKE_CRONOMETER" "true"})))
      (is (true? (config :dev {"FAKE_CRONOMETER" "true"})))
      (is (false? (config :dev {})))))
  (testing "dev/ is not on the uberjar's classpath"
    (is (not-any? #{"dev"} (:paths (edn/read-string (slurp "deps.edn")))))))

(deftest client-contract-test
  (let [urls (atom [])
        conn (client/connection {:id       ::contract
                                 :email    email
                                 :password "anything"
                                 :base-url (str host connection/fake-path)})]
    (client/forget-session! ::contract)
    (with-redefs [http/post (via-fake urls)]
      (testing "every endpoint answers in the client's shapes"
        (is (= (data/diary uid day) (client/get-diary conn day)))
        (is (seq (client/get-foods conn [910001 920002])))
        (is (= data/metric-catalog (client/get-metrics conn)))
        (is (< 90 (count (client/get-nutrient-definitions conn day))))
        (is (seq (:scores (client/get-nutrition-scores
                           conn (keep :servingId (entries (data/diary uid day) "Serving"))))))
        (is (= 3 (count (:data (client/get-biometrics conn 1 2 day (.plusDays day 2))))))
        (is (= 1 (login-count @urls)) "the session is reused"))
      (testing "a rejected session logs in once more and the call succeeds"
        (is (= 200 (:status (call true {:request-method :post
                                        :uri            "/dev/cronometer/reject-next-session"
                                        :body           (str "{\"email\":\"" email "\"}")}))))
        (is (map? (client/get-diary conn day)))
        (is (= 2 (login-count @urls))))
      (testing "a bad password fails login"
        (is (thrown-with-msg? Exception #"login failed"
                              (client/login! (assoc conn :password "invalid"))))))
    (client/forget-session! ::contract)))

(deftest sync-against-fake-test
  (with-open [node (test-xtdb-node [])]
    (mutations/create-entity! (ctx node) {:entity-key :user
                                          :data {:xt/id          user-id
                                                 :user/email     "u@example.com"
                                                 :user/joined-at (t/now)
                                                 :user/time-zone zone}})
    (connection/connect! (ctx node) user-id email "any password")
    (let [user  {:xt/id user-id, :user/time-zone zone}
          days  (take 3 week)
          urls  (atom [])
          run!  #(binding [sync/*day-pause-ms* 0]
                   (with-redefs [http/post (via-fake urls)]
                     (sync/sync-days! (ctx node) user days)))
          first-run  (run!)
          second-run (run!)
          day-strs   (map str days)]
      (testing "the sync targets the fake and writes the days"
        (is (nil? (:error first-run)) (:error first-run))
        (is (= 3 (count (:days first-run))))
        (is (every? #(pos? (:created %)) (:days first-run)))
        (is (= (reduce + (map #(count (entries (data/diary uid %) "Serving")) days))
               (count (queries/cronometer-docs-for-days (xt/db node) user-id
                                                        :food-log day-strs)))))
      (testing "Exercise entries become activity observations"
        (let [obs     (queries/cronometer-docs-for-days (xt/db node) user-id
                                                        :activity-observation day-strs)
              by-scope (group-by :activity-observation/scope obs)]
          (is (= (reduce + (map #(count (entries (data/diary uid %) "Exercise")) days))
                 (count obs)))
          (is (= 3 (count (:daily by-scope))))
          (is (seq (:session by-scope)))
          (is (every? #(and (:activity-observation/beginning %)
                            (.isBefore ^java.time.Instant (:activity-observation/beginning %)
                                       (:activity-observation/end %))
                            (:activity-observation/auto-ended %))
                      (:session by-scope)))))
      (testing "a re-sync of unchanged days writes nothing"
        (is (every? #(and (zero? (:created %)) (zero? (:updated %)) (zero? (:deleted %)))
                    (:days second-run))))
      (testing "nothing went anywhere but the fake"
        (is (every? #(str/starts-with? % (str host connection/fake-path)) @urls))))
    (client/forget-session! user-id)))

(deftest base-url-test
  (with-open [node (test-xtdb-node [])]
    (connection/connect! (ctx node) user-id email "pw")
    (testing "flag on: this server's fake"
      (is (= (str host "/dev/cronometer")
             (:base-url (connection/client-connection (ctx node) user-id)))))
    (testing "flag off: Cronometer itself"
      (is (= client/default-base-url
             (:base-url (connection/client-connection (ctx node :fake? false) user-id)))))))
