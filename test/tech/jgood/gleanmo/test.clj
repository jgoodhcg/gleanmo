(ns tech.jgood.gleanmo.test
  (:require
   [clojure.test :refer [deftest is]]
   [com.biffweb :as biff :refer [test-xtdb-node]]
   [tech.jgood.gleanmo :as main]
   [tech.jgood.gleanmo.app.habit :as habit]
   [tech.jgood.gleanmo.test.db.mutations-test]
   [xtdb.api :as xt])
  (:import
   [java.util UUID]))

(deftest example-test
  (is (= 4 (+ 2 2))))

(defn get-context [node]
  {:biff.xtdb/node  node
   :biff/db         (xt/db node)
   :biff/malli-opts #'main/malli-opts})

#_
(deftest habit-create-test
  (with-open [node (test-xtdb-node [])]
    (let [user-uuid   (UUID/randomUUID)
          ctx         (assoc (get-context node) :session {:uid user-uuid})
          habit-label "label"
          _           (habit/create! (merge ctx {:params {:habit-label habit-label
                                                          :notes       ""
                                                          :sensitive   false}}))
          db          (xt/db node) ; get a fresh db value so it contains any transactions
                                   ; that create! submitted.
          doc         (biff/lookup db :habit/label habit-label)]
      (is (some? doc))
      (is (= (:user/id doc) user-uuid)))))
