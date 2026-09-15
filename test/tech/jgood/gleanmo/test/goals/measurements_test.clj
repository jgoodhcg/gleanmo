(ns tech.jgood.gleanmo.test.goals.measurements-test
  "Registry-wide contracts from persisted source records to dashboard results."
  (:require
   [clojure.test :refer [deftest is testing]]
   [com.biffweb :refer [test-xtdb-node]]
   [tech.jgood.gleanmo :as main]
   [tech.jgood.gleanmo.db.mutations :as mutations]
   [tech.jgood.gleanmo.goals.dashboard :as dashboard]
   [tech.jgood.gleanmo.goals.registry :as registry]
   [xtdb.api :as xt])
  (:import
   [java.time Instant LocalDate]))

(def ^:private expectations
  ;; Explicit results are independent of the calculator and registry metadata.
  ;; Values are [both records, after deleting the second record].
  {:project-log/duration-total [4500.0 1800.0]
   :reading-log/duration-total [4500.0 1800.0]
   :meditation-log/duration-total [4500.0 1800.0]
   :meditation-log/records-total [2.0 1.0]
   :habit-log/records-total [2.0 1.0]
   :exercise-session/records-total [2.0 1.0]
   :boulder-session/records-total [2.0 1.0]
   :boulder-attempt/attempts-total [4.0 3.0]
   :boulder-attempt/duration-total [4500.0 1800.0]
   :exercise-line/reps-total [13.0 5.0]
   :exercise-line/weight-best [90.718474 80.0]
   :exercise-line/duration-best [60.0 45.0]
   :reading-log/book-completion-completion [true false]})

