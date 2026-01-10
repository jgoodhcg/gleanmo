(ns tech.jgood.gleanmo.test.app.task-test
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [com.biffweb :as biff :refer [test-xtdb-node]]
   [tech.jgood.gleanmo :as main]
   [tech.jgood.gleanmo.app.task-focus :as task-focus]
   [tech.jgood.gleanmo.db.mutations :as mutations]
   [xtdb.api :as xt])
  (:import
   [java.time LocalDate]
   [java.util UUID]))

(defn get-context
  [node]
  {:biff.xtdb/node  node
   :biff/db         (xt/db node)
   :biff/malli-opts #'main/malli-opts})

(defn- rendered-text
  [hiccup]
  (->> (tree-seq coll? seq hiccup)
       (filter string?)
       (str/join " ")))

(defn- focus-text
  [node user-id params]
  (let [ctx  {:biff/db (xt/db node)
              :session {:uid user-id}
              :params  params}
        view (task-focus/focus-view ctx)]
    (rendered-text view)))

(deftest focus-default-now-test
  (testing "defaults to now tasks and respects snooze/unrelated users"
    (with-open [node (test-xtdb-node [])]
      (let [user-id        (UUID/randomUUID)
            other-user-id  (UUID/randomUUID)
            ctx            (get-context node)
            today          (LocalDate/now)]
        (mutations/create-entity!
         ctx
         {:entity-key :task
          :data {:user/id user-id
                 :task/label "Now Task"
                 :task/state :now}})
        (mutations/create-entity!
         ctx
         {:entity-key :task
          :data {:user/id user-id
                 :task/label "Later Task"
                 :task/state :later}})
        (mutations/create-entity!
         ctx
         {:entity-key :task
          :data {:user/id user-id
                 :task/label "Snoozed Now Task"
                 :task/state :now
                 :task/snooze-until (.plusDays today 2)}})
        (mutations/create-entity!
         ctx
         {:entity-key :task
          :data {:user/id other-user-id
                 :task/label "Other User Task"
                 :task/state :now}})
        (xt/sync node)
        (let [text (focus-text node user-id {})]
          (is (str/includes? text "Now Task"))
          (is (not (str/includes? text "Later Task")))
          (is (not (str/includes? text "Snoozed Now Task")))
          (is (not (str/includes? text "Other User Task"))))))))

(deftest focus-any-state-test
  (testing "any state includes multiple states"
    (with-open [node (test-xtdb-node [])]
      (let [user-id (UUID/randomUUID)
            ctx     (get-context node)]
        (mutations/create-entity!
         ctx
         {:entity-key :task
          :data {:user/id user-id
                 :task/label "Now Task"
                 :task/state :now}})
        (mutations/create-entity!
         ctx
         {:entity-key :task
          :data {:user/id user-id
                 :task/label "Later Task"
                 :task/state :later}})
        (mutations/create-entity!
         ctx
         {:entity-key :task
          :data {:user/id user-id
                 :task/label "Snoozed Task"
                 :task/state :snoozed}})
        (xt/sync node)
        (let [text (focus-text node user-id {:state "any"})]
          (is (str/includes? text "Now Task"))
          (is (str/includes? text "Later Task"))
          (is (str/includes? text "Snoozed Task")))))))

(deftest focus-filter-combination-test
  (testing "project/domain/search/due filters narrow the list"
    (with-open [node (test-xtdb-node [])]
      (let [user-id   (UUID/randomUUID)
            ctx       (get-context node)
            today     (LocalDate/now)
            tomorrow  (.plusDays today 1)
            project-a (mutations/create-entity!
                       ctx
                       {:entity-key :project
                        :data {:user/id user-id
                               :project/label "Project Alpha"}})
            project-b (mutations/create-entity!
                       ctx
                       {:entity-key :project
                        :data {:user/id user-id
                               :project/label "Project Beta"}})]
        (mutations/create-entity!
         ctx
         {:entity-key :task
          :data {:user/id user-id
                 :task/label "Write report"
                 :task/state :now
                 :task/domain :work
                 :task/project-id project-a
                 :task/due-on today}})
        (mutations/create-entity!
         ctx
         {:entity-key :task
          :data {:user/id user-id
                 :task/label "Clean kitchen"
                 :task/state :now
                 :task/domain :home
                 :task/project-id project-b
                 :task/due-on tomorrow}})
        (mutations/create-entity!
         ctx
         {:entity-key :task
          :data {:user/id user-id
                 :task/label "Write notes"
                 :task/state :later
                 :task/domain :work
                 :task/project-id project-a
                 :task/due-on today}})
        (xt/sync node)
        (let [params {:project (str project-a)
                      :domain "work"
                      :search "report"
                      :due-on (str today)
                      :state  "now"}
              text (focus-text node user-id params)]
          (is (str/includes? text "Write report"))
          (is (not (str/includes? text "Clean kitchen")))
          (is (not (str/includes? text "Write notes"))))))))

(deftest focus-snoozed-only-test
  (testing "snoozed filter shows only snoozed tasks"
    (with-open [node (test-xtdb-node [])]
      (let [user-id (UUID/randomUUID)
            ctx     (get-context node)]
        (mutations/create-entity!
         ctx
         {:entity-key :task
          :data {:user/id user-id
                 :task/label "Snoozed Task"
                 :task/state :snoozed}})
        (mutations/create-entity!
         ctx
         {:entity-key :task
          :data {:user/id user-id
                 :task/label "Now Task"
                 :task/state :now}})
        (xt/sync node)
        (let [params {:state "any"
                      :snoozed "only"}
              text   (focus-text node user-id params)]
          (is (str/includes? text "Snoozed Task"))
          (is (not (str/includes? text "Now Task"))))))))
