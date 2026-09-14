(ns tech.jgood.gleanmo.test.goals.validation-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [com.biffweb :refer [test-xtdb-node]]
   [tech.jgood.gleanmo :as main]
   [tech.jgood.gleanmo.db.mutations :as mutations]
   [tech.jgood.gleanmo.goals.registry :as registry]
   [tech.jgood.gleanmo.goals.validation :as v]
   [tech.jgood.gleanmo.schema :as schema-registry]
   [tech.jgood.gleanmo.schema.rules :as rules]
   [xtdb.api :as xt])
  (:import
   [java.time LocalDate]))

(defn- date [s] (LocalDate/parse s))

(def ^:private base
  {:goal/label       "Reading"
   :goal/source      :reading-log
   :goal/measure     :duration
   :goal/aggregation :total
   :goal/target      36000.0
   :goal/timing      :dated
   :goal/starts-on   (date "2026-07-01")
   :goal/ends-on     (date "2026-10-31")
   :goal/time-zone   "America/Detroit"})

(defn- errors-for [goal] (set (keys (v/goal-errors goal))))

(deftest registry-matches-schema-test
  (let [enum-values (fn [k]
                      (->> schema-registry/schema :goal rest rest
                           (some #(when (= k (first %)) (last %)))
                           rest set))]
    (is (every? (enum-values :goal/source) (map :source registry/measurements)))
    (is (every? (enum-values :goal/measure) (map :measure registry/measurements)))
    (is (= 13 (count registry/measurements)))))

(deftest allowlist-test
  (is (empty? (v/goal-errors base)))
  (is (contains? (errors-for (assoc base :goal/measure :reps)) :goal/measure))
  (is (contains? (errors-for (assoc base :goal/source :habit-log)) :goal/measure))
  (testing "best goals cannot reset weekly"
    (is (contains? (errors-for (assoc base
                                      :goal/source :exercise-line
                                      :goal/measure :weight
                                      :goal/aggregation :best
                                      :goal/timing :weekly
                                      :goal/ends-on nil))
                   :goal/timing))))

(deftest target-test
  (is (contains? (errors-for (dissoc base :goal/target)) :goal/target))
  (is (contains? (errors-for (assoc base :goal/target 0)) :goal/target))
  (is (contains? (errors-for (assoc base :goal/target ##Inf)) :goal/target))
  (testing "counts need whole numbers; durations may be fractional"
    (is (contains? (errors-for (assoc base :goal/source :habit-log
                                      :goal/measure :records
                                      :goal/target 2.5))
                   :goal/target))
    (is (empty? (v/goal-errors (assoc base :goal/target 1.5))))
    (is (contains? (errors-for (assoc base :goal/source :habit-log
                                      :goal/measure :records
                                      :goal/target 10
                                      :goal/threshold-step 0.5))
                   :goal/threshold-step))))

(deftest filter-test
  (is (contains? (errors-for (assoc base :goal/book-ids #{})) :goal/book-ids))
  (is (contains? (errors-for (assoc base :goal/project-ids #{(random-uuid)}))
                 :goal/project-ids))
  (is (empty? (v/goal-errors (assoc base :goal/book-ids #{(random-uuid)})))))

(deftest completion-test
  (let [goal (-> base
                 (assoc :goal/measure :book-completion
                        :goal/aggregation :completion
                        :goal/book-ids #{(random-uuid)})
                 (dissoc :goal/target))]
    (is (empty? (v/goal-errors goal)))
    (is (empty? (v/goal-errors (assoc goal :goal/timing :open-ended
                                      :goal/ends-on nil
                                      :goal/progress-measure :audio))))
    (is (contains? (errors-for (assoc goal :goal/target 1)) :goal/target))
    (is (contains? (errors-for (assoc goal :goal/book-ids
                                      (set [(random-uuid) (random-uuid)])))
                   :goal/book-ids))
    (is (contains? (errors-for (assoc goal :goal/timing :weekly)) :goal/timing))
    (is (contains? (errors-for (assoc base :goal/progress-measure :pages))
                   :goal/progress-measure))))

(deftest dates-and-zone-test
  (is (contains? (errors-for (dissoc base :goal/ends-on)) :goal/ends-on))
  (is (contains? (errors-for (assoc base :goal/timing :open-ended)) :goal/ends-on))
  (is (empty? (v/goal-errors (assoc base :goal/timing :weekly))))
  (is (empty? (v/goal-errors (assoc base :goal/timing :weekly :goal/ends-on nil))))
  (is (contains? (errors-for (assoc base :goal/ends-on (date "2026-06-30")))
                 :goal/ends-on))
  (is (contains? (errors-for (assoc base :goal/time-zone "Mars/Olympus"))
                 :goal/time-zone))
  (is (contains? (errors-for (assoc base :goal/time-zone "+05:00"))
                 :goal/time-zone)))

(deftest exercise-duration-rule-test
  (is (empty? (rules/write-errors :exercise-line
                                  {:exercise-line/duration-seconds 42.5} {})))
  (is (seq (rules/write-errors :exercise-line
                               {:exercise-line/duration-seconds 0} {})))
  (is (seq (rules/write-errors :exercise-line
                               {:exercise-line/duration-seconds ##NaN} {}))))

(defn- ctx [node]
  {:biff.xtdb/node  node
   :biff/db         (xt/db node)
   :biff/malli-opts #'main/malli-opts})

(deftest write-boundary-test
  (with-open [node (test-xtdb-node [])]
    (let [user    (random-uuid)
          other   (random-uuid)
          book    (mutations/create-entity! (ctx node)
                                            {:entity-key :book
                                             :data {:user/id user :book/title "Mine"}})
          foreign (mutations/create-entity! (ctx node)
                                            {:entity-key :book
                                             :data {:user/id other :book/title "Theirs"}})
          goal    (assoc base :user/id user :goal/book-ids #{book})]
      (testing "a valid goal writes"
        (let [id (mutations/create-entity! (ctx node) {:entity-key :goal :data goal})]
          (is (= "Reading" (:goal/label (xt/entity (xt/db node) id))))
          (testing "updates are checked against the merged document"
            (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Invalid goal"
                                  (mutations/update-entity!
                                   (ctx node)
                                   {:entity-key :goal :entity-id id
                                    :data {:goal/ends-on :db/dissoc}}))))
          (testing "archiving is an ordinary validated update"
            (mutations/update-entity! (ctx node)
                                      {:entity-key :goal :entity-id id
                                       :data {:goal/archived true}})
            (is (true? (:goal/archived (xt/entity (xt/db node) id)))))))
      (testing "another user's book is rejected without a write"
        (try
          (mutations/create-entity! (ctx node)
                                    {:entity-key :goal
                                     :data (assoc goal :goal/book-ids #{foreign})})
          (is false "expected a rule violation")
          (catch clojure.lang.ExceptionInfo e
            (is (mutations/invalid-write? e))
            (is (contains? (:errors (ex-data e)) :goal/book-ids))))))))
