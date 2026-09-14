(ns tech.jgood.gleanmo.test.goals.dashboard-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [com.biffweb :refer [test-xtdb-node]]
   [tech.jgood.gleanmo :as main]
   [tech.jgood.gleanmo.db.mutations :as mutations]
   [tech.jgood.gleanmo.db.queries :as queries]
   [tech.jgood.gleanmo.goals.dashboard :as dashboard]
   [xtdb.api :as xt])
  (:import
   [java.time LocalDate LocalDateTime ZoneId]))

(def ^:private utc (ZoneId/of "UTC"))

(defn- at [s] (.toInstant (.atZone (LocalDateTime/parse s) utc)))

(defn- ctx [node]
  {:biff.xtdb/node  node
   :biff/db         (xt/db node)
   :biff/malli-opts #'main/malli-opts})

(defn- create! [node entity-key data]
  (mutations/create-entity! (ctx node) {:entity-key entity-key :data data}))

(def ^:private visible {:show-sensitive false :show-archived false})

(deftest goal-source-records-test
  (with-open [node (test-xtdb-node [])]
    (let [user      (random-uuid)
          other     (random-uuid)
          project   (create! node :project {:user/id user :project/label "Mine"})
          secret    (create! node :project {:user/id user :project/label "Secret"
                                            :project/sensitive true})
          theirs    (create! node :project {:user/id other :project/label "Theirs"})
          plog      (fn [owner p b e]
                      (create! node :project-log
                               {:user/id owner :project-log/project-id p
                                :project-log/beginning (at b) :project-log/end (at e)
                                :project-log/time-zone "UTC"}))
          kept      (plog user project "2026-07-02T10:00" "2026-07-02T11:00")
          _         (plog user secret "2026-07-02T12:00" "2026-07-02T13:00")
          _         (plog other theirs "2026-07-02T10:00" "2026-07-02T11:00")
          _         (plog user project "2026-06-01T10:00" "2026-06-01T11:00")
          deleted   (plog user project "2026-07-03T10:00" "2026-07-03T11:00")
          _         (mutations/soft-delete-entity! (ctx node) {:entity-key :project-log
                                                               :entity-id deleted})
          request   {:source :project-log :since (at "2026-07-01T00:00")
                     :until (at "2026-08-01T00:00") :user-settings visible}
          records   (queries/goal-source-records (xt/db node) user request)]
      (testing "owner, window, deletion, and related-entity visibility"
        (is (= [kept] (mapv :id records)))
        (is (= #{project} (:relations (first records))))
        (is (false? (:open? (first records)))))
      (testing "sensitive parents appear when the user shows them"
        (is (= 2 (count (queries/goal-source-records
                         (xt/db node) user
                         (assoc request :user-settings
                                {:show-sensitive true :show-archived false}))))))
      (testing "relation scope"
        (is (empty? (queries/goal-source-records
                     (xt/db node) user (assoc request :relation-ids #{theirs}))))))))

(deftest exercise-line-records-test
  (with-open [node (test-xtdb-node [])]
    (let [user     (random-uuid)
          exercise (create! node :exercise {:user/id user :exercise/label "Pull-up"})
          other-ex (create! node :exercise {:user/id user :exercise/label "Squat"})
          session  (create! node :exercise-session
                            {:user/id user
                             :exercise-session/beginning (at "2026-07-02T09:00")})
          in-set   (create! node :exercise-set
                            {:user/id user :exercise-set/session-id session
                             :exercise-set/beginning (at "2026-07-02T09:05")})
          old-set  (create! node :exercise-set
                            {:user/id user :exercise-set/session-id session
                             :exercise-set/beginning (at "2026-06-02T09:05")})
          line     (fn [s ex reps]
                     (create! node :exercise-line
                              {:user/id user :exercise-line/set-id s
                               :exercise-line/exercise-id ex :exercise-line/reps reps}))
          kept     (line in-set exercise 10)
          _        (line in-set other-ex 5)
          _        (line old-set exercise 8)
          records  (queries/goal-source-records
                    (xt/db node) user
                    {:source :exercise-line :since (at "2026-07-01T00:00")
                     :until (at "2026-08-01T00:00") :relation-ids #{exercise}
                     :user-settings visible})]
      (is (= [kept] (mapv :id records)))
      (is (= (at "2026-07-02T09:05") (:at (first records))) "dated by the parent set")
      (is (= 10 (:reps (first records)))))))

(deftest dashboard-test
  (with-open [node (test-xtdb-node [])]
    (let [user  (random-uuid)
          book  (create! node :book {:user/id user :book/title "Odyssey"
                                     :book/total-pages 300})
          base  {:user/id user :goal/time-zone "UTC"
                 :goal/starts-on (LocalDate/parse "2026-07-01")}
          _     (create! node :goal (merge base {:goal/label "Reading"
                                                 :goal/source :reading-log
                                                 :goal/measure :duration
                                                 :goal/aggregation :total
                                                 :goal/target 36000.0
                                                 :goal/timing :open-ended}))
          _     (create! node :goal (merge base {:goal/label "Finish"
                                                 :goal/source :reading-log
                                                 :goal/measure :book-completion
                                                 :goal/aggregation :completion
                                                 :goal/timing :open-ended
                                                 :goal/book-ids #{book}}))
          _     (create! node :goal (merge base {:goal/label "Old"
                                                 :goal/source :habit-log
                                                 :goal/measure :records
                                                 :goal/aggregation :total
                                                 :goal/target 10
                                                 :goal/timing :open-ended
                                                 :goal/archived true}))
          _     (create! node :reading-log
                         {:user/id user :reading-log/book-id book
                          :reading-log/beginning (at "2026-07-02T10:00")
                          :reading-log/end (at "2026-07-02T11:00")
                          :reading-log/time-zone "UTC"
                          :reading-log/end-page 120
                          :reading-log/finished? true})
          data  (dashboard/dashboard (xt/db node) user
                                     {:cutoff-date (LocalDate/parse "2026-07-10")
                                      :user-settings visible})
          by-label (into {} (map (juxt (comp :goal/label :goal) identity))
                         (:active data))]
      (is (= #{"Reading" "Finish"} (set (keys by-label))))
      (is (= ["Old"] (mapv :goal/label (:archived data))))
      (is (= 3600.0 (get-in by-label ["Reading" :progress :logged])))
      (is (= (LocalDate/parse "2026-07-02")
             (get-in by-label ["Finish" :book-progress :completion :date])))
      (is (= 120 (get-in by-label ["Finish" :book-progress :measures :pages
                                   :latest :value])))
      (is (= 84 (count (get-in by-label ["Reading" :history])))))))
