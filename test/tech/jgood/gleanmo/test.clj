(ns tech.jgood.gleanmo.test
  (:require
   [clojure.test :refer [deftest is]]
   [com.biffweb :as biff :refer [test-xtdb-node]]
   [tech.jgood.gleanmo :as main]
   [tech.jgood.gleanmo.app.habit :as habit]
   [xtdb.api :as xt])
  (:import
   [java.util UUID]))

(deftest example-test
  (is (= 4 (+ 2 2))))

(defn get-context [node]
  {:biff.xtdb/node  node
   :biff/db         (xt/db node)
   :biff/malli-opts #'main/malli-opts})

(deftest habit-create-test
  (with-open [node (test-xtdb-node [])]
    (println "testing")
    (let [user-uuid  (UUID/randomUUID)
          ctx        (assoc (get-context node) :session {:uid user-uuid})
          habit-name "name"
          _          (habit/create! (merge ctx {:params {:habit-name habit-name
                                                         :notes      ""
                                                         :sensitive  false}}))
          db         (xt/db node) ; get a fresh db value so it contains any transactions
                                        ; that create! submitted.
          doc        (biff/lookup db :habit/name habit-name)]
      (is (some? doc))
      (is (= (:user/id doc) user-uuid)))))
