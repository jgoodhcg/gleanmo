(ns tech.jgood.gleanmo.test.db.queries-test
  (:require
    [clojure.test       :refer [deftest is testing]]
    [com.biffweb        :as    biff
                        :refer [test-xtdb-node]]
    [tech.jgood.gleanmo :as main]
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
  "Creates a valid cruddy entity for the specified user-id or a random one if not provided"
  ([]
   (create-valid-cruddy-data (UUID/randomUUID)))
  ([user-id]
   (let [now         (t/now)
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
      :cruddy/timestamp       now})))

;; Create test entities with various attributes to test filtering
(defn create-test-entities
  [ctx user-id]
  (let [sensitive-entity-id (mutations/create-entity!
                              ctx
                              {:entity-key :cruddy,
                               :data       (merge
                                             (create-valid-cruddy-data user-id)
                                             {:cruddy/label "Sensitive Entity",
                                              :cruddy/sensitive true})})

        archived-entity-id  (mutations/create-entity!
                              ctx
                              {:entity-key :cruddy,
                               :data       (merge
                                             (create-valid-cruddy-data user-id)
                                             {:cruddy/label "Archived Entity",
                                              :cruddy/archived true})})

        normal-entity-id    (mutations/create-entity!
                              ctx
                              {:entity-key :cruddy,
                               :data       (merge
                                             (create-valid-cruddy-data user-id)
                                             {:cruddy/label "Normal Entity"})})

        ;; Return the created IDs for reference in tests
        entity-ids          {:sensitive sensitive-entity-id,
                             :archived  archived-entity-id,
                             :normal    normal-entity-id}]

    entity-ids))

(defn create-related-entities
  [ctx user-id]
  (let [habit-id (mutations/create-entity!
                   ctx
                   {:entity-key :habit,
                    :data       {:user/id         user-id,
                                 :habit/label     "Test Habit",
                                 :habit/notes     "Habit notes",
                                 :habit/sensitive false}})

        habit-sensitive-id (mutations/create-entity!
                             ctx
                             {:entity-key :habit,
                              :data       {:user/id user-id,
                                           :habit/label "Sensitive Habit",
                                           :habit/notes "Sensitive habit notes",
                                           :habit/sensitive true}})

        location-id (mutations/create-entity!
                      ctx
                      {:entity-key :location,
                       :data       {:user/id           user-id,
                                    :location/label    "Test Location",
                                    :location/notes    "Location notes",
                                    :location/archived false}})

        location-archived-id
          (mutations/create-entity!
            ctx
            {:entity-key :location,
             :data       {:user/id           user-id,
                          :location/label    "Archived Location",
                          :location/notes    "Archived location notes",
                          :location/archived true}})

        entity-with-normal-relations-id
          (mutations/create-entity!
            ctx
            {:entity-key :cruddy,
             :data       (merge (create-valid-cruddy-data user-id)
                                {:cruddy/label "Entity with normal relations",
                                 :cruddy/single-relation habit-id,
                                 :cruddy/another-single-relation location-id,
                                 :cruddy/set-relation #{habit-id}})})

        entity-with-sensitive-relation-id
          (mutations/create-entity!
            ctx
            {:entity-key :cruddy,
             :data       (merge (create-valid-cruddy-data user-id)
                                {:cruddy/label "Entity with sensitive relation",
                                 :cruddy/single-relation habit-sensitive-id,
                                 :cruddy/another-single-relation location-id,
                                 :cruddy/set-relation #{habit-id}})})

        entity-with-archived-relation-id
          (mutations/create-entity!
            ctx
            {:entity-key :cruddy,
             :data       (merge (create-valid-cruddy-data user-id)
                                {:cruddy/label "Entity with archived relation",
                                 :cruddy/single-relation habit-id,
                                 :cruddy/another-single-relation
                                   location-archived-id,
                                 :cruddy/set-relation #{habit-id}})})

        ;; Return all the entity IDs for tests
        entity-ids {:habit habit-id,
                    :habit-sensitive habit-sensitive-id,
                    :location location-id,
                    :location-archived location-archived-id,
                    :normal-relations entity-with-normal-relations-id,
                    :sensitive-relation entity-with-sensitive-relation-id,
                    :archived-relation entity-with-archived-relation-id}]

    entity-ids))

(deftest get-entity-by-id-test
  (testing "get-entity-by-id"
    (with-open [node (test-xtdb-node [])]
      (let [ctx         (get-context node)
            user-id     (UUID/randomUUID)
            entity-data (create-valid-cruddy-data user-id)
            entity-id   (mutations/create-entity! ctx
                                                  {:entity-key :cruddy,
                                                   :data       entity-data})
            db          (xt/db node)]

        (testing "should return entity when it exists"
          (let [entity (queries/get-entity-by-id db entity-id)]
            (is (some? entity))
            (is (= entity-id (:xt/id entity)))
            (is (= (:cruddy/label entity-data) (:cruddy/label entity)))))

        (testing "should return nil for non-existent entity"
          (let [random-id (UUID/randomUUID)
                entity    (queries/get-entity-by-id db random-id)]
            (is (nil? entity))))))))

(deftest get-entity-for-user-test
  (testing "get-entity-for-user"
    (with-open [node (test-xtdb-node [])]
      (let [ctx           (get-context node)
            user-id       (UUID/randomUUID)
            other-user-id (UUID/randomUUID)
            entity-data   (create-valid-cruddy-data user-id)
            entity-id     (mutations/create-entity! ctx
                                                    {:entity-key :cruddy,
                                                     :data       entity-data})
            db            (xt/db node)]

        (testing "should return entity when it belongs to the user"
          (let [entity
                  (queries/get-entity-for-user db entity-id user-id :cruddy)]
            (is (some? entity))
            (is (= entity-id (:xt/id entity)))
            (is (= user-id (:user/id entity)))))

        (testing "should return nil for entity that doesn't belong to the user"
          (let [entity (queries/get-entity-for-user db
                                                    entity-id
                                                    other-user-id
                                                    :cruddy)]
            (is (nil? entity))))

        (testing "should return nil for entity of incorrect type"
          (let [entity (queries/get-entity-for-user db
                                                    entity-id
                                                    user-id
                                                    :wrong-type)]
            (is (nil? entity))))))))

(deftest all-entities-for-user-test
  (testing "all-entities-for-user"
    (with-open [node (test-xtdb-node [])]
      (let [ctx           (get-context node)
            user-id       (UUID/randomUUID)
            other-user-id (UUID/randomUUID)
            entity-ids    (create-test-entities ctx user-id)
            ;; Create an entity for a different user to test filtering by
            ;; user
            _ (mutations/create-entity!
                ctx
                {:entity-key :cruddy,
                 :data       (merge (create-valid-cruddy-data other-user-id)
                                    {:cruddy/label "Other User's Entity"})})
            db            (xt/db node)]

        (testing
          "should return all non-sensitive, non-archived entities by default"
          (let [entities (queries/all-entities-for-user db user-id :cruddy)]
            (is (= 1 (count entities)))
            (is (= (:normal entity-ids) (:xt/id (first entities))))))

        (testing
          "should include sensitive entities when filter-sensitive is true"
          (let [entities (queries/all-entities-for-user
                           db
                           user-id
                           :cruddy
                           :filter-sensitive
                           true)]
            (is (= 2 (count entities)))
            (is (some #(= (:normal entity-ids) (:xt/id %)) entities))
            (is (some #(= (:sensitive entity-ids) (:xt/id %)) entities))))

        (testing "should include archived entities when filter-archived is true"
          (let [entities (queries/all-entities-for-user
                           db
                           user-id
                           :cruddy
                           :filter-archived
                           true)]
            (is (= 2 (count entities)))
            (is (some #(= (:normal entity-ids) (:xt/id %)) entities))
            (is (some #(= (:archived entity-ids) (:xt/id %)) entities))))

        (testing "should include all entity types when both filters are true"
          (let [entities (queries/all-entities-for-user
                           db
                           user-id
                           :cruddy
                           :filter-sensitive true
                           :filter-archived  true)]
            (is (= 3 (count entities)))
            (is (some #(= (:normal entity-ids) (:xt/id %)) entities))
            (is (some #(= (:sensitive entity-ids) (:xt/id %)) entities))
            (is (some #(= (:archived entity-ids) (:xt/id %)) entities))))

        (testing "should not return entities for other users"
          (let [entities (queries/all-entities-for-user
                           db
                           user-id
                           :cruddy
                           :filter-sensitive true
                           :filter-archived  true)]
            (is (every? #(= user-id (:user/id %)) entities))))))))

(deftest entities-with-related-filtering-test
  (testing "all-entities-for-user with relationship filtering"
    (with-open [node (test-xtdb-node [])]
      (let [ctx     (get-context node)
            user-id (UUID/randomUUID)
            related-entity-ids (create-related-entities ctx user-id)
            db      (xt/db node)
            ;; Mock the relationship fields structure expected by the
            ;; function
            relationship-fields [{:field-key          :cruddy/single-relation,
                                  :input-type         :single-relationship,
                                  :related-entity-str "habit"}
                                 {:field-key :cruddy/another-single-relation,
                                  :input-type :single-relationship,
                                  :related-entity-str "location"}
                                 {:field-key          :cruddy/set-relation,
                                  :input-type         :many-relationship,
                                  :related-entity-str "habit"}]]

        (testing
          "should filter out entities with sensitive related entities when filter-sensitive is false"
          (let [entities (queries/all-entities-for-user
                           db
                           user-id
                           :cruddy
                           :filter-sensitive    false
                           :filter-references   true
                           :relationship-fields relationship-fields)]
            ;; Should only include normal entities without sensitive
            ;; relations
            (is (some #(= (:normal-relations related-entity-ids) (:xt/id %))
                      entities))
            (is (not-any? #(= (:sensitive-relation related-entity-ids)
                              (:xt/id %))
                          entities))))

        (testing
          "should filter out entities with archived related entities when filter-archived is false"
          (let [entities (queries/all-entities-for-user
                           db
                           user-id
                           :cruddy
                           :filter-archived     false
                           :filter-references   true
                           :relationship-fields relationship-fields)]
            ;; Should only include normal entities without archived
            ;; relations
            (is (some #(= (:normal-relations related-entity-ids) (:xt/id %))
                      entities))
            (is (not-any? #(= (:archived-relation related-entity-ids)
                              (:xt/id %))
                          entities))))

        (testing "should include all entities when both filters are true"
          (let [entities (queries/all-entities-for-user
                           db
                           user-id
                           :cruddy
                           :filter-sensitive    true
                           :filter-archived     true
                           :filter-references   true
                           :relationship-fields relationship-fields)]
            ;; Should include all types of entities
            (is (some #(= (:normal-relations related-entity-ids) (:xt/id %))
                      entities))
            (is (some #(= (:sensitive-relation related-entity-ids) (:xt/id %))
                      entities))
            (is (some #(= (:archived-relation related-entity-ids) (:xt/id %))
                      entities))))))))

(deftest all-for-user-query-test
  (testing "all-for-user-query"
    (with-open [node (test-xtdb-node [])]
      (let [ctx         (get-context node)
            user-id     (UUID/randomUUID)
            entity-ids  (create-test-entities ctx user-id)
            db          (xt/db node)
            ;; Create a schema map similar to what would be used in the
            ;; application
            mock-schema [:map {:closed true}
                         [:cruddy/single-relation :habit/id]
                         [:cruddy/another-single-relation :location/id]
                         [:cruddy/set-relation [:set :habit/id]]]
            ;; Create a mock request context
            request-ctx {:biff/db db,
                         :session {:uid user-id},
                         :params  {}}]

        (testing "should filter entities based on params"
          (let [result (queries/all-for-user-query
                         {:entity-type-str "cruddy",
                          :schema mock-schema,
                          :filter-references true}
                         request-ctx)]
            ;; By default, should only include non-sensitive, non-archived
            ;; entities
            (is (= 1 (count result)))
            (is (= (:normal entity-ids) (:xt/id (first result))))))

        (testing "should respect sensitive param"
          (let [result (queries/all-for-user-query
                         {:entity-type-str "cruddy",
                          :schema mock-schema,
                          :filter-references true}
                         (assoc-in request-ctx [:params :sensitive] "true"))]
            ;; Should include sensitive entities
            (is (= 2 (count result)))
            (is (some #(= (:sensitive entity-ids) (:xt/id %)) result))
            (is (some #(= (:normal entity-ids) (:xt/id %)) result))))

        (testing "should respect archived param"
          (let [result (queries/all-for-user-query
                         {:entity-type-str "cruddy",
                          :schema mock-schema,
                          :filter-references true}
                         (assoc-in request-ctx [:params :archived] "true"))]
            ;; Should include archived entities
            (is (= 2 (count result)))
            (is (some #(= (:archived entity-ids) (:xt/id %)) result))
            (is (some #(= (:normal entity-ids) (:xt/id %)) result))))))))
