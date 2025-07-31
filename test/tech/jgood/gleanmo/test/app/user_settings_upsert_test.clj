(ns tech.jgood.gleanmo.test.app.user-settings-upsert-test
  (:require
    [clojure.test       :refer [deftest is testing]]
    [com.biffweb        :as    biff
                        :refer [test-xtdb-node]]
    [tech.jgood.gleanmo :as main]
    [tech.jgood.gleanmo.app.user :as user]
    [tech.jgood.gleanmo.db.mutations :as mutations]
    [tech.jgood.gleanmo.db.queries :as queries]
    [tick.core          :as t]
    [xtdb.api           :as xt])
  (:import
    [java.util UUID]))

(defn get-context
  [node]
  {:biff.xtdb/node  node,
   :biff/db         (xt/db node),
   :biff/malli-opts #'main/malli-opts})

(deftest upsert-user-settings-test
  (testing "should create new user settings when none exist"
    (with-open [node (test-xtdb-node [])]
      (let [ctx     (get-context node)
            user-id (UUID/randomUUID)]
        (mutations/upsert-user-settings! ctx user-id {:user-settings/show-sensitive true})
        (let [db       (xt/db node)
              settings (queries/get-user-settings db user-id)]
          (is (some? settings))
          (is (= user-id (:user/id settings)))
          (is (true? (:user-settings/show-sensitive settings)))))))

  (testing "should update existing user settings"
    (with-open [node (test-xtdb-node [])]
      (let [ctx     (get-context node)
            user-id (UUID/randomUUID)]
        ;; Create initial settings
        (mutations/upsert-user-settings! ctx user-id {:user-settings/show-sensitive false})
        (Thread/sleep 100) ;; Allow transaction to complete
        (let [db       (xt/db node)
              settings (queries/get-user-settings db user-id)]
          (is (false? (:user-settings/show-sensitive settings))))
        
        ;; Update settings
        (mutations/upsert-user-settings! ctx user-id {:user-settings/show-sensitive true})
        (Thread/sleep 100) ;; Allow transaction to complete
        (let [db       (xt/db node)
              settings (queries/get-user-settings db user-id)]
          (is (true? (:user-settings/show-sensitive settings)))))))

  (testing "should merge with default settings"
    (with-open [node (test-xtdb-node [])]
      (let [ctx     (get-context node)
            user-id (UUID/randomUUID)]
        ;; Create settings without specifying show-sensitive
        (mutations/upsert-user-settings! ctx user-id {})
        (let [db       (xt/db node)
              settings (queries/get-user-settings db user-id)]
          (is (some? settings))
          (is (= user-id (:user/id settings)))
          ;; Should default to false
          (is (false? (:user-settings/show-sensitive settings)))))))

  (testing "should handle multiple users separately"
    (with-open [node (test-xtdb-node [])]
      (let [ctx      (get-context node)
            user-id1 (UUID/randomUUID)
            user-id2 (UUID/randomUUID)]
        ;; Create different settings for each user
        (mutations/upsert-user-settings! ctx user-id1 {:user-settings/show-sensitive true})
        (mutations/upsert-user-settings! ctx user-id2 {:user-settings/show-sensitive false})
        
        (let [db        (xt/db node)
              settings1 (queries/get-user-settings db user-id1)
              settings2 (queries/get-user-settings db user-id2)]
          (is (true? (:user-settings/show-sensitive settings1)))
          (is (false? (:user-settings/show-sensitive settings2))))))))

(deftest turn-off-sensitive-handler-test
  (testing "should set show-sensitive to false"
    (with-open [node (test-xtdb-node [])]
      (let [ctx     (get-context node)
            user-id (UUID/randomUUID)]
        ;; Set up initial state with sensitive mode on
        (mutations/upsert-user-settings! ctx user-id {:user-settings/show-sensitive true})
        (Thread/sleep 100) ;; Allow transaction to complete
        
        ;; Call the turn-off handler with complete context
        (let [handler-ctx (merge ctx {:authorized.user/id user-id
                                      :params {}})]
          (user/turn-off-sensitive! handler-ctx))
        
        ;; Verify settings were updated
        (Thread/sleep 100) ;; Allow transaction to complete
        (let [db       (xt/db node)
              settings (queries/get-user-settings db user-id)]
          (is (false? (:user-settings/show-sensitive settings)))))))

  (testing "should redirect to /app by default"
    (with-open [node (test-xtdb-node [])]
      (let [ctx     (get-context node)
            user-id (UUID/randomUUID)]
        (mutations/upsert-user-settings! ctx user-id {:user-settings/show-sensitive true})
        
        (let [handler-ctx (merge ctx {:authorized.user/id user-id
                                      :params {}})
              response    (user/turn-off-sensitive! handler-ctx)]
          (is (= 303 (:status response)))
          (is (= "/app" (get-in response [:headers "location"])))))))

  (testing "should redirect to custom location if provided"
    (with-open [node (test-xtdb-node [])]
      (let [ctx     (get-context node)
            user-id (UUID/randomUUID)]
        (mutations/upsert-user-settings! ctx user-id {:user-settings/show-sensitive true})
        
        (let [handler-ctx (merge ctx {:authorized.user/id user-id
                                      :params {:redirect "/custom/path"}})
              response    (user/turn-off-sensitive! handler-ctx)]
          (is (= 303 (:status response)))
          (is (= "/custom/path" (get-in response [:headers "location"]))))))))

(deftest middleware-integration-test
  (testing "wrap-user-authz should set authorized.user/id for path-based routes"
    ;; This tests the authorization middleware behavior
    (let [user-id (UUID/randomUUID)
          ctx     {:session {:uid user-id}
                   :path-params {:id (str user-id)}}
          handler (fn [ctx] {:called-with ctx})
          wrapped-handler (tech.jgood.gleanmo.middleware/wrap-user-authz handler)
          result  (wrapped-handler ctx)]
      (is (= user-id (get-in result [:called-with :authorized.user/id])))))

  (testing "wrap-user-authz should block access to other users' resources"
    (let [user-id       (UUID/randomUUID)
          other-user-id (UUID/randomUUID)
          ctx           {:session {:uid user-id}
                         :path-params {:id (str other-user-id)}}
          handler       (fn [ctx] {:called-with ctx})
          wrapped-handler (tech.jgood.gleanmo.middleware/wrap-user-authz handler)
          result        (wrapped-handler ctx)]
      (is (= 403 (:status result))))))