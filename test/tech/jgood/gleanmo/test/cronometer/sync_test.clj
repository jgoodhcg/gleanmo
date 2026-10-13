(ns tech.jgood.gleanmo.test.cronometer.sync-test
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [com.biffweb :refer [test-xtdb-node]]
   [tech.jgood.gleanmo :as main]
   [tech.jgood.gleanmo.crypto :as crypto]
   [tech.jgood.gleanmo.cronometer.client :as client]
   [tech.jgood.gleanmo.cronometer.connection :as connection]
   [tech.jgood.gleanmo.cronometer.sync :as sync]
   [tech.jgood.gleanmo.cronometer.transform :as transform]
   [tech.jgood.gleanmo.db.mutations :as mutations]
   [tech.jgood.gleanmo.db.queries :as queries]
   [tech.jgood.gleanmo.schema.meta :as sm]
   [tech.jgood.gleanmo.test.cronometer.fixtures :as fx]
   [tech.jgood.gleanmo.worker :as worker]
   [tick.core :as t]
   [xtdb.api :as xt])
  (:import
   [java.time Instant LocalDate]))

(def ^:private test-key (crypto/generate-key))

(defn- ctx
  [node & {:keys [credentials-key] :or {credentials-key test-key}}]
  {:biff.xtdb/node  node
   :biff/db         (xt/db node)
   :biff/malli-opts #'main/malli-opts
   :biff/secret     (fn [k] (when (= k :gleanmo/credentials-key) credentials-key))})

(def ^:private user {:xt/id fx/user-id, :user/time-zone fx/zone})

(def ^:private day (LocalDate/parse fx/day))

