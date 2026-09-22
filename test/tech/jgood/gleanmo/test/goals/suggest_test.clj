(ns tech.jgood.gleanmo.test.goals.suggest-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [tech.jgood.gleanmo.goals.registry :as registry]
   [tech.jgood.gleanmo.goals.suggest :as suggest])
  (:import
   [java.time LocalDate LocalDateTime ZoneId]))

(def ^:private detroit (ZoneId/of "America/Detroit"))

(defn- date [s] (LocalDate/parse s))

(defn- at
  [s]
  (.toInstant (.atZone (LocalDateTime/parse s) detroit)))

(defn- m [source measure aggregation]
  (registry/measurement {:goal/source source :goal/measure measure
                         :goal/aggregation aggregation}))

(def ^:private as-of (at "2027-01-06T12:00"))

(defn- goal [overrides]
  (merge {:goal/timing :dated :goal/time-zone "America/Detroit"
          :goal/starts-on (date "2027-01-01") :goal/ends-on (date "2027-12-31")}
         overrides))

(defn- rep-line [s reps]
  {:id (random-uuid) :at (at s) :relations #{} :reps reps})

(deftest unsupported-goals-test
  (is (nil? (suggest/suggestion (goal {:goal/timing :open-ended})
                                (m :exercise-line :reps :total) [] as-of)))
  (is (nil? (suggest/suggestion (goal {})
                                (m :reading-log :book-completion :completion)
                                [] as-of)))
  (testing "no history"
    (is (nil? (suggest/suggestion (goal {}) (m :exercise-line :reps :total)
                                  [] as-of)))))

(deftest dated-total-test
  (let [records (for [d (range 0 365 5)]
                  (rep-line (str (.plusDays (date "2026-01-06") d) "T09:00") 20))
        s       (suggest/suggestion (goal {}) (m :exercise-line :reps :total)
                                    records as-of)]
    (testing "rate over the goal length plus 15%, two significant figures"
      ;; 73 lines × 20 = 1460 reps over 365 days; × 1.15 = 1679.
      (is (= :dated (:kind s)))
      (is (= 1460 (:amount s)))
      (is (= 365 (:days s)))
      (is (== 1700 (:target s))))
    (testing "a shorter goal scales the rate"
      (is (== 190 (:target (suggest/suggestion
                            (goal {:goal/ends-on (date "2027-02-11")})
                            (m :exercise-line :reps :total) records as-of)))))
    (testing "an end date is assumed only when missing"
      (is (nil? (:assumed-ends-on s)))
      (let [s (suggest/suggestion (goal {:goal/ends-on nil})
                                  (m :exercise-line :reps :total) records as-of)]
        (is (= (date "2027-12-31") (:assumed-ends-on s)))
        (is (== 365 (:period-days s)))
        (is (== 1700 (:target s))))))
  (testing "today and records before the window are excluded"
    (let [s (suggest/suggestion (goal {}) (m :exercise-line :reps :total)
                                [(rep-line "2025-06-01T09:00" 1000)
                                 (rep-line "2026-11-01T09:00" 100)
                                 (rep-line "2027-01-06T09:00" 1000)]
                                as-of)]
      (is (= 100 (:amount s)))
      (is (= 66 (:days s)))))
  (testing "short history divides by at least 28 days"
    ;; 50 / 28 × 365 × 1.15 ≈ 750.
    (let [s (suggest/suggestion (goal {}) (m :exercise-line :reps :total)
                                [(rep-line "2027-01-02T09:00" 50)] as-of)]
      (is (= 28 (:days s)))
      (is (== 750 (:target s))))))

(deftest weekly-total-test
  (let [;; Ten weeks from Monday 2026-10-26 through the week of 2026-12-28:
        ;; weekly counts 1..10, one log per count.
        records (for [w (range 10), i (range (inc w))]
                  {:id (random-uuid) :relations #{}
                   :at (at (str (.plusDays (date "2026-10-26") (* 7 w)) "T0" i ":00"))})
        s       (suggest/suggestion (goal {:goal/timing :weekly})
                                    (m :habit-log :records :total) records as-of)]
    (is (= :weekly (:kind s)))
    (is (= 10 (:weeks s)))
    (is (== 6 (:target s)))
    (is (= 5 (:hits s))))
  (testing "one whole week is enough"
    (let [s (suggest/suggestion (goal {:goal/timing :weekly})
                                (m :habit-log :records :total)
                                [{:id (random-uuid) :relations #{}
                                  :at (at "2026-12-30T09:00")}]
                                as-of)]
      (is (= 1 (:weeks s)))
      (is (== 1 (:target s)))))
  (testing "the current week does not count"
    (is (nil? (suggest/suggestion (goal {:goal/timing :weekly})
                                  (m :habit-log :records :total)
                                  [{:id (random-uuid) :relations #{}
                                    :at (at "2027-01-05T09:00")}]
                                  as-of)))))

(deftest duration-total-test
  (testing "hours round in the input unit"
    (let [records (for [d (range 0 364 7)]
                    (let [b (at (str (.plusDays (date "2026-01-07") d) "T09:00"))]
                      {:id (random-uuid) :relations #{} :at b
                       :end (.plusSeconds b 5400) :open? false}))
          s       (suggest/suggestion (goal {}) (m :project-log :duration :total)
                                      records as-of)]
      ;; 52 × 1.5 h = 78 h over 364 days → 78/364 × 365 × 1.15 ≈ 89.9 h.
      (is (== 90 (:display s)))
      (is (== (* 90 3600) (:target s))))))

(deftest best-test
  (let [s (suggest/suggestion (goal {}) (m :exercise-line :weight :best)
                              [{:id (random-uuid) :relations #{} :reps 5
                                :weight 60 :weight-unit :kg
                                :at (at "2026-12-01T09:00")}]
                              as-of)]
    (is (= :best (:kind s)))
    (is (== 63 (:target s)))))

(deftest relation-scope-test
  (let [a (random-uuid)
        records [(assoc (rep-line "2026-11-01T09:00" 100) :relations #{a})
                 (rep-line "2026-11-02T09:00" 900)]
        s (suggest/suggestion (goal {:goal/exercise-ids #{a}})
                              (m :exercise-line :reps :total) records as-of)]
    (is (= 100 (:amount s)))))
