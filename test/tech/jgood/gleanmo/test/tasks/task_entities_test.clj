(ns tech.jgood.gleanmo.test.tasks.task-entities-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [com.biffweb :refer [test-xtdb-node]]
   [tasks.task-entities :as task-entities]
   [tech.jgood.gleanmo :as main]
   [tech.jgood.gleanmo.db.mutations :as mutations]
   [xtdb.api :as xt])
  (:import
   [java.util UUID]))

(defn- get-context
  [node]
  {:biff.xtdb/node  node,
   :biff/db         (xt/db node),
   :biff/malli-opts #'main/malli-opts})

(defn- seed!
  "A user with one project and one task. Returns their ids."
  [ctx]
  (let [user-id               (UUID/randomUUID)
        [project-id task-id] (mutations/create-entities!
                              ctx
                              [{:entity-key :project
                                :data {:user/id user-id :project/label "garden"}}
                               {:entity-key :task
                                :data {:user/id user-id :task/label "Weed"
                                       :task/state :now}}])]
    {:user-id user-id :project-id project-id :task-id task-id}))

(defn- resolve-plan
  [node user-id plan]
  (task-entities/resolve-plan (xt/db node) user-id (java.time.Instant/now) plan))

(deftest plan-errors-test
  (testing "well-formed plans pass"
    (is (empty? (task-entities/plan-errors
                 [{:op :create :type :project :ref :errands
                   :set {:project/label "errands"}}
                  {:op :create :set {:task/label "Buy stamps" :task/state :later
                                     :task/project-id :errands}}]))))
  (testing "a ref that names no created project is rejected"
    (is (seq (task-entities/plan-errors
              [{:op :create :set {:task/label "Buy stamps" :task/state :later
                                  :task/project-id :errands}}]))))
  (testing "creates take no id and only their own type's keys"
    (is (seq (task-entities/plan-errors
              [{:op :create :id (UUID/randomUUID)
                :set {:task/label "x" :task/state :now}}])))
    (is (seq (task-entities/plan-errors
              [{:op :create :type :project :set {:task/label "x"}}]))))
  (testing "system keys stay off limits"
    (is (seq (task-entities/plan-errors
              [{:op :create :set {:task/label "x" :task/state :now
                                  :task/done-at (java.time.Instant/now)}}])))))

(deftest create-and-update-in-one-plan-test
  (with-open [node (test-xtdb-node [])]
    (let [ctx                         (get-context node)
          {:keys [user-id task-id]}   (seed! ctx)
          plan                        [{:op :create :type :project :ref :errands
                                        :set {:project/label "errands"}}
                                       {:op :create
                                        :set {:task/label "Buy stamps"
                                              :task/state :done
                                              :task/project-id :errands}}
                                       {:op :update :id task-id :label "Weed"
                                        :set {:task/project-id :errands}}]
          resolved                    (resolve-plan node user-id plan)
          [project-id new-task-id _]  (mapv :id resolved)]
      (is (every? (comp empty? :errors) resolved))
      (task-entities/commit-plan! ctx resolved)
      (let [db (xt/db node)]
        (is (= "errands" (:project/label (xt/entity db project-id))))
        (is (= project-id (:task/project-id (xt/entity db new-task-id))))
        (is (some? (:task/done-at (xt/entity db new-task-id))))
        (is (= user-id (:user/id (xt/entity db new-task-id))))
        (is (= project-id (:task/project-id (xt/entity db task-id))))))))

(deftest resolve-errors-test
  (with-open [node (test-xtdb-node [])]
    (let [{:keys [user-id task-id]} (seed! (get-context node))
          errors                    (fn [plan]
                                      (mapcat :errors
                                              (resolve-plan node user-id plan)))]
      (testing "a duplicate project label is refused"
        (is (seq (errors [{:op :create :type :project
                           :set {:project/label " Garden "}}]))))
      (testing "a project id that doesn't exist is refused"
        (is (seq (errors [{:op :update :id task-id
                           :set {:task/project-id (UUID/randomUUID)}}]))))
      (testing "a label mismatch is refused"
        (is (seq (errors [{:op :update :id task-id :label "Mow"
                           :set {:task/state :done}}]))))
      (testing "a create that fails the schema is refused"
        (is (seq (errors [{:op :create :set {:task/label "x"}}])))))))
