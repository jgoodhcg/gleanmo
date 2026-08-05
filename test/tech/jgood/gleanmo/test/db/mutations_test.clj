(ns tech.jgood.gleanmo.test.db.mutations-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [com.biffweb :as biff :refer [test-xtdb-node]]
   [tech.jgood.gleanmo :as main]
   [tech.jgood.gleanmo.db.mutations :as mutations]
   [tech.jgood.gleanmo.schema.meta :as sm]
   [tech.jgood.gleanmo.schema.utils :as schema-utils]
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

(deftest create-entities-test
  (testing "create-entities! submits one batch and preserves input order"
    (with-open [node (test-xtdb-node [])]
      (let [ctx      (get-context node)
            user-id  (UUID/randomUUID)
            ids      (mutations/create-entities!
                      ctx
                      [{:entity-key :habit
                        :data {:user/id user-id
                               :habit/label "First"}}
                       {:entity-key :habit
                        :data {:user/id user-id
                               :habit/label "Second"}}])
            entities (mapv #(xt/entity (xt/db node) %) ids)]
        (is (= 2 (count ids)))
        (is (= ["First" "Second"] (mapv :habit/label entities)))))))

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

;; ---------------------------------------------------------------------------
;; Running-timer flag derivation
;;
;; The point of deriving here rather than in the timer handlers is that paths
;; which never go near a handler still maintain the flag. These tests drive
;; `update-entity!` directly for exactly that reason — that is the CRUD form's
;; and `resume-set!`'s view of the world.
;; ---------------------------------------------------------------------------

(defn- session-doc
  [user-id & {:keys [beginning end]}]
  (cond-> {:user/id                    user-id,
           :exercise-session/beginning (or beginning (t/now))}
    end (assoc :exercise-session/end end)))

(defn- stored
  [node entity-id]
  (xt/entity (xt/db node) entity-id))

(deftest running-flag-on-create-test
  (testing "starting a timer writes running true; a closed interval writes no flag"
    (with-open [node (test-xtdb-node [])]
      (let [ctx     (get-context node)
            user-id (UUID/randomUUID)
            open-id (mutations/create-entity!
                     ctx {:entity-key :exercise-session,
                          :data       (session-doc user-id)})
            done-id (mutations/create-entity!
                     ctx {:entity-key :exercise-session,
                          :data       (session-doc user-id :end (t/now))})]
        (is (= true (:exercise-session/running (stored node open-id))))
        (is (not (contains? (stored node done-id) :exercise-session/running)))))))

(deftest running-flag-on-stop-test
  (testing "stopping a timer dissocs the flag rather than storing false"
    (with-open [node (test-xtdb-node [])]
      (let [ctx     (get-context node)
            user-id (UUID/randomUUID)
            id      (mutations/create-entity!
                     ctx {:entity-key :exercise-session,
                          :data       (session-doc user-id)})]
        (mutations/update-entity! ctx {:entity-key :exercise-session,
                                       :entity-id  id,
                                       :data       {:exercise-session/end
                                                    (t/now)}})
        ;; Sparse, not false — the index lookup has to stay proportional to the
        ;; number of running timers, not to history.
        (is (not (contains? (stored node id) :exercise-session/running)))))))

(deftest running-flag-on-clearing-end-test
  (testing "clearing end through update-entity! restores the flag — the
            regression this derivation exists to prevent, since the CRUD edit
            form and resume-set! reach the document without a timer handler"
    (with-open [node (test-xtdb-node [])]
      (let [ctx     (get-context node)
            user-id (UUID/randomUUID)
            id      (mutations/create-entity!
                     ctx {:entity-key :exercise-session,
                          :data       (session-doc user-id :end (t/now))})]
        (is (not (contains? (stored node id) :exercise-session/running)))
        (mutations/update-entity! ctx {:entity-key :exercise-session,
                                       :entity-id  id,
                                       :data       {:exercise-session/end
                                                    :db/dissoc}})
        (is (= true (:exercise-session/running (stored node id))))))))

(deftest running-flag-partial-update-test
  (testing "an update touching only beginning derives from the stored end, not
            from data alone — start/stop tests pass without this"
    (with-open [node (test-xtdb-node [])]
      (let [ctx     (get-context node)
            user-id (UUID/randomUUID)
            closed  (mutations/create-entity!
                     ctx {:entity-key :exercise-session,
                          :data       (session-doc user-id :end (t/now))})
            open    (mutations/create-entity!
                     ctx {:entity-key :exercise-session,
                          :data       (session-doc user-id)})]
        ;; `data` says nothing about `end`; the merged document is what decides.
        (mutations/update-entity! ctx {:entity-key :exercise-session,
                                       :entity-id  closed,
                                       :data       {:exercise-session/beginning
                                                    (t/now)}})
        (mutations/update-entity! ctx {:entity-key :exercise-session,
                                       :entity-id  open,
                                       :data       {:exercise-session/beginning
                                                    (t/now)}})
        (is (not (contains? (stored node closed) :exercise-session/running)))
        (is (= true (:exercise-session/running (stored node open))))))))

(deftest running-flag-untouched-update-test
  (testing "an update touching neither interval field leaves the flag alone"
    (with-open [node (test-xtdb-node [])]
      (let [ctx     (get-context node)
            user-id (UUID/randomUUID)
            id      (UUID/randomUUID)]
        ;; Planted with a deliberately wrong flag, straight past the
        ;; derivation: if the update re-derived, it would clear this.
        (biff/submit-tx ctx
                        [{:db/doc-type                :exercise-session,
                          :xt/id                      id,
                          ::sm/type                   :exercise-session,
                          ::sm/created-at             (t/now),
                          :user/id                    user-id,
                          :exercise-session/beginning (t/now),
                          :exercise-session/end       (t/now),
                          :exercise-session/running   true}])
        (mutations/update-entity! ctx {:entity-key :exercise-session,
                                       :entity-id  id,
                                       :data       {:exercise-session/notes
                                                    "still not a timer edit"}})
        (is (= true (:exercise-session/running (stored node id))))))))

(deftest running-flag-opt-in-is-the-schema-test
  (testing "an entity with beginning/end but no running field is untouched"
    ;; exercise-set is the deliberate exclusion: the workout screen finds its
    ;; running set through the parent-scoped `sets-for-session`, so a flag
    ;; would buy nothing and add a fourth schema to keep in sync.
    (is (nil? (schema-utils/running-flag-fields
               (schema-utils/entity-schema :exercise-set) :exercise-set)))
    (is (not (contains? @schema-utils/running-flag-entities :exercise-set)))
    (with-open [node (test-xtdb-node [])]
      (let [ctx        (get-context node)
            user-id    (UUID/randomUUID)
            session-id (mutations/create-entity!
                        ctx {:entity-key :exercise-session,
                             :data       (session-doc user-id)})
            set-id     (mutations/create-entity!
                        ctx {:entity-key :exercise-set,
                             :data {:user/id                user-id,
                                    :exercise-set/session-id session-id,
                                    :exercise-set/beginning (t/now),
                                    :exercise-set/end       (t/now)}})]
        ;; The resume-set! shape. A derivation firing here would write an
        ;; attribute the :closed schema rejects, so this would throw, not
        ;; merely assert false.
        (mutations/update-entity! ctx {:entity-key :exercise-set,
                                       :entity-id  set-id,
                                       :data       {:exercise-set/end
                                                    :db/dissoc}})
        (is (not (contains? (stored node set-id) :exercise-set/running)))))))

(deftest running-flag-entities-test
  (testing "every entity reached through active-timers-for-user opts in"
    (is (= #{:exercise-session :boulder-session
             :project-log :meditation-log :reading-log}
           (set (keys @schema-utils/running-flag-entities))))
    (is (= {:beginning-key :project-log/beginning,
            :end-key       :project-log/end,
            :running-key   :project-log/running}
           (get @schema-utils/running-flag-entities :project-log)))))
