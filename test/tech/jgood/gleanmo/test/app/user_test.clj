(ns tech.jgood.gleanmo.test.app.user-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [com.biffweb :as biff
    :refer [test-xtdb-node]]
   [tech.jgood.gleanmo :as main]
   [tech.jgood.gleanmo.app.user :as user]
   [tech.jgood.gleanmo.db.mutations :as mutations]
   [tech.jgood.gleanmo.db.queries :as queries]
   [tick.core :as t]
   [xtdb.api :as xt])
  (:import
   [java.util UUID]))

(defn get-context
  [node]
  {:biff.xtdb/node node,
   :biff/db (xt/db node),
   :biff/malli-opts #'main/malli-opts})

(defn- create-user!
  [ctx email]
  (let [user-id (UUID/randomUUID)]
    (mutations/create-entity!
     ctx
     {:entity-key :user,
      :data {:user/email email
             :user/joined-at (t/now)
             :xt/id user-id}})
    user-id))

(defn- stored-user
  [node user-id]
  (queries/get-entity-by-id (xt/db node) user-id))

(defn- status-of
  [response]
  (second (re-find #"email-status=([a-z-]+)"
                   (get-in response [:headers "location"]))))

(defn- capturing-send-email
  "A :biff/send-email stub that records each email it is asked to send."
  [sent]
  (fn [_ctx opts]
    (swap! sent conj opts)
    true))

(deftest edit!-test
  (testing "user edit! function with consolidated fields"
    (with-open [node (test-xtdb-node [])]
      (let [ctx (get-context node)
            user-id (create-user! ctx "initial@example.com")
            edit! (fn [params]
                    (user/edit! (assoc (get-context node)
                                       :params params
                                       :authorized.user/id user-id)))]

        (testing "an unchanged email saves the other fields directly"
          (let [result (edit! {:email "initial@example.com"
                               :time-zone "America/New_York"
                               :show-sensitive "true"
                               :show-archived "on"})
                updated-user (stored-user node user-id)]
            (is (= 303 (:status result)))
            (is (= (str "/app/users/" user-id "/edit")
                   (get-in result [:headers "location"])))
            (is (= "initial@example.com" (:user/email updated-user)))
            (is (= "America/New_York" (:user/time-zone updated-user)))
            (is (true? (:user/show-sensitive updated-user)))
            (is (true? (:user/show-archived updated-user)))
            (is (not (contains? updated-user :user/pending-email)))))

        (testing "case and whitespace differences count as unchanged"
          (let [result (edit! {:email "  Initial@Example.com "
                               :time-zone "UTC"})
                updated-user (stored-user node user-id)]
            (is (nil? (status-of result)))
            (is (= "initial@example.com" (:user/email updated-user)))
            (is (not (contains? updated-user :user/pending-email)))))

        (testing "should handle false values for boolean settings"
          (edit! {:email "initial@example.com" :time-zone "UTC"})
          (let [updated-user (stored-user node user-id)]
            (is (false? (:user/show-sensitive updated-user)))
            (is (false? (:user/show-archived updated-user)))))))))

(deftest email-change-verification-test
  (with-open [node (test-xtdb-node [])]
    (let [sent    (atom [])
          ctx     #(assoc (get-context node)
                          :biff/send-email (capturing-send-email sent))
          user-id (create-user! (ctx) "owner@example.com")
          _       (create-user! (ctx) "other@example.com")
          edit!   (fn [params]
                    (user/edit! (assoc (ctx)
                                       :params (merge {:time-zone "UTC"} params)
                                       :authorized.user/id user-id)))
          verify! (fn [code]
                    (user/verify-email! (assoc (ctx)
                                               :params {:code code}
                                               :authorized.user/id user-id)))
          last-code #(:code (peek @sent))]

      (testing "a changed email is not saved before it is verified"
        (let [result (edit! {:email "New@Example.com"
                             :time-zone "Europe/London"})
              stored (stored-user node user-id)]
          (is (= "code-sent" (status-of result)))
          (is (= "owner@example.com" (:user/email stored)))
          (is (= "new@example.com" (:user/pending-email stored)))
          (is (= "Europe/London" (:user/time-zone stored))
              "other fields still save immediately")
          (is (= {:template :email-change-code :to "new@example.com"}
                 (select-keys (peek @sent) [:template :to]))
              "the code goes to the new address")
          (is (re-matches #"\d{6}" (last-code)))
          (is (not= (last-code) (:user/pending-email-code-hash stored))
              "the code is not stored in the clear")))

      (testing "a wrong code is refused and counted"
        (is (= "wrong-code" (status-of (verify! "not-it"))))
        (let [stored (stored-user node user-id)]
          (is (= "owner@example.com" (:user/email stored)))
          (is (= 1 (:user/pending-email-failed-attempts stored)))))

      (testing "the right code sets the email and clears pending state"
        (is (= "changed" (status-of (verify! (last-code)))))
        (let [stored (stored-user node user-id)]
          (is (= "new@example.com" (:user/email stored)))
          (is (empty? (select-keys stored
                                   [:user/pending-email
                                    :user/pending-email-code-hash
                                    :user/pending-email-timestamp
                                    :user/pending-email-failed-attempts])))))

      (testing "a used code cannot be replayed"
        (is (= "no-pending" (status-of (verify! (last-code))))))

      (testing "three wrong codes end the attempt"
        (edit! {:email "third@example.com"})
        (let [code (last-code)]
          (is (= "wrong-code" (status-of (verify! "000000x"))))
          (is (= "wrong-code" (status-of (verify! "000000y"))))
          (is (= "too-many-attempts" (status-of (verify! "000000z"))))
          (is (= "no-pending" (status-of (verify! code)))
              "the right code no longer works")
          (is (= "new@example.com" (:user/email (stored-user node user-id))))))

      (testing "an expired code is refused"
        (edit! {:email "late@example.com"})
        (mutations/update-user! (ctx) user-id
                                {:user/pending-email-timestamp
                                 (t/<< (t/now) (t/new-duration 16 :minutes))})
        (is (= "expired" (status-of (verify! (last-code)))))
        (let [stored (stored-user node user-id)]
          (is (= "new@example.com" (:user/email stored)))
          (is (not (contains? stored :user/pending-email)))))

      (testing "an address another user has is refused, ignoring case"
        (let [n (count @sent)]
          (is (= "taken" (status-of (edit! {:email "OTHER@example.com"}))))
          (is (= n (count @sent)) "no code is sent")
          (is (not (contains? (stored-user node user-id)
                              :user/pending-email)))))

      (testing "an address taken after the code was sent is refused on verify"
        (edit! {:email "race@example.com"})
        (create-user! (ctx) "race@example.com")
        (is (= "taken" (status-of (verify! (last-code)))))
        (let [stored (stored-user node user-id)]
          (is (= "new@example.com" (:user/email stored)))
          (is (not (contains? stored :user/pending-email)))))

      (testing "an invalid address is refused"
        (is (= "invalid" (status-of (edit! {:email "not an email"})))))

      (testing "canceling drops the pending change"
        (edit! {:email "maybe@example.com"})
        (user/cancel-email-change! (assoc (ctx) :authorized.user/id user-id))
        (is (not (contains? (stored-user node user-id) :user/pending-email)))
        (is (= "new@example.com" (:user/email (stored-user node user-id))))))))

(deftest set-user-email!-unique-at-commit-test
  (with-open [node (test-xtdb-node [])]
    (let [ctx     (get-context node)
          user-id (create-user! ctx "mine@example.com")]
      (create-user! ctx "theirs@example.com")
      (is (false? (mutations/set-user-email! (get-context node) user-id
                                             "theirs@example.com" {})))
      (is (= "mine@example.com" (:user/email (stored-user node user-id))))
      (is (true? (mutations/set-user-email! (get-context node) user-id
                                            "fresh@example.com" {})))
      (is (= "fresh@example.com" (:user/email (stored-user node user-id)))))))

(deftest turn-off-sensitive!-test
  (testing "turn-off-sensitive! function"
    (with-open [node (test-xtdb-node [])]
      (let [ctx (get-context node)
            user-id (UUID/randomUUID)]

        ;; Create user with sensitive setting enabled
        (mutations/create-entity!
         ctx
         {:entity-key :user,
          :data {:user/email "test@example.com"
                 :user/joined-at (t/now)
                 :user/show-sensitive true
                 :xt/id user-id}})

        (testing "should turn off sensitive setting"
          (let [turn-off-ctx (assoc ctx :authorized.user/id user-id)
                result (user/turn-off-sensitive! turn-off-ctx)
                updated-db (xt/db node)
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
                result (user/turn-off-sensitive! turn-off-ctx)]

            ;; Check custom redirect
            (is (= 303 (:status result)))
            (is (= "/custom/redirect" (get-in result [:headers "location"])))))

        (testing "should ignore an off-site redirect parameter"
          (let [result (user/turn-off-sensitive!
                        (assoc ctx
                               :authorized.user/id user-id
                               :params {:redirect "https://example.com"}))]
            (is (= "/app" (get-in result [:headers "location"])))))

        (testing "should work when sensitive is already false"
          ;; First turn it off
          (user/turn-off-sensitive! (assoc ctx :authorized.user/id user-id))

          ;; Then turn it off again
          (let [result (user/turn-off-sensitive! (assoc ctx :authorized.user/id user-id))
                updated-db (xt/db node)
                updated-user (queries/get-entity-by-id updated-db user-id)]

            ;; Should still work fine
            (is (= 303 (:status result)))
            (is (false? (:user/show-sensitive updated-user)))))))))

(deftest update-user!-integration-test
  (testing "update-user! function with new fields"
    (with-open [node (test-xtdb-node [])]
      (let [ctx (get-context node)
            user-id (UUID/randomUUID)]

        ;; Create initial user
        (mutations/create-entity!
         ctx
         {:entity-key :user,
          :data {:user/email "initial@example.com"
                 :user/joined-at (t/now)
                 :xt/id user-id}})

        (testing "should update user with sensitive and archived fields"
          (let [result (mutations/update-user!
                        ctx
                        user-id
                        {:user/email "updated@example.com"
                         :user/time-zone "America/Los_Angeles"
                         :user/show-sensitive true
                         :user/show-archived false})
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
                _ (mutations/create-entity!
                   ctx
                   {:entity-key :user,
                    :data {:user/email "fresh@example.com"
                           :user/joined-at (t/now)
                           :xt/id fresh-user-id}})
                #_{:clj-kondo/ignore [:unused-binding]}
                result (mutations/update-user!
                        ctx
                        fresh-user-id
                        {:user/email "updated@example.com"})
                updated-db (xt/db node)
                updated-user (queries/get-entity-by-id updated-db fresh-user-id)]

            ;; Optional fields should not be present if not provided
            (is (not (contains? updated-user :user/show-sensitive)))
            (is (not (contains? updated-user :user/show-archived)))

            ;; But the query functions should still return false for missing fields
            (let [user-settings (queries/get-user-settings updated-db fresh-user-id)]
              (is (false? (:show-sensitive user-settings)))
              (is (false? (:show-archived user-settings))))))))))

(deftest turn-off-archived!-test
  (testing "turn-off-archived! function"
    (with-open [node (test-xtdb-node [])]
      (let [ctx (get-context node)
            user-id (UUID/randomUUID)]

        ;; Create user with archived setting enabled
        (mutations/create-entity!
         ctx
         {:entity-key :user,
          :data {:user/email "test@example.com"
                 :user/joined-at (t/now)
                 :user/show-archived true
                 :xt/id user-id}})

        (testing "should turn off archived setting"
          (let [turn-off-ctx (assoc ctx :authorized.user/id user-id)
                result (user/turn-off-archived! turn-off-ctx)
                updated-db (xt/db node)
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
                result (user/turn-off-archived! turn-off-ctx)]

            ;; Check custom redirect
            (is (= 303 (:status result)))
            (is (= "/custom/page" (get-in result [:headers "location"])))))

        (testing "should work when archived is already false"
          ;; First turn it off
          (user/turn-off-archived! (assoc ctx :authorized.user/id user-id))

          ;; Then turn it off again
          (let [result (user/turn-off-archived! (assoc ctx :authorized.user/id user-id))
                updated-db (xt/db node)
                updated-user (queries/get-entity-by-id updated-db user-id)]

            ;; Should still work fine
            (is (= 303 (:status result)))
            (is (false? (:user/show-archived updated-user)))))))))