(defn- run-sync!
  "Sync the fixture day against a stubbed client serving `diary` and `foods`.
   Returns the result and every food id `get-foods` was asked for."
  [node diary foods]
  (let [asked (atom [])]
    (binding [sync/*day-pause-ms* 0]
      (with-redefs [client/get-metrics (fn [_] fx/metric-catalog)
                    client/get-diary   (fn [_ _] diary)
                    client/get-foods   (fn [_ ids]
                                         (swap! asked into ids)
                                         (filterv #((set ids) (:id %)) foods))]
        {:result (sync/sync-days! (ctx node) user [day])
         :asked  @asked}))))

(defn- doc
  [node kind k]
  (xt/entity (xt/db node) (transform/doc-id fx/user-id kind k)))

(defn- history-count
  [node kind k]
  (count (xt/entity-history (xt/db node) (transform/doc-id fx/user-id kind k) :asc)))

(defn- connect!
  [node]
  (connection/connect! (ctx node) fx/user-id "someone@example.com" "hunter2"))

;; -----------------------------------------------------------------------------

(deftest crypto-test
  (let [k (crypto/parse-key test-key)]
    (testing "round trip"
      (is (= "secret" (crypto/decrypt k (crypto/encrypt k "secret" "aad") "aad"))))
    (testing "a wrong key or wrong associated data fails"
      (let [enc (crypto/encrypt k "secret" "aad")]
        (is (thrown? Exception
                     (crypto/decrypt (crypto/parse-key (crypto/generate-key)) enc "aad")))
        (is (thrown? Exception (crypto/decrypt k enc "other")))))
    (testing "every encryption uses a fresh nonce"
      (is (= 50 (count (set (repeatedly 50 #(:nonce (crypto/encrypt k "x" "a"))))))))
    (testing "malformed keys read as not configured"
      (is (nil? (crypto/parse-key nil)))
      (is (nil? (crypto/parse-key "")))
      (is (nil? (crypto/parse-key "not base64!")))
      (is (nil? (crypto/parse-key "c2hvcnQ="))))))

(deftest connection-test
  (with-open [node (test-xtdb-node [])]
    (testing "the stored document holds no plaintext"
      (connect! node)
      (let [stored (queries/cronometer-connection-for-user (xt/db node) fx/user-id)]
        (is (some? stored))
        (is (not (str/includes? (pr-str stored) "someone@example.com")))
        (is (not (str/includes? (pr-str stored) "hunter2")))
        (is (= {:email "someone@example.com", :password "hunter2"}
               (connection/credentials (ctx node) stored)))))
    (testing "a different key cannot read it"
      (let [stored (queries/cronometer-connection-for-user (xt/db node) fx/user-id)]
        (is (thrown? Exception
                     (connection/credentials (ctx node :credentials-key (crypto/generate-key))
                                             stored)))))
    (testing "reconnecting evicts the old connection"
      (let [old-id (:xt/id (queries/cronometer-connection-for-user (xt/db node) fx/user-id))]
        (connection/connect! (ctx node) fx/user-id "someone@example.com" "changed")
        (is (empty? (xt/entity-history (xt/db node) old-id :asc)))
        (is (= "changed" (:password (connection/credentials
                                     (ctx node)
                                     (queries/cronometer-connection-for-user
                                      (xt/db node) fx/user-id)))))))
    (testing "the user is listed as connected"
      (mutations/create-entity! (ctx node) {:entity-key :user
                                            :data {:xt/id          fx/user-id
                                                   :user/email     "u@example.com"
                                                   :user/joined-at (t/now)}})
      (is (= [fx/user-id]
             (map :xt/id (queries/users-with-cronometer-connection (xt/db node))))))
    (testing "disconnecting leaves no document history"
      (let [id (:xt/id (queries/cronometer-connection-for-user (xt/db node) fx/user-id))]
        (connection/disconnect! (ctx node) fx/user-id)
        (is (nil? (queries/cronometer-connection-for-user (xt/db node) fx/user-id)))
        (is (empty? (xt/entity-history (xt/db node) id :asc)))
        (is (empty? (queries/users-with-cronometer-connection (xt/db node))))))
    (testing "without a key, connecting is refused"
      (is (thrown? Exception
                   (connection/connect! (ctx node :credentials-key nil)
                                        fx/user-id "a@b.c" "pw"))))))

(deftest sync-test
  (with-open [node (test-xtdb-node [])]
    (connect! node)
    (testing "the first run creates every document"
      (let [{:keys [result asked]} (run-sync! node fx/diary fx/foods)
            [summary] (:days result)]
        (is (nil? (:error result)) (pr-str result))
        (is (= fx/day (:day summary)))
        ;; 3 foods + 3 food-logs + 8 biometric-logs + 3 activity-observations
        ;; + 1 nutrition-day
        (is (= 18 (:created summary)))
        (testing "and fetches recipe ingredients that no serving names"
          (is (= #{101 102 201} (set asked))))))

    (testing "recipe ingredients resolve to food docs with tags"
      (let [recipe (doc node :food 201)
            ingredient (xt/entity (xt/db node)
                                  (:food-id (first (:food/ingredients recipe))))]
        (is (= #{"Vegan" "Whole"} (:food/tags ingredient)))))

    (testing "blood pressure lands as one row with both values"
      (let [bp (doc node :biometric-log 7002)]
        (is (= [118 76] [(:biometric-log/bp-systolic bp) (:biometric-log/bp-diastolic bp)]))))

    (testing "a second identical run writes nothing new"
      (let [before (history-count node :food-log 5001)
            {:keys [result]} (run-sync! node fx/diary fx/foods)
            [summary] (:days result)]
        (is (= 0 (:created summary)))
        (is (= 0 (:deleted summary)))
        (is (= 0 (:updated summary)))
        (is (= 18 (:unchanged summary)))
        (testing "except the nutrition-day, which is rewritten to mark the sync"
          (is (= 2 (history-count node :nutrition-day fx/day))))
        (is (= before (history-count node :food-log 5001)))))

    (testing "a serving removed in Cronometer is soft-deleted"
      (let [diary (update fx/diary :diary
                          (fn [entries] (vec (remove #(= 5002 (:servingId %)) entries))))
            {:keys [result]} (run-sync! node diary fx/foods)]
        (is (= 1 (:deleted (first (:days result)))))
        (is (some? (::sm/deleted-at (doc node :food-log 5002))))
        (is (nil? (::sm/deleted-at (doc node :food-log 5001))))
        (is (= #{(transform/doc-id fx/user-id :food-log 5001)
                 (transform/doc-id fx/user-id :food-log 5003)}
               (queries/cronometer-ids-for-day (xt/db node) fx/user-id :food-log fx/day)))))

    (testing "a changed recipe updates its food doc and drops removed fields"
      (let [porridge (-> fx/porridge
                         (assoc :name "Test Porridge v2")
                         (update :ingredients #(vec (take 1 %))))
            foods    [fx/oats fx/milk porridge]
            created  (::sm/created-at (doc node :food 201))
            {:keys [result]} (run-sync! node fx/diary foods)]
        (is (nil? (:error result)))
        (let [stored (doc node :food 201)]
          (is (= "Test Porridge v2" (:food/label stored)))
          (is (= 1 (count (:food/ingredients stored))))
          (is (= created (::sm/created-at stored))))
        (testing "and the serving that reappeared is restored"
          (is (nil? (::sm/deleted-at (doc node :food-log 5002)))))))

    (testing "a field removed upstream does not linger"
      (let [diary (update fx/diary :diary
                          (fn [entries]
                            (mapv #(if (= 7003 (:biometricId %))
                                     (dissoc % :time :offset)
                                     %)
                                  entries)))]
        (run-sync! node diary fx/foods)
        (let [glucose (doc node :biometric-log 7003)]
          (is (nil? (:cronometer/time glucose)))
          (is (not (contains? glucose :cronometer/offset)))
          (is (= :wake-time (:biometric-log/timestamp-basis glucose))))))

    (testing "a re-sync keeps a user-set exercise session link"
      (let [session-id (random-uuid)
            obs-id     (transform/doc-id fx/user-id :activity-observation 9002)]
        (mutations/update-entity! (ctx node)
                                  {:entity-key :activity-observation
                                   :entity-id  obs-id
                                   :data       {:activity-observation/exercise-session-id
                                                session-id}})
        (let [before (history-count node :activity-observation 9002)
              {:keys [result]} (run-sync! node fx/diary fx/foods)]
          (is (nil? (:error result)))
          (is (= session-id (:activity-observation/exercise-session-id
                             (doc node :activity-observation 9002))))
          (testing "without rewriting the observation"
            (is (= before (history-count node :activity-observation 9002)))))
        (testing "and an upstream change keeps it too"
          (let [diary (update fx/diary :diary
                              (fn [entries]
                                (mapv #(if (= 9002 (:exerciseId %))
                                         (assoc % :minutes 45)
                                         %)
                                      entries)))]
            (run-sync! node diary fx/foods)
            (let [walk (doc node :activity-observation 9002)]
              (is (= session-id (:activity-observation/exercise-session-id walk)))
              (is (= (Instant/parse "2030-01-15T15:30:00Z")
                     (:activity-observation/end walk))))))))

    (testing "an exercise removed in Cronometer is soft-deleted"
      (let [diary (update fx/diary :diary
                          (fn [entries] (vec (remove #(= 9003 (:exerciseId %)) entries))))
            {:keys [result]} (run-sync! node diary fx/foods)]
        (is (= 1 (:deleted (first (:days result)))))
        (is (some? (::sm/deleted-at (doc node :activity-observation 9003))))))

    (testing "the last sync time is the latest nutrition-day sync"
      (is (some? (queries/last-cronometer-sync (xt/db node) fx/user-id))))))

(deftest sync-failures-test
  (with-open [node (test-xtdb-node [])]
    (testing "without a connection the sync is a no-op"
      (is (:not-connected (sync/sync-days! (ctx node) user [day]))))
    (testing "a failing day stops the run and is reported"
      (connect! node)
      (binding [sync/*day-pause-ms* 0]
        (with-redefs [client/get-metrics (fn [_] fx/metric-catalog)
                      client/get-diary   (fn [_ _] (throw (ex-info "boom" {})))]
          (let [result (sync/sync-days! (ctx node) user [day])]
            (is (= "boom" (:error result)))
            (is (= fx/day (:failed-day result)))))))))

(deftest scheduled-sync-test
  (with-open [node (test-xtdb-node [])]
    (let [failing (random-uuid)
          working (random-uuid)]
      (doseq [[id email] [[failing "fails@example.com"] [working "works@example.com"]]]
        (mutations/create-entity! (ctx node) {:entity-key :user
                                              :data {:xt/id          id
                                                     :user/email     email
                                                     :user/joined-at (t/now)
                                                     :user/time-zone fx/zone}})
        (connection/connect! (ctx node) id email "pw"))
      (binding [sync/*day-pause-ms* 0]
        (with-redefs [worker/cronometer-user-pause-ms 0
                      client/get-metrics (fn [conn]
                                           (if (= "fails@example.com" (:email conn))
                                             (throw (ex-info "login failed" {}))
                                             fx/metric-catalog))
                      client/get-diary   (fn [_ _] fx/diary)
                      client/get-foods   (fn [_ ids] (filterv #((set ids) (:id %)) fx/foods))]
          (testing "without a key the task does nothing"
            (worker/sync-cronometer (ctx node :credentials-key nil))
            (is (zero? (queries/count-entities-for-user (xt/db node) working :food))))
          (testing "one user's failure does not stop the others"
            (worker/sync-cronometer (ctx node))
            (is (zero? (queries/count-entities-for-user (xt/db node) failing :food)))
            (is (= 3 (queries/count-entities-for-user (xt/db node) working :food)))))))))
