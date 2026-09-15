(ns tech.jgood.gleanmo.test.goals.calc-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [tech.jgood.gleanmo.goals.calc :as calc]
   [tech.jgood.gleanmo.goals.registry :as registry])
  (:import
   [java.time Instant LocalDate LocalDateTime ZoneId]))

(def ^:private detroit (ZoneId/of "America/Detroit"))

(defn- date [s] (LocalDate/parse s))

(defn- at
  "Instant for a local date-time string in Detroit."
  [s]
  (.toInstant (.atZone (LocalDateTime/parse s) detroit)))

(defn- m [source measure aggregation]
  (registry/measurement {:goal/source source :goal/measure measure
                         :goal/aggregation aggregation}))

(defn- goal [overrides]
  (merge {:goal/label "g" :goal/timing :dated :goal/target 36000.0
          :goal/starts-on (date "2026-07-01") :goal/ends-on (date "2026-10-31")
          :goal/time-zone "America/Detroit"}
         overrides))

(deftest interval-union-and-split-test
  (testing "overlapping intervals count once"
    (is (= {(date "2026-07-02") 5400.0}
           (calc/daily-seconds [[(at "2026-07-02T10:00") (at "2026-07-02T11:00")]
                                [(at "2026-07-02T10:30") (at "2026-07-02T11:30")]]
                               detroit (at "2026-07-01T00:00") (at "2026-07-10T00:00")))))
  (testing "intervals split at local midnight and clip to the window"
    ;; Jul 1 gets 00:00–01:00 of the first interval (its June part is clipped)
    ;; and 23:00–24:00 of the second; the second's last half hour is Jul 2.
    (is (= {(date "2026-07-01") 7200.0 (date "2026-07-02") 1800.0}
           (calc/daily-seconds [[(at "2026-06-30T23:00") (at "2026-07-01T01:00")]
                                [(at "2026-07-01T23:00") (at "2026-07-02T00:30")]]
                               detroit (at "2026-07-01T00:00") (at "2026-07-10T00:00")))))
  (testing "a DST day splits on the zone's own midnight, not 24h steps"
    ;; 2026-03-08 springs forward: 23:00 EST to 03:00 EDT is three real hours.
    (is (= {(date "2026-03-07") 3600.0 (date "2026-03-08") 7200.0}
           (calc/daily-seconds [[(at "2026-03-07T23:00") (at "2026-03-08T03:00")]]
                               detroit (at "2026-03-01T00:00") (at "2026-03-10T00:00"))))))

(deftest window-test
  (testing "a weekly goal measures the current week and resets Monday"
    (let [g (goal {:goal/timing :weekly :goal/ends-on nil
                   :goal/starts-on (date "2026-09-09")})]
      (is (= {:start (date "2026-09-09") :end (date "2026-09-13") :partial? true
              :completed-days 3 :total-days 5 :remaining-days 2 :status :active}
             (select-keys (calc/window g (date "2026-09-12"))
                          [:start :end :partial? :completed-days :total-days
                           :remaining-days :status])))
      (is (= {:start (date "2026-09-14") :partial? false :completed-days 2}
             (select-keys (calc/window g (date "2026-09-16"))
                          [:start :partial? :completed-days])))))
  (testing "states"
    (is (= :not-started (:status (calc/window (goal {}) (date "2026-06-30")))))
    (is (= :active (:status (calc/window (goal {}) (date "2026-07-01")))))
    (is (= :ended (:status (calc/window (goal {}) (date "2026-11-01")))))
    (is (= 0 (:remaining-days (calc/window (goal {}) (date "2026-11-05")))))))

