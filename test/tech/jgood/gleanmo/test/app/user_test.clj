(ns tech.jgood.gleanmo.test.app.user-test
  (:require
   [clojure.test       :refer [deftest is testing]]
   [com.biffweb        :as    biff
    :refer [test-xtdb-node]]
   [tech.jgood.gleanmo :as main]
   [tech.jgood.gleanmo.app.user :as user]
   [tech.jgood.gleanmo.db.mutations :as mutations]
   [tech.jgood.gleanmo.db.queries :as queries]
   [tick.core :as t]
   [xtdb.api           :as xt])
  (:import
   [java.util UUID]))

(defn get-context
  [node]
  {:biff.xtdb/node  node,
   :biff/db         (xt/db node),
   :biff/malli-opts #'main/malli-opts})

(deftest edit!-test
  (testing "user edit! function with consolidated fields"
    (with-open [node (test-xtdb-node [])]
      (let [ctx     (get-context node)
            user-id (UUID/randomUUID)]

        ;; Create initial user
        (mutations/create-entity!
         ctx
         {:entity-key :user,
          :data       {:user/email     "initial@example.com"
                       :user/joined-at (t/now)
                       :xt/id          user-id}})

        (testing "should update all user fields including sensitive and archived settings"
          (let [edit-ctx (assoc ctx
                                :params {:email "updated@example.com"
                                         :time-zone "America/New_York"
                                         :show-sensitive "true"
                                         :show-archived "on"}
                                :authorized.user/id user-id)
                result   (user/edit! edit-ctx)
                updated-db (xt/db node)
                updated-user (queries/get-entity-by-id updated-db user-id)]

            ;; Check response
            (is (= 303 (:status result)))
            (is (= (str "/app/users/" user-id "/edit")
                   (get-in result [:headers "location"])))

            ;; Check user was updated with all fields
            (is (= "updated@example.com" (:user/email updated-user)))
            (is (= "America/New_York" (:user/time-zone updated-user)))
            (is (true? (:user/show-sensitive updated-user)))
            (is (true? (:user/show-archived updated-user)))))

        (testing "should handle false values for boolean settings"
          (let [edit-ctx (assoc ctx
                                :params {:email "test@example.com"
                                         :time-zone "UTC"
                                    ;; show-sensitive and show-archived not present = false
                                         }
                                :authorized.user/id user-id)
                result   (user/edit! edit-ctx)
                updated-db (xt/db node)
                updated-user (queries/get-entity-by-id updated-db user-id)]

            ;; Check response
            (is (= 303 (:status result)))

            ;; Check boolean fields are false when not provided
            (is (false? (:user/show-sensitive updated-user)))
            (is (false? (:user/show-archived updated-user)))))

        (testing "should handle partial updates"
          (let [edit-ctx (assoc ctx
                                :params {:email "partial@example.com"
                                         :time-zone "Europe/London"
                                         :show-sensitive "true"
                                    ;; show-archived not provided
                                         }
                                :authorized.user/id user-id)
                result   (user/edit! edit-ctx)
                updated-db (xt/db node)
                updated-user (queries/get-entity-by-id updated-db user-id)]

            ;; Check mixed true/false
            (is (= "partial@example.com" (:user/email updated-user)))
            (is (= "Europe/London" (:user/time-zone updated-user)))
            (is (true? (:user/show-sensitive updated-user)))
            (is (false? (:user/show-archived updated-user)))))))))

(deftest turn-off-sensitive!-test
  (testing "turn-off-sensitive! function"
    (with-open [node (test-xtdb-node [])]
      (let [ctx     (get-context node)
            user-id (UUID/randomUUID)]

        ;; Create user with sensitive setting enabled
        (mutations/create-entity!
         ctx
         {:entity-key :user,
          :data       {:user/email          "test@example.com"
                       :user/joined-at      (t/now)
                       :user/show-sensitive true
                       :xt/id               user-id}})

        (testing "should turn off sensitive setting"
          (let [turn-off-ctx (assoc ctx :authorized.user/id user-id)
                result       (user/turn-off-sensitive! turn-off-ctx)
                updated-db   (xt/db node)
                updated-user (queries/get-entity-by-id updated-db user-id)]

            ;; Check response
            (is (= 303 (:status result)))
            (is (= "/app" (get-in result [:headers "location"])))

            ;; Check sensitive setting is now false
            (is (false? (:user/show-sensitive updated-user)))

            ;; Check other fields are unchanged
            (is (= "test@example.com" (:user/email updated-user)))))

        (testing "should handle custom redirect parameter"
          (let [turn-off-ctx (assoc ctx
                                    :authorized.user/id user-id
                                    :params {:redirect "/custom/redirect"})
                result       (user/turn-off-sensitive! turn-off-ctx)]

            ;; Check custom redirect
            (is (= 303 (:status result)))
            (is (= "/custom/redirect" (get-in result [:headers "location"])))))

        (testing "should work when sensitive is already false"
          ;; First turn it off
          (user/turn-off-sensitive! (assoc ctx :authorized.user/id user-id))

          ;; Then turn it off again
          (let [result       (user/turn-off-sensitive! (assoc ctx :authorized.user/id user-id))
                updated-db   (xt/db node)
                updated-user (queries/get-entity-by-id updated-db user-id)]

            ;; Should still work fine
            (is (= 303 (:status result)))
            (is (false? (:user/show-sensitive updated-user)))))))))

(deftest update-user!-integration-test
  (testing "update-user! function with new fields"
    (with-open [node (test-xtdb-node [])]
      (let [ctx     (get-context node)
            user-id (UUID/randomUUID)]

        ;; Create initial user
        (mutations/create-entity!
         ctx
         {:entity-key :user,
          :data       {:user/email     "initial@example.com"
                       :user/joined-at (t/now)
                       :xt/id          user-id}})

        (testing "should update user with sensitive and archived fields"
          (let [result     (mutations/update-user!
                            ctx
                            user-id
                            {:user/email          "updated@example.com"
                             :user/time-zone      "America/Los_Angeles"
                             :user/show-sensitive true
                             :user/show-archived  false})
                updated-db (xt/db node)
                updated-user (queries/get-entity-by-id updated-db user-id)]

            ;; Check function return
            (is (= user-id result))

            ;; Check all fields were updated
            (is (= "updated@example.com" (:user/email updated-user)))
            (is (= "America/Los_Angeles" (:user/time-zone updated-user)))
            (is (true? (:user/show-sensitive updated-user)))
            (is (false? (:user/show-archived updated-user)))))

        (testing "should handle missing optional fields gracefully"
          ;; Create a fresh user for this test to avoid interference from previous tests
          (let [fresh-user-id (UUID/randomUUID)
                _             (mutations/create-entity!
                               ctx
                               {:entity-key :user,
                                :data       {:user/email     "fresh@example.com"
                                             :user/joined-at (t/now)
                                             :xt/id          fresh-user-id}})
                result        (mutations/update-user!
                               ctx
                               fresh-user-id
                               {:user/email "updated@example.com"})
                updated-db    (xt/db node)
                updated-user  (queries/get-entity-by-id updated-db fresh-user-id)]

            ;; Optional fields should not be present if not provided
            (is (not (contains? updated-user :user/show-sensitive)))
            (is (not (contains? updated-user :user/show-archived)))

            ;; But the query functions should still return false for missing fields
            (let [user-settings (queries/get-user-settings updated-db fresh-user-id)]
              (is (false? (:show-sensitive user-settings)))
              (is (false? (:show-archived user-settings)))))))))

  (deftest turn-off-archived!-test
    (testing "turn-off-archived! function"
      (with-open [node (test-xtdb-node [])]
        (let [ctx     (get-context node)
              user-id (UUID/randomUUID)]

        ;; Create user with archived setting enabled
          (mutations/create-entity!
           ctx
           {:entity-key :user,
            :data       {:user/email         "test@example.com"
                         :user/joined-at     (t/now)
                         :user/show-archived true
                         :xt/id              user-id}})

          (testing "should turn off archived setting"
            (let [turn-off-ctx (assoc ctx :authorized.user/id user-id)
                  result       (user/turn-off-archived! turn-off-ctx)
                  updated-db   (xt/db node)
                  updated-user (queries/get-entity-by-id updated-db user-id)]

            ;; Check response
              (is (= 303 (:status result)))
              (is (= "/app" (get-in result [:headers "location"])))

            ;; Check archived setting is now false
              (is (false? (:user/show-archived updated-user)))

            ;; Check other fields are unchanged
              (is (= "test@example.com" (:user/email updated-user)))))

          (testing "should handle custom redirect parameter"
            (let [turn-off-ctx (assoc ctx
                                      :authorized.user/id user-id
                                      :params {:redirect "/custom/page"})
                  result       (user/turn-off-archived! turn-off-ctx)]

            ;; Check custom redirect
              (is (= 303 (:status result)))
              (is (= "/custom/page" (get-in result [:headers "location"])))))

          (testing "should work when archived is already false"
          ;; First turn it off
            (user/turn-off-archived! (assoc ctx :authorized.user/id user-id))

          ;; Then turn it off again
            (let [result       (user/turn-off-archived! (assoc ctx :authorized.user/id user-id))
                  updated-db   (xt/db node)
                  updated-user (queries/get-entity-by-id updated-db user-id)]

            ;; Should still work fine
              (is (= 303 (:status result)))
              (is (false? (:user/show-archived updated-user))))))))))