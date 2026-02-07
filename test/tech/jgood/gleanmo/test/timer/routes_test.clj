
(ns tech.jgood.gleanmo.test.timer.routes-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [tech.jgood.gleanmo.schema.utils :as schema-utils]
   [tech.jgood.gleanmo.timer.routes :as timer-routes]))

(deftest timer-config-test
  (testing "project-log config uses metadata"
    (let [config (timer-routes/timer-config {:entity-key :project-log
                                             :entity-str "project-log"})]
      (is (= :project (:parent-entity-key config)))
      (is (= "project" (:parent-entity-str config)))
      (is (= (schema-utils/entity-field-key "project-log" "beginning")
             (:beginning-key config)))
      (is (= (schema-utils/entity-field-key "project-log" "end")
             (:end-key config)))
      (is (= :project-log/project-id (:relationship-key config)))))

  (testing "meditation metadata overrides naming"
    (let [config (timer-routes/timer-config {:entity-key :meditation-log
                                             :entity-str "meditation-log"})]
      (is (= :meditation (:parent-entity-key config)))
      (is (= "meditation" (:parent-entity-str config)))
      (is (= :meditation-log/type-id (:relationship-key config)))))

  (testing "missing interval fields throws"
    (is (thrown? clojure.lang.ExceptionInfo
                 (timer-routes/timer-config {:entity-key :project
                                             :entity-str "project"}))))

  (testing "missing relationship throws"
    (let [fake-schema-map {:test [:test
                                  [:test/beginning :instant]
                                  [:test/end :instant]]}]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"primary relationship"
                            (timer-routes/timer-config {:entity-key :test
                                                        :entity-str "test"
                                                        :schema-map fake-schema-map}))))))

(deftest overlap-metrics-helpers-test
  (let [i #(java.time.Instant/parse %)
        clamp-interval #'timer-routes/clamp-interval-to-window
        merge-intervals #'timer-routes/merge-overlapping-intervals
        unique-seconds #'timer-routes/unique-interval-seconds]
    (testing "clamp interval trims to day window"
      (let [window-start (i "2026-02-07T00:00:00Z")
            window-end   (i "2026-02-08T00:00:00Z")
            log-start    (i "2026-02-06T23:30:00Z")
            log-end      (i "2026-02-07T00:30:00Z")]
        (is (= [window-start (i "2026-02-07T00:30:00Z")]
               (clamp-interval log-start log-end window-start window-end)))))

    (testing "merge combines overlapping and adjacent intervals"
      (let [intervals [[(i "2026-02-07T10:00:00Z") (i "2026-02-07T11:00:00Z")]
                       [(i "2026-02-07T10:30:00Z") (i "2026-02-07T11:30:00Z")]
                       [(i "2026-02-07T11:30:00Z") (i "2026-02-07T12:00:00Z")]
                       [(i "2026-02-07T13:00:00Z") (i "2026-02-07T13:30:00Z")]]]
        (is (= [[(i "2026-02-07T10:00:00Z") (i "2026-02-07T12:00:00Z")]
                [(i "2026-02-07T13:00:00Z") (i "2026-02-07T13:30:00Z")]]
               (merge-intervals intervals)))))

    (testing "unique interval seconds removes overlap"
      (let [intervals [[(i "2026-02-07T10:00:00Z") (i "2026-02-07T11:00:00Z")]
                       [(i "2026-02-07T10:00:00Z") (i "2026-02-07T11:00:00Z")]
                       [(i "2026-02-07T10:30:00Z") (i "2026-02-07T11:30:00Z")]]]
        (is (= 5400
               (unique-seconds intervals)))))))