(defn- interval-record [b e]
  {:at (at b) :end (at e) :open? false :relations #{}})

(deftest numeric-progress-test
  (let [dur (m :reading-log :duration :total)]
    (testing "rates follow the version 08 formulas"
      (let [p (calc/numeric-progress
               (goal {:goal/ends-on (date "2026-07-10") :goal/target 36000.0})
               dur
               [(interval-record "2026-07-01T10:00" "2026-07-01T12:00")
                (interval-record "2026-07-02T10:00" "2026-07-02T12:00")]
               (date "2026-07-05"))]
        ;; 4 of 10 days complete, 4h logged of 10h.
        (is (= 14400.0 (:logged p)))
        (is (= 3600.0 (:average p)))
        (is (= (/ 21600.0 6) (:required p)))
        (is (= 1.0 (:ratio p)))
        (is (= 0.0 (:pace-days p)))))
    (testing "future records and open timers do not count"
      (let [p (calc/numeric-progress
               (goal {})
               dur
               [(interval-record "2026-07-05T10:00" "2026-07-05T11:00")
                {:at (at "2026-07-03T10:00") :end nil :open? true :relations #{}}]
               (date "2026-07-05"))]
        (is (= 0.0 (:logged p)))
        (is (= 0.0 (:average p)))
        (is (nil? (:ratio p)) "no ratio against a zero average")))
    (testing "not started, ended, and reached goals guard their rates"
      (let [not-started (calc/numeric-progress (goal {}) dur [] (date "2026-07-01"))
            ended       (calc/numeric-progress (goal {}) dur [] (date "2026-11-02"))
            reached     (calc/numeric-progress
                         (goal {:goal/target 3600.0}) dur
                         [(interval-record "2026-07-01T10:00" "2026-07-01T12:00")]
                         (date "2026-07-03"))]
        (is (nil? (:average not-started)))
        (is (nil? (:pace-days not-started)))
        (is (nil? (:required ended)))
        (is (true? (:reached? reached)))
        (is (nil? (:required reached)) "no required rate once reached")
        (is (nil? (:next reached)))
        (is (= 0.0 (:remaining reached)))))
    (testing "open-ended goals have no required rate or pace"
      (let [p (calc/numeric-progress
               (goal {:goal/timing :open-ended :goal/ends-on nil})
               dur [(interval-record "2026-07-01T10:00" "2026-07-01T12:00")]
               (date "2026-07-03"))]
        (is (= 7200.0 (:logged p)))
        (is (nil? (:required p)))
        (is (nil? (:pace-days p)))
        (is (= 3600.0 (:average p)))))
    (testing "weekly totals ignore last week's surplus"
      (let [p (calc/numeric-progress
               (goal {:goal/timing :weekly :goal/ends-on nil :goal/target 7200.0})
               dur
               [(interval-record "2026-09-08T10:00" "2026-09-08T20:00")
                (interval-record "2026-09-14T10:00" "2026-09-14T11:00")]
               (date "2026-09-16"))]
        (is (= 3600.0 (:logged p)))
        (is (= (date "2026-09-14") (get-in p [:window :start])))))))

(deftest point-measures-test
  (let [base {:open? false :relations #{}}]
    (testing "reps sum by the parent set's date"
      (is (= {(date "2026-07-02") 25}
             (calc/daily-values (m :exercise-line :reps :total)
                                [(assoc base :at (at "2026-07-02T09:00") :reps 10)
                                 (assoc base :at (at "2026-07-02T09:10") :reps 15)
                                 (assoc base :at (at "2026-07-02T09:20"))]
                                detroit (at "2026-07-01T00:00") (at "2026-07-10T00:00")))))
    (testing "attempts default to one and exclude open attempts"
      (is (= {(date "2026-07-02") 4}
             (calc/daily-values (m :boulder-attempt :attempts :total)
                                [(assoc base :at (at "2026-07-02T09:00") :attempts 3)
                                 (assoc base :at (at "2026-07-02T09:10"))
                                 (assoc base :at (at "2026-07-02T09:20") :open? true)]
                                detroit (at "2026-07-01T00:00") (at "2026-07-10T00:00")))))
    (testing "best weight converts pounds, needs a rep, and skips missing units"
      (let [daily (calc/daily-values (m :exercise-line :weight :best)
                                     [(assoc base :at (at "2026-07-02T09:00")
                                             :reps 1 :weight 225 :weight-unit :lbs)
                                      (assoc base :at (at "2026-07-02T09:10")
                                             :reps 3 :weight 100 :weight-unit :kg)
                                      (assoc base :at (at "2026-07-02T09:20")
                                             :reps 0 :weight 200 :weight-unit :kg)
                                      (assoc base :at (at "2026-07-02T09:30")
                                             :reps 1 :weight 300)]
                                     detroit (at "2026-07-01T00:00")
                                     (at "2026-07-10T00:00"))]
        (is (< 102.05 (get daily (date "2026-07-02")) 102.06))))
    (testing "habit logs count once each and honour the relation filter"
      (let [h1 (random-uuid) h2 (random-uuid)
            g  (goal {:goal/source :habit-log :goal/measure :records
                      :goal/habit-ids #{h1}})
            ms (m :habit-log :records :total)]
        (is (= 1 (count (calc/scoped-records
                         g ms [{:relations #{h1 h2}} {:relations #{h2}}]))))))))

(defn- log [end-str & {:as fields}]
  (merge {:xt/id (random-uuid)
          :reading-log/beginning (.minusSeconds ^Instant (at end-str) 1800)
          :reading-log/end (at end-str)}
         fields))

(deftest book-progress-test
  (let [book {:book/total-pages 300 :book/total-chapters 20}
        g    (goal {:goal/measure :book-completion :goal/aggregation :completion
                    :goal/target nil :goal/ends-on (date "2026-08-31")})]
    (testing "100% of a total without the finished flag is not complete"
      (let [p (calc/book-progress g book [(log "2026-07-02T10:00" :reading-log/end-page 300)]
                                  (date "2026-09-01"))]
        (is (nil? (:completion p)))
        (is (true? (:overdue? p)))
        (is (= 1.0 (get-in p [:measures :pages :percent])))))
    (testing "finished below the total completes, and late completion stays visible"
      (let [p (calc/book-progress g book
                                  [(log "2026-09-02T10:00" :reading-log/end-page 280
                                        :reading-log/finished? true)]
                                  (date "2026-09-05"))]
        (is (= (date "2026-09-02") (get-in p [:completion :date])))
        (is (= 1800.0 (:time-spent p)) "time spent comes from the log intervals")
        (is (true? (get-in p [:completion :late?])))))
    (testing "correcting the finished flag recalculates completion"
      (is (nil? (:completion (calc/book-progress
                              g book [(log "2026-07-02T10:00" :reading-log/finished? false)]
                              (date "2026-07-05"))))))
    (testing "latest position, not the maximum; dotted links over missing values"
      (let [p     (calc/book-progress
                   g book
                   [(log "2026-07-02T10:00" :reading-log/end-page 150)
                    (log "2026-07-03T10:00" :reading-log/end-chapter 4)
                    (log "2026-07-04T10:00" :reading-log/end-page 120)
                    (log "2026-07-05T10:00" :reading-log/end-page 130)]
                   (date "2026-07-10"))
            pages (get-in p [:measures :pages])]
        (is (= 120 (get-in pages [:points 1 :value])))
        (is (= 130 (get-in pages [:latest :value])))
        (is (= 3 (count (:points pages))))
        (is (= [:dotted :solid] (mapv :style (:segments pages))))
        (is (= 1 (count (get-in p [:measures :chapters :points]))))
        (is (empty? (get-in p [:measures :audio :points])))
        (is (nil? (get-in p [:measures :audio :percent])))))
    (testing "a missing total shows raw positions without a percentage"
      (let [p (calc/book-progress g {} [(log "2026-07-02T10:00" :reading-log/end-page 50)]
                                  (date "2026-07-10"))]
        (is (= 50 (get-in p [:measures :pages :latest :value])))
        (is (nil? (get-in p [:measures :pages :percent])))))))

(deftest comparison-test
  (let [dur (m :reading-log :duration :total)
        g   (goal {})
        w   (calc/window g (date "2026-07-11"))]
    (testing "unknown coverage is never shown as zero"
      (is (= :unknown (:status (calc/comparison g dur [] 3600.0 w 1 nil)))))
    (testing "known coverage aligns month/day and reconciles totals"
      (let [c (calc/comparison g dur
                               [(interval-record "2025-07-03T10:00" "2025-07-03T11:00")
                                (interval-record "2025-07-11T10:00" "2025-07-11T11:00")]
                               7200.0 w 1
                               [{:start (date "2025-01-01") :end (date "2026-12-31")}])]
        (is (= {:start (date "2025-07-01") :through (date "2025-07-10")} (:window c)))
        (is (= 3600.0 (:amount c)))
        (is (= 100.0 (:percent c)))))
    (testing "coverage that misses part of the window stays unknown"
      (is (= :unknown (:status (calc/comparison
                                g dur [] 1.0 w 1
                                [{:start (date "2025-07-05") :end (date "2025-12-31")}])))))
    (testing "leap days fall back to February 28"
      (is (= (date "2027-02-28")
             (:through (calc/comparison-window
                        g {:start (date "2028-02-01") :through (date "2028-02-29")
                           :completed-days 29}
                        1)))))
    (testing "weekly comparisons use the ISO week, and week 53 may not exist"
      (let [wk (goal {:goal/timing :weekly :goal/ends-on nil})]
        (is (= {:start (date "2025-09-08") :through (date "2025-09-10")}
               (calc/comparison-window wk {:start (date "2026-09-07")
                                           :through (date "2026-09-09")
                                           :completed-days 3}
                                       1)))
        (is (nil? (calc/comparison-window wk {:start (date "2026-12-28")
                                              :through (date "2026-12-29")
                                              :completed-days 2}
                                          1)))))))

(deftest accounting-cutoff-test
  (doseq [measure [(m :reading-log :duration :total)
                   (m :meditation-log :records :total)]]
    (is (empty? (calc/daily-values
                 measure [(interval-record "2026-07-02T10:00" "2026-07-06T10:00")]
                 detroit (at "2026-07-01T00:00") (at "2026-07-05T00:00")))))
  (testing "eligibility uses the accounting cutoff before clipping to the goal end"
    (is (= 3600.0 (:logged
                   (calc/numeric-progress
                    (goal {:goal/ends-on (date "2026-07-02")})
                    (m :reading-log :duration :total)
                    [(interval-record "2026-07-02T23:00" "2026-07-03T01:00")]
                    (date "2026-07-05")))))))

(deftest current-progress-assumes-logged-data-test
  (let [g (goal {:goal/ends-on (date "2026-07-10")})
        ms (m :reading-log :duration :total)
        cutoff (date "2026-07-05")
        empty-progress (calc/numeric-progress g ms [] cutoff)
        sparse-progress (calc/numeric-progress
                         g ms [(interval-record "2026-07-02T10:00" "2026-07-02T11:00")]
                         cutoff)]
    (testing "no logs means zero activity, with a usable required rate"
      (is (= 0.0 (:logged empty-progress)))
      (is (= 0.0 (:progress empty-progress)))
      (is (= 0.0 (:average empty-progress)))
      (is (= 6000.0 (:required empty-progress)))
      (is (= -4.0 (:pace-days empty-progress)))
      (is (nil? (:ratio empty-progress))))
    (testing "unlogged days count in the average without daily confirmation"
      (is (= 3600.0 (:logged sparse-progress)))
      (is (= 900.0 (:average sparse-progress)))
      (is (= 5400.0 (:required sparse-progress)))
      (is (= 6.0 (:ratio sparse-progress))))
    (testing "historical comparisons still require explicit coverage"
      (is (= :unknown (:status (calc/comparison g ms [] 0 (calc/window g cutoff) 1 nil))))
      (is (= :unknown (:status (calc/comparison
                                g ms [] 0 (calc/window g cutoff) 1
                                [{:start (date "2025-01-01") :end (date "2025-12-31")}])))))))

(deftest best-duration-and-recent-test
  (let [g (goal {:goal/timing :open-ended})
        records [{:at (at "2026-07-02T09:00") :duration 80 :reps 1 :weight 80 :weight-unit :kg}
                 {:at (at "2026-07-03T09:00") :duration 90 :reps 1 :weight 90 :weight-unit :kg}
                 {:at (at "2026-07-04T09:00") :duration -10}]]
    (is (= 90.0 (:logged (calc/numeric-progress g (m :exercise-line :duration :best)
                                                records (date "2026-07-05")))))
    (is (= 90.0 (:amount (calc/recent-activity g (m :exercise-line :weight :best)
                                               records (date "2026-07-05") 28))))))

(deftest partial-final-week-and-autumn-dst-test
  (let [w (calc/window (goal {:goal/timing :weekly :goal/ends-on (date "2026-09-16")})
                       (date "2026-09-20"))]
    (is (= 3 (:total-days w)))
    (is (= 3 (:completed-days w)))
    (is (:partial? w)))
  (is (= {(date "2026-11-01") 90000.0}
         (calc/daily-seconds [[(at "2026-11-01T00:00") (at "2026-11-02T00:00")]]
                             detroit (at "2026-11-01T00:00") (at "2026-11-02T00:00")))))

(deftest book-order-and-total-edit-test
  (let [g (goal {})
        logs [(log "2026-07-02T10:00" :xt/id #uuid "00000000-0000-0000-0000-000000000002"
                   :reading-log/end-page 100)
              (log "2026-07-02T10:00" :xt/id #uuid "00000000-0000-0000-0000-000000000001"
                   :reading-log/end-page 50)]
        progress #(calc/book-progress g {:book/total-pages %} logs (date "2026-07-05"))]
    (is (= 100 (get-in (progress 200) [:measures :pages :latest :value])))
    (is (= 0.5 (get-in (progress 200) [:measures :pages :percent])))
    (is (= 0.25 (get-in (progress 400) [:measures :pages :percent])))))

(deftest count-today-boundaries-test
  (let [g (goal {:goal/starts-on (date "2026-07-01")
                 :goal/ends-on (date "2026-07-10") :goal/target 36000.0})
        ms (m :reading-log :duration :total)
        now (at "2026-07-03T12:00")
        records [(interval-record "2026-07-01T10:00" "2026-07-01T12:00")
                 (interval-record "2026-07-03T09:00" "2026-07-03T10:00")
                 (interval-record "2026-07-03T11:00" "2026-07-03T12:00")
                 (interval-record "2026-07-02T23:00" "2026-07-03T13:00")
                 {:at (at "2026-07-02T20:00") :open? true}]
        p (calc/numeric-progress g ms records now)]
    (is (= 14400.0 (:logged p)) "today includes intervals ending exactly now")
    (is (= 7200.0 (:completed-logged p)))
    (is (= 7200.0 (:today-logged p)))
    (is (= 3600.0 (:average p)) "only completed-day amount enters the average")
    (is (= 3600.0 (:required p)) "required pace retains the completed-day baseline")
    (is (= 1.0 (:ratio p)))
    (is (= 0.0 (:pace-days p)))
    (is (= 14400.0 (second (peek (:series p)))))
    (is (= (LocalDateTime/parse "2026-07-03T12:00") (first (peek (:series p))))
        "today's partial point ends at the captured local time")
    (is (= 7200.0 (:value (peek (calc/history g ms records now 84)))))
    (is (= 14400.0 (:amount (calc/recent-activity g ms records now 28))))))

(deftest today-midnight-and-reset-test
  (let [ms (m :habit-log :records :total)
        g (goal {:goal/timing :weekly :goal/ends-on nil
                 :goal/starts-on (date "2026-09-01") :goal/target 7})
        records [{:at (at "2026-09-13T23:59")}
                 {:at (at "2026-09-14T00:00")}
                 {:at (at "2026-09-14T00:01")}]
        midnight (calc/numeric-progress g ms records (at "2026-09-14T00:00"))
        monday (calc/numeric-progress g ms records (at "2026-09-14T12:00"))]
    (is (= 0.0 (:logged midnight)))
    (is (= 2.0 (:logged monday)))
    (is (= 0.0 (:completed-logged monday)))
    (is (nil? (:average monday)))
    (is (= 0 (get-in monday [:window :completed-days])))
    (is (= (date "2026-09-14") (get-in monday [:window :start])))
    (is (= :active (get-in (calc/numeric-progress
                            (assoc g :goal/starts-on (date "2026-09-14"))
                            ms records (at "2026-09-14T12:00")) [:window :status])))))

(deftest today-interval-eligibility-before-clipping-test
  (let [g (goal {:goal/ends-on (date "2026-07-02")})
        records [(interval-record "2026-07-02T23:00" "2026-07-03T01:00")
                 (interval-record "2026-07-02T20:00" "2026-07-03T13:00")]
        now (at "2026-07-03T12:00")]
    (doseq [[ms expected] [[(m :reading-log :duration :total) 3600.0]
                           [(m :meditation-log :records :total) 1.0]]]
      (let [p (calc/numeric-progress g ms records now)]
        (is (= expected (:logged p)))
        (is (= 0.0 (:completed-logged p)))
        (is (= :ended (get-in p [:window :status])))
        (is (nil? (:required p)))
        (is (= (date "2026-07-03") (first (peek (:series p)))))
        (is (= expected (second (peek (:series p)))))))))

(deftest today-book-and-best-test
  (let [now (at "2026-07-03T12:00")
        g (goal {:goal/ends-on (date "2026-07-02")})
        p (calc/book-progress g {:book/total-pages 300}
                              [(log "2026-07-03T12:00" :reading-log/finished? true
                                    :reading-log/end-page 200)
                               (log "2026-07-03T13:00" :reading-log/end-page 300)] now)]
    (is (= 1 (count (:logs p))))
    (is (= (date "2026-07-03") (get-in p [:completion :date])))
    (is (true? (get-in p [:completion :late?])))
    (is (= 200 (get-in p [:measures :pages :latest :value]))))
  (let [p (calc/numeric-progress (goal {}) (m :exercise-line :duration :best)
                                 [{:at (at "2026-07-02T09:00") :duration 80}
                                  {:at (at "2026-07-03T09:00") :duration 90}
                                  {:at (at "2026-07-03T13:00") :duration 100}]
                                 (at "2026-07-03T12:00"))]
    (is (= 90.0 (:logged p)))
    (is (= 80.0 (:completed-logged p)))
    (is (every? nil? (map p [:average :required :ratio :pace-days])))))

(deftest today-goal-local-zones-test
  (let [now (Instant/parse "2026-09-14T01:00:00Z")
        ms (m :habit-log :records :total)
        records [{:at (Instant/parse "2026-09-13T14:00:00Z")}
                 {:at (Instant/parse "2026-09-13T16:00:00Z")}
                 {:at (Instant/parse "2026-09-14T00:30:00Z")}]
        progress (fn [zone]
                   (calc/numeric-progress
                    (goal {:goal/timing :weekly :goal/ends-on nil
                           :goal/starts-on (date "2026-09-01")
                           :goal/time-zone zone :goal/target 7})
                    ms records now))
        tokyo (progress "Asia/Tokyo")
        la (progress "America/Los_Angeles")]
    (is (= (date "2026-09-14") (get-in tokyo [:window :start])))
    (is (= 2.0 (:logged tokyo)))
    (is (= 0.0 (:completed-logged tokyo)))
    (is (nil? (:average tokyo)))
    (is (= (date "2026-09-07") (get-in la [:window :start])))
    (is (= 3.0 (:logged la)))
    (is (= 0.0 (:completed-logged la)))
    (is (= 0.0 (:average la)))))