(defn- context [node]
  {:biff.xtdb/node node :biff/db (xt/db node) :biff/malli-opts #'main/malli-opts})

(defn- create! [node owner entity data]
  (mutations/create-entity! (context node)
                            {:entity-key entity :data (assoc data :user/id owner)}))

(defn- seed-sources!
  "Create real source documents and their parents, with explicit relation fields."
  [node owner & [only-sources]]
  (let [create #(create! node owner %1 %2)
        project (create :project {:project/label "Project"})
        book (create :book {:book/title "Book" :book/total-pages 200})
        location (create :location {:location/label "Room"})
        meditation (create :meditation {:meditation/label "Practice"})
        habit (create :habit {:habit/label "Walk"})
        exercise (create :exercise {:exercise/label "Hold"})
        problem (create :boulder-problem {:boulder-problem/gym "Gym"
                                          :boulder-problem/difficulty "Easy"})
        exercise-session (create :exercise-session
                                 {:exercise-session/beginning (Instant/parse "2026-07-07T08:00:00Z")})
        boulder-session (create :boulder-session
                                {:boulder-session/beginning (Instant/parse "2026-07-07T08:00:00Z")
                                 :boulder-session/gym "Gym"})
        sources
        {:project-log {:base {:project-log/project-id project :project-log/time-zone "UTC"}
                       :goal {:goal/project-ids #{project}}}
         :reading-log {:base {:reading-log/book-id book :reading-log/time-zone "UTC"}
                       :goal {:goal/book-ids #{book}}}
         :meditation-log {:base {:meditation-log/type-id meditation
                                 :meditation-log/location-id location
                                 :meditation-log/time-zone "UTC"
                                 :meditation-log/position :sitting
                                 :meditation-log/guided false :meditation-log/interrupted false}
                          :goal {:goal/meditation-ids #{meditation}}}
         :habit-log {:base {:habit-log/habit-ids #{habit} :habit-log/time-zone "UTC"}
                     :goal {:goal/habit-ids #{habit}}}
         :exercise-session {:base {}}
         :boulder-session {:base {:boulder-session/gym "Gym"}}
         :boulder-attempt {:base {:boulder-attempt/session-id boulder-session
                                  :boulder-attempt/problem-id problem :boulder-attempt/sent false}}
         :exercise-line {:base {:exercise-line/exercise-id exercise}
                         :goal {:goal/exercise-ids #{exercise}}}}]
    (into {}
          (for [[source {:keys [base] :as fixture}]
                (if only-sources (select-keys sources only-sources) sources)]
            [source
             (assoc fixture :ids
                    (mapv
                     (fn [i]
                       (let [beginning (Instant/parse (if (zero? i)
                                                        "2026-07-07T10:00:00Z"
                                                        "2026-07-08T10:00:00Z"))
                             end (.plusSeconds beginning (if (zero? i) 1800 2700))
                             data
                             (case source
                               :habit-log {:habit-log/timestamp beginning}
                               :exercise-line
                               {:exercise-line/set-id
                                (create :exercise-set {:exercise-set/session-id exercise-session
                                                       :exercise-set/beginning beginning
                                                       :exercise-set/end end})
                                :exercise-line/reps (if (zero? i) 5 8)
                                :exercise-line/weight (if (zero? i) 80 200)
                                :exercise-line/weight-unit (if (zero? i) :kg :lbs)
                                :exercise-line/duration-seconds (if (zero? i) 45 60)}
                               {(keyword (name source) "beginning") beginning
                                (keyword (name source) "end") end})
                             data (cond-> data
                                    (= source :reading-log)
                                    (assoc :reading-log/end-page (if (zero? i) 50 150)
                                           :reading-log/finished? (= i 1))
                                    (and (= source :boulder-attempt) (zero? i))
                                    (assoc :boulder-attempt/attempts 3))]
                         (create source (merge base data))))
                     [0 1]))]))))

(deftest every-registered-measurement-has-a-fixture-test
  (is (= (set (map :id registry/measurements)) (set (keys expectations)))
      "Add explicit expected results whenever a measurement is added or removed."))

(deftest persisted-measurements-through-dashboard-test
  (with-open [node (test-xtdb-node [])]
    (let [owner (random-uuid)
          fixtures (seed-sources! node owner)
          ;; Identical foreign history must not affect any goal.
          _ (seed-sources! node (random-uuid))
          ;; Owned records outside selected relations must also be excluded.
          _ (seed-sources! node owner [:project-log :reading-log :meditation-log
                                       :habit-log :exercise-line])
          goals (vec
                 (for [{:keys [id source measure aggregation timings]} registry/measurements
                       timing (sort timings)]
                   {:measurement-id id
                    :source source
                    :timing timing
                    :id (create! node owner :goal
                                 (merge {:goal/label (str id " " timing)
                                         :goal/source source :goal/measure measure
                                         :goal/aggregation aggregation :goal/timing timing
                                         :goal/starts-on (LocalDate/parse "2026-07-06")
                                         :goal/time-zone "UTC"}
                                        (:goal (fixtures source))
                                        (when (not= timing :open-ended)
                                          {:goal/ends-on (LocalDate/parse "2026-07-12")})
                                        (when (not= aggregation :completion)
                                          {:goal/target 10000})))}))
          read-dashboard #(dashboard/dashboard
                           (xt/db node) owner
                           {:now (Instant/parse "2026-07-10T12:00:00Z")
                            :user-settings {:show-sensitive false :show-archived false}})
          check-results
          (fn [phase]
            (let [result (read-dashboard)
                  entries (into {} (map (juxt #(get-in % [:goal :xt/id]) identity))
                                (:active result))]
              (is (= (set (map :id goals)) (set (keys entries))))
              (is (zero? (:hidden-count result)))
              (doseq [{:keys [id source measurement-id timing]} goals]
                (testing (str measurement-id " / " timing " / phase " phase)
                  (let [entry (entries id)
                        expected (get-in expectations [measurement-id phase])]
                    (is (some? expected) "Every measurement needs an independent expectation.")
                    (is (= (LocalDate/parse "2026-07-10") (:cutoff-date entry)))
                    (if (= :reading-log/book-completion-completion measurement-id)
                      (do
                        (is (= expected (some? (get-in entry [:book-progress :completion]))))
                        (is (= (if (zero? phase) 150 50)
                               (get-in entry [:book-progress :measures :pages :latest :value])))
                        (when expected
                          (is (= (second (:ids (fixtures source)))
                                 (get-in entry [:book-progress :completion :log-id])))))
                      (is (< (Math/abs (- (double expected)
                                          (double (or (get-in entry [:progress :logged]) -1))))
                             1.0e-6)))
                    (is (= (- 2 phase) (count (filter #(pos? (or (:value %) 0))
                                                      (:history entry))))))))))]
      (check-results 0)
      ;; A source correction must invalidate totals, maxima, and completion.
      (doseq [[source {:keys [ids]}] fixtures]
        (mutations/soft-delete-entity! (context node)
                                       {:entity-key source :entity-id (second ids)}))
      (check-results 1))))
