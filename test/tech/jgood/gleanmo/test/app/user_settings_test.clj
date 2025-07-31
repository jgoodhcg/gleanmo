(ns tech.jgood.gleanmo.test.app.user-settings-test
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

(defn create-valid-cruddy-data
  "Creates a valid cruddy entity for the specified user-id"
  [user-id]
  (let [now         (t/now)
        habit-id    (UUID/randomUUID)
        location-id (UUID/randomUUID)]
    {:user/id                        user-id,
     :cruddy/label                   "Test Cruddy",
     :cruddy/notes                   "Some notes for testing",
     :cruddy/num                     42.5,
     :cruddy/bool                    true,
     :cruddy/integer                 10,
     :cruddy/single-relation         habit-id,
     :cruddy/another-single-relation location-id,
     :cruddy/set-relation            #{habit-id},
     :cruddy/enum                    :a,
     :cruddy/time-zone               "UTC",
     :cruddy/timestamp               now}))

(deftest user-settings-schema-test
  (testing "user-settings schema validation"
    (with-open [node (test-xtdb-node [])]
      (let [ctx     (get-context node)
            user-id (UUID/randomUUID)]
        
        (testing "should create valid user-settings entity"
          (let [settings-id (mutations/create-entity!
                              ctx
                              {:entity-key :user-settings,
                               :data       {:user/id                     user-id,
                                            :user-settings/show-sensitive true}})
                db          (xt/db node)
                settings    (queries/get-entity-by-id db settings-id)]
            (is (some? settings))
            (is (= user-id (:user/id settings)))
            (is (true? (:user-settings/show-sensitive settings)))))

        (testing "should create user-settings without show-sensitive field"
          (let [settings-id (mutations/create-entity!
                              ctx
                              {:entity-key :user-settings,
                               :data       {:user/id user-id}})
                db          (xt/db node)
                settings    (queries/get-entity-by-id db settings-id)]
            (is (some? settings))
            (is (= user-id (:user/id settings)))
            (is (nil? (:user-settings/show-sensitive settings)))))))))

(deftest get-user-settings-test
  (testing "should return nil when no settings exist"
    (with-open [node (test-xtdb-node [])]
      (let [user-id  (UUID/randomUUID)
            db       (xt/db node)
            settings (queries/get-user-settings db user-id)]
        (is (nil? settings)))))

  (testing "should return settings when they exist"
    (with-open [node (test-xtdb-node [])]
      (let [ctx       (get-context node)
            user-id   (UUID/randomUUID)
            settings-id (mutations/create-entity!
                          ctx
                          {:entity-key :user-settings,
                           :data       {:user/id                     user-id,
                                        :user-settings/show-sensitive true}})
            db        (xt/db node)
            settings  (queries/get-user-settings db user-id)]
        (is (some? settings))
        (is (= settings-id (:xt/id settings)))
        (is (= user-id (:user/id settings)))
        (is (true? (:user-settings/show-sensitive settings))))))

  (testing "should not return deleted settings"
    (with-open [node (test-xtdb-node [])]
      (let [ctx       (get-context node)
            user-id   (UUID/randomUUID)
            settings-id (mutations/create-entity!
                          ctx
                          {:entity-key :user-settings,
                           :data       {:user/id                     user-id,
                                        :user-settings/show-sensitive false}})
            ;; Mark as deleted using a soft delete transaction
            _         (biff/submit-tx ctx
                                      [{:db/op :update,
                                        :db/doc-type :user-settings,
                                        :xt/id settings-id,
                                        :tech.jgood.gleanmo.schema.meta/deleted-at (t/now)}])
            db        (xt/db node)
            settings  (queries/get-user-settings db user-id)]
        (is (nil? settings)))))

  (testing "should return settings for correct user only"
    (with-open [node (test-xtdb-node [])]
      (let [ctx         (get-context node)
            user-id     (UUID/randomUUID)
            other-user-id (UUID/randomUUID)
            _           (mutations/create-entity!
                          ctx
                          {:entity-key :user-settings,
                           :data       {:user/id                     other-user-id,
                                        :user-settings/show-sensitive true}})
            db          (xt/db node)
            settings    (queries/get-user-settings db user-id)]
        (is (nil? settings))))))

(deftest get-show-sensitive-setting-test
  (testing "should default to false when no settings exist"
    (with-open [node (test-xtdb-node [])]
      (let [user-id      (UUID/randomUUID)
            db           (xt/db node)
            show-sensitive (queries/get-show-sensitive-setting db user-id)]
        (is (false? show-sensitive)))))

  (testing "should return true when setting is true"
    (with-open [node (test-xtdb-node [])]
      (let [ctx        (get-context node)
            user-id    (UUID/randomUUID)
            _          (mutations/create-entity!
                         ctx
                         {:entity-key :user-settings,
                          :data       {:user/id                     user-id,
                                       :user-settings/show-sensitive true}})
            db         (xt/db node)
            show-sensitive (queries/get-show-sensitive-setting db user-id)]
        (is (true? show-sensitive)))))

  (testing "should return false when setting is false"
    (with-open [node (test-xtdb-node [])]
      (let [ctx        (get-context node)
            user-id    (UUID/randomUUID)
            _          (mutations/create-entity!
                         ctx
                         {:entity-key :user-settings,
                          :data       {:user/id                     user-id,
                                       :user-settings/show-sensitive false}})
            db         (xt/db node)
            show-sensitive (queries/get-show-sensitive-setting db user-id)]
        (is (false? show-sensitive)))))

  (testing "should return false when setting is nil"
    (with-open [node (test-xtdb-node [])]
      (let [ctx        (get-context node)
            user-id    (UUID/randomUUID)
            _          (mutations/create-entity!
                         ctx
                         {:entity-key :user-settings,
                          :data       {:user/id user-id}})
            db         (xt/db node)
            show-sensitive (queries/get-show-sensitive-setting db user-id)]
        (is (false? show-sensitive))))))

(deftest queries-user-settings-integration-test
  (testing "get-user-settings function in queries namespace"
    (with-open [node (test-xtdb-node [])]
      (let [user-id (UUID/randomUUID)
            db      (xt/db node)
            settings (queries/get-user-settings db user-id)]
        (is (nil? settings)))))

  (testing "get-user-settings function with existing settings"
    (with-open [node (test-xtdb-node [])]
      (let [ctx       (get-context node)
            user-id   (UUID/randomUUID)
            settings-id (mutations/create-entity!
                          ctx
                          {:entity-key :user-settings,
                           :data       {:user/id                     user-id,
                                        :user-settings/show-sensitive true}})
            db        (xt/db node)
            settings  (queries/get-user-settings db user-id)]
        (is (some? settings))
        (is (= settings-id (:xt/id settings))))))

  (testing "get-show-sensitive-setting function in queries namespace"
    (with-open [node (test-xtdb-node [])]
      (let [ctx        (get-context node)
            user-id    (UUID/randomUUID)
            _          (mutations/create-entity!
                         ctx
                         {:entity-key :user-settings,
                          :data       {:user/id                     user-id,
                                       :user-settings/show-sensitive true}})
            db         (xt/db node)
            show-sensitive (queries/get-show-sensitive-setting db user-id)]
        (is (true? show-sensitive))))))

(deftest all-for-user-query-with-settings-test
  (testing "should hide sensitive entities when no settings exist (default false)"
    (with-open [node (test-xtdb-node [])]
      (let [ctx     (get-context node)
            user-id (UUID/randomUUID)
            ;; Create test entities using proper cruddy data
            normal-id    (mutations/create-entity!
                           ctx
                           {:entity-key :cruddy,
                            :data       (merge (create-valid-cruddy-data user-id)
                                               {:cruddy/label "Normal Entity"})})
            sensitive-id (mutations/create-entity!
                           ctx
                           {:entity-key :cruddy,
                            :data       (merge (create-valid-cruddy-data user-id)
                                               {:cruddy/label    "Sensitive Entity",
                                                :cruddy/sensitive true})})
            db           (xt/db node)
            mock-schema  [:map {:closed true}]
            base-ctx     {:biff/db db, :session {:uid user-id}, :params {}}
            result       (queries/all-for-user-query
                           {:entity-type-str   "cruddy",
                            :schema            mock-schema,
                            :filter-references false}
                           base-ctx)]
        (is (= 1 (count result)))
        (is (= normal-id (:xt/id (first result)))))))

  (testing "should hide sensitive entities when setting is false"
    (with-open [node (test-xtdb-node [])]
      (let [ctx     (get-context node)
            user-id (UUID/randomUUID)
            normal-id    (mutations/create-entity!
                           ctx
                           {:entity-key :cruddy,
                            :data       (merge (create-valid-cruddy-data user-id)
                                               {:cruddy/label "Normal Entity"})})
            sensitive-id (mutations/create-entity!
                           ctx
                           {:entity-key :cruddy,
                            :data       (merge (create-valid-cruddy-data user-id)
                                               {:cruddy/label    "Sensitive Entity",
                                                :cruddy/sensitive true})})
            _        (mutations/create-entity!
                       ctx
                       {:entity-key :user-settings,
                        :data       {:user/id                     user-id,
                                     :user-settings/show-sensitive false}})
            db       (xt/db node)
            mock-schema [:map {:closed true}]
            base-ctx {:biff/db db, :session {:uid user-id}, :params {}}
            result   (queries/all-for-user-query
                       {:entity-type-str   "cruddy",
                        :schema            mock-schema,
                        :filter-references false}
                       base-ctx)]
        (is (= 1 (count result)))
        (is (= normal-id (:xt/id (first result)))))))

  (testing "should show sensitive entities when setting is true"
    (with-open [node (test-xtdb-node [])]
      (let [ctx     (get-context node)
            user-id (UUID/randomUUID)
            normal-id    (mutations/create-entity!
                           ctx
                           {:entity-key :cruddy,
                            :data       (merge (create-valid-cruddy-data user-id)
                                               {:cruddy/label "Normal Entity"})})
            sensitive-id (mutations/create-entity!
                           ctx
                           {:entity-key :cruddy,
                            :data       (merge (create-valid-cruddy-data user-id)
                                               {:cruddy/label    "Sensitive Entity",
                                                :cruddy/sensitive true})})
            _        (mutations/create-entity!
                       ctx
                       {:entity-key :user-settings,
                        :data       {:user/id                     user-id,
                                     :user-settings/show-sensitive true}})
            db       (xt/db node)
            mock-schema [:map {:closed true}]
            base-ctx {:biff/db db, :session {:uid user-id}, :params {}}
            result   (queries/all-for-user-query
                       {:entity-type-str   "cruddy",
                        :schema            mock-schema,
                        :filter-references false}
                       base-ctx)]
        (is (= 2 (count result)))
        (is (some #(= normal-id (:xt/id %)) result))
        (is (some #(= sensitive-id (:xt/id %)) result))))))