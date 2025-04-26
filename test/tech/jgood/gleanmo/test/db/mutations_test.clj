(ns tech.jgood.gleanmo.test.db.mutations-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [com.biffweb :as biff :refer [test-xtdb-node]]
   [tech.jgood.gleanmo :as main]
   [tech.jgood.gleanmo.db.mutations :as mutations]
   [tech.jgood.gleanmo.schema.meta :as sm]
   [tick.core :as t]
   [xtdb.api :as xt])
  (:import
   [java.util UUID]))

(defn get-context
  [node]
  {:biff.xtdb/node  node,
   :biff/db         (xt/db node),
   :biff/malli-opts #'main/malli-opts})

(defn create-valid-cruddy-data
  []
  (let [now         (t/now)
        user-id     (UUID/randomUUID)
        habit-id    (UUID/randomUUID)
        location-id (UUID/randomUUID)]
    {:user/id                user-id,
     :cruddy/label           "Test Cruddy",
     :cruddy/notes           "Some notes for testing",
     :cruddy/num             42.5,
     :cruddy/bool            true,
     :cruddy/integer         10,
     :cruddy/single-relation habit-id,
     :cruddy/another-single-relation location-id,
     :cruddy/set-relation    #{habit-id},
     :cruddy/enum            :a,
     :cruddy/time-zone       "UTC",
     :cruddy/timestamp       now}))

(deftest create-entity-test
  (testing "create-entity!"
    (with-open [node (test-xtdb-node [])]
      (let [ctx        (get-context node)
            entity-key :cruddy
            test-data  (create-valid-cruddy-data)
            entity-id  (mutations/create-entity! ctx
                                                 {:entity-key entity-key,
                                                  :data       test-data})
            db         (xt/db node)
            entity     (xt/entity db entity-id)]

        (is (some? entity-id))
        (is (some? entity))
        (is (= entity-key (::sm/type entity)))
        (is (= (:cruddy/label test-data) (:cruddy/label entity)))
        (is (= (:cruddy/num test-data) (:cruddy/num entity)))
        (is (= (:cruddy/bool test-data) (:cruddy/bool entity)))
        (is (some? (::sm/created-at entity)))))))

(deftest update-entity-test
  (testing "update-entity!"
    (with-open [node (test-xtdb-node [])]
      (let [ctx            (get-context node)
            entity-key     :cruddy
            initial-data   (create-valid-cruddy-data)
            entity-id      (mutations/create-entity! ctx
                                                     {:entity-key entity-key,
                                                      :data       initial-data})
            updated-data   {:cruddy/label      "Updated Label",
                            :cruddy/num        99.9,
                            :cruddy/bool       false,
                            :cruddy/other-text "New optional field"}

            _ (mutations/update-entity! ctx
                                        {:entity-key entity-key,
                                         :entity-id  entity-id,
                                         :data       updated-data})
            db             (xt/db node)
            updated-entity (xt/entity db entity-id)]

        (is (= "Updated Label" (:cruddy/label updated-entity)))
        (is (= 99.9 (:cruddy/num updated-entity)))
        (is (= false (:cruddy/bool updated-entity)))
        (is (= "New optional field" (:cruddy/other-text updated-entity)))
        (is (= entity-key (::sm/type updated-entity)))
        (is (some? (::sm/created-at updated-entity)))))))

(deftest soft-delete-entity-test
  (testing "soft-delete-entity!"
    (with-open [node (test-xtdb-node [])]
      (let [ctx            (get-context node)
            entity-key     :cruddy
            initial-data   (create-valid-cruddy-data)
            entity-id      (mutations/create-entity! ctx
                                                     {:entity-key entity-key,
                                                      :data       initial-data})

            _ (mutations/soft-delete-entity! ctx
                                             {:entity-key entity-key,
                                              :entity-id  entity-id})
            db             (xt/db node)
            deleted-entity (xt/entity db entity-id)]

        (is (some? (::sm/deleted-at deleted-entity)))
        (is (= entity-key (::sm/type deleted-entity)))
        (is (= (:cruddy/label initial-data) (:cruddy/label deleted-entity)))))))
