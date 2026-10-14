(ns tech.jgood.gleanmo.test.e2e-auth-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [malli.core :as malli]
   [tech.jgood.gleanmo :as main]
   [tech.jgood.gleanmo.db.mutations :as mutations]
   [tech.jgood.gleanmo.e2e-auth :as e2e-auth])
  (:import
   [java.time LocalDate]
   [java.util UUID]))

(def parent-ids
  {:location        (UUID/randomUUID)
   :project         (UUID/randomUUID)
   :meditation      (UUID/randomUUID)
   :book            (UUID/randomUUID)
   :habit-a         (UUID/randomUUID)
   :habit-b         (UUID/randomUUID)
   :medication      (UUID/randomUUID)
   :exercise        (UUID/randomUUID)
   :boulder-problem (UUID/randomUUID)})

(deftest timeline-day-specs-test
  (testing "one daily pulse covers every charted activity type with valid docs"
    (let [user-id (UUID/randomUUID)
          date    (LocalDate/of 2026 7 29)
          specs   (#'e2e-auth/timeline-day-specs date parent-ids)]
      (is (= #{:habit-log :meditation-log :bm-log :medication-log :reading-log
               :project-log :symptom-log :mood-log :exercise-session
               :boulder-session :calendar-event}
             (set (map :entity-key specs))))
      (doseq [{:keys [entity-key data]} specs]
        (let [doc (mutations/entity-doc
                   entity-key
                   (merge {:user/id user-id} data))]
          (is (malli/validate entity-key doc main/malli-opts)
              (str "invalid timeline fixture for " entity-key)))))))

(deftest timeline-day-label-test
  (testing "the idempotency marker is stable for a calendar date"
    (is (= "Timeline pulse 2026-07-29"
           (#'e2e-auth/timeline-day-label (LocalDate/of 2026 7 29))))))

(deftest e2e-guard-test
  (let [guarded (fn [token] (e2e-auth/wrap-e2e-guard (constantly {:status 303}) token))
        handler (guarded "secret")
        request (fn [overrides]
                  (merge {:remote-addr "127.0.0.1"
                          :params      {:token "secret"
                                        :email "e2e-test@localhost"}}
                         overrides))
        status  #(:status (handler (request %)))]
    (testing "a local request with the token and a test account passes"
      (is (= 303 (status {})))
      (is (= 303 (status {:remote-addr "0:0:0:0:0:0:0:1"})))
      (is (= 303 (status {:params {:token "secret"}}))))
    (testing "another device on the network is not found"
      (is (= 404 (status {:remote-addr "192.168.1.20"})))
      (is (= 404 (status {:remote-addr "100.101.102.103"}))))
    (testing "a missing or wrong token is refused"
      (is (= 403 (status {:params {:email "e2e-test@localhost"}})))
      (is (= 403 (status {:params {:token "guess" :email "e2e-test@localhost"}}))))
    (testing "a blank expected token matches nothing"
      (is (= 403 (:status ((guarded "") (request {:params {:token ""}}))))))
    (testing "only @localhost accounts can be signed in"
      (is (= 403 (status {:params {:token "secret" :email "me@example.com"}})))
      (is (= 403 (status {:params {:token "secret"
                                   :email "me@localhost.example.com"}}))))))
