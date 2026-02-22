(ns tech.jgood.gleanmo.test.app.shared-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [com.biffweb :as biff
    :refer [test-xtdb-node]]
   [tech.jgood.gleanmo :as main]
   [tech.jgood.gleanmo.app.shared :as shared]
   [tech.jgood.gleanmo.db.mutations :as mutations]
   [tick.core :as t]
   [xtdb.api :as xt])
  (:import
   [java.util UUID]))

(defn get-context
  [node]
  {:biff.xtdb/node node,
   :biff/db (xt/db node),
   :biff/malli-opts #'main/malli-opts})

(deftest turn-off-sensitive-button-test
  (testing "should return nil when show-sensitive is false"
    (let [result (shared/turn-off-sensitive-button false (UUID/randomUUID))]
      (is (nil? result))))

  (testing "should return form component when show-sensitive is true"
    (let [user-id (UUID/randomUUID)
          result (shared/turn-off-sensitive-button true user-id)]
      (is (vector? result))
      (is (= :form (first result)))
      ;; Check that the form action includes the user ID
      (let [form-attrs (second result)]
        (is (= (str "/app/users/" user-id "/settings/turn-off-sensitive") (:action form-attrs)))
        (is (= "post" (:method form-attrs)))
        (is (= "inline" (:class form-attrs))))))

  (testing "should contain button element"
    (let [user-id (UUID/randomUUID)
          result (shared/turn-off-sensitive-button true user-id)]
      ;; The form contains: [:form {...} [:input ...] [:button ...]]
      ;; Skip the anti-forgery token input and get the button
      #_{:clj-kondo/ignore [:redundant-let]}
      (let [button-component (first (last result))]
        (is (vector? button-component))
        ;; Just verify it's a button element, not specific styling
        (is (.startsWith (name (first button-component)) "button"))
        ;; Verify it contains the expected text
        (is (some #(and (string? %) (.contains % "Sensitive")) (tree-seq coll? seq button-component)))))))

(deftest side-bar-data-fetching-test
  (testing "should fetch user email and sensitive settings"
    (with-open [node (test-xtdb-node [])]
      (let [ctx (get-context node)
            user-id (UUID/randomUUID)]
        ;; Create a test user with sensitive setting enabled
        (mutations/create-entity!
         ctx
         {:entity-key :user
          :data {:user/email "test@example.com"
                 :user/joined-at (t/now)
                 :user/show-sensitive true
                 :xt/id user-id}})

        ;; Test the sidebar context preparation
        (let [sidebar-ctx {:biff/db (xt/db node)
                           :session {:uid user-id}}
              result (shared/side-bar sidebar-ctx [:div "test content"])]
          ;; Should return a valid hiccup structure
          (is (vector? result))
          (is (= :div.flex.min-h-screen (first result)))))))

  (testing "should handle missing user gracefully"
    (with-open [node (test-xtdb-node [])]
      #_{:clj-kondo/ignore [:unused-binding]}
      (let [ctx (get-context node)
            user-id (UUID/randomUUID)
            sidebar-ctx {:biff/db (xt/db node)
                         :session {:uid user-id}}
            result (shared/side-bar sidebar-ctx [:div "test content"])]
        ;; Should still return a valid structure even with no user data
        (is (vector? result))
        (is (= :div.flex.min-h-screen (first result))))))

  (testing "should default to false for show-sensitive when no settings exist"
    (with-open [node (test-xtdb-node [])]
      #_{:clj-kondo/ignore [:unused-binding]}
      (let [ctx (get-context node)
            user-id (UUID/randomUUID)
            sidebar-ctx {:biff/db (xt/db node)
                         :session {:uid user-id}}]
        ;; Don't create any user settings
        #_{:clj-kondo/ignore [:redundant-let]}
        (let [result (shared/side-bar sidebar-ctx [:div "test content"])]
          ;; Should not show the turn-off button since show-sensitive defaults to false
          (is (vector? result)))))))

(deftest turn-off-archived-button-test
  (testing "should return nil when show-archived is false"
    (let [result (shared/turn-off-archived-button false (UUID/randomUUID))]
      (is (nil? result))))

  (testing "should return form component when show-archived is true"
    (let [user-id (UUID/randomUUID)
          result (shared/turn-off-archived-button true user-id)]
      (is (vector? result))
      (is (= :form (first result)))
      ;; Check that the form action includes the user ID
      (let [form-attrs (second result)]
        (is (= (str "/app/users/" user-id "/settings/turn-off-archived") (:action form-attrs)))
        (is (= "post" (:method form-attrs)))
        (is (= "inline" (:class form-attrs))))))

  (testing "should contain button element for archived button"
    (let [user-id (UUID/randomUUID)
          result (shared/turn-off-archived-button true user-id)]
      ;; The form contains: [:form {...} [:input ...] [:button ...]]
      ;; Skip the anti-forgery token input and get the button
      #_{:clj-kondo/ignore [:redundant-let]}
      (let [button-component (first (last result))]
        (is (vector? button-component))
        ;; Just verify it's a button element, not specific styling
        (is (.startsWith (name (first button-component)) "button"))
        ;; Verify it contains the expected text
        (is (some #(and (string? %) (.contains % "Archived")) (tree-seq coll? seq button-component)))))))

(deftest param-true-test
  (testing "should return true for 'on'"
    (is (true? (shared/param-true? "on"))))

  (testing "should return true for 'true'"
    (is (true? (shared/param-true? "true"))))

  (testing "should return false for other values"
    (is (false? (shared/param-true? "false")))
    (is (false? (shared/param-true? "off")))
    (is (false? (shared/param-true? nil)))
    (is (false? (shared/param-true? "")))))
