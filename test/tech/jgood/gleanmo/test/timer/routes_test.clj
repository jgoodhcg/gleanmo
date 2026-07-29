
(ns tech.jgood.gleanmo.test.timer.routes-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [tech.jgood.gleanmo.db.mutations :as mutations]
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

(deftest sanitize-redirect-test
  (let [sanitize #'timer-routes/sanitize-redirect]
    (testing "timer-prefixed paths pass through"
      (is (= "/app/timers" (sanitize "/app/timers" "/fallback")))
      (is (= "/app/timer/project-log"
             (sanitize "/app/timer/project-log" "/fallback"))))
    (testing "anything else falls back"
      (is (= "/fallback" (sanitize "/app/crud/project" "/fallback")))
      (is (= "/fallback" (sanitize "https://example.com/app/timers" "/fallback")))
      (is (= "/fallback" (sanitize nil "/fallback"))))))

(deftest fill-required-fields-test
  (let [fill #'timer-routes/fill-required-fields
        now  (java.time.Instant/now)]
    (testing "no missing required fields — data returned, template not fetched"
      (let [config (timer-routes/timer-config {:entity-key :project-log
                                               :entity-str "project-log"})
            calls  (atom 0)
            data   {:project-log/project-id (random-uuid)
                    :project-log/beginning  now
                    :project-log/time-zone  "UTC"}]
        (is (= data (fill {} config data (fn [_] (swap! calls inc) nil))))
        (is (zero? @calls))))
    (testing "missing fields copied from template, booleans defaulted"
      (let [config      (timer-routes/timer-config {:entity-key :meditation-log
                                                    :entity-str "meditation-log"})
            location-id (random-uuid)
            template    {:meditation-log/location-id location-id
                         :meditation-log/position    :sitting
                         :meditation-log/guided      true}
            data        {:meditation-log/type-id   (random-uuid)
                         :meditation-log/beginning now
                         :meditation-log/time-zone "UTC"}
            filled      (fill {} config data (fn [_] template))]
        (is (= location-id (:meditation-log/location-id filled)))
        (is (= :sitting (:meditation-log/position filled)))
        (is (true? (:meditation-log/guided filled)))
        (is (false? (:meditation-log/interrupted filled)))))
    (testing "unfillable required field returns nil"
      (let [config (timer-routes/timer-config {:entity-key :meditation-log
                                               :entity-str "meditation-log"})
            data   {:meditation-log/type-id   (random-uuid)
                    :meditation-log/beginning now
                    :meditation-log/time-zone "UTC"}]
        (is (nil? (fill {} config data (fn [_] nil))))))))

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

(deftest relocate-timer-shared-instant-test
  (let [config      (timer-routes/timer-config {:entity-key :project-log
                                                :entity-str "project-log"})
        at          (java.time.Instant/parse "2026-07-28T15:00:00Z")
        project-id  (random-uuid)
        location-id (random-uuid)
        timer       {:xt/id                   (random-uuid)
                     :project-log/project-id  project-id
                     :project-log/beginning   (java.time.Instant/parse
                                               "2026-07-28T14:00:00Z")
                     :project-log/time-zone   "UTC"
                     :project-log/location-id (random-uuid)
                     :project-log/notes       "first segment"}
        updates     (atom [])
        creates     (atom [])]
    (with-redefs [mutations/update-entity! (fn [_ m] (swap! updates conj m))
                  mutations/create-entity! (fn [_ m] (swap! creates conj m))]
      (timer-routes/relocate-timer! {:session {:uid (random-uuid)}}
                                    config
                                    timer
                                    location-id
                                    at))
    (testing "the running segment ends at the caller's instant"
      (is (= 1 (count @updates)))
      (is (= at (get-in (first @updates) [:data :project-log/end]))))
    (testing "the continuation begins at that same instant — segments meet
              exactly, so there is no gap and no overlap to subtract"
      (is (= 1 (count @creates)))
      (is (= at (get-in (first @creates) [:data :project-log/beginning])))
      (is (= (get-in (first @updates) [:data :project-log/end])
             (get-in (first @creates) [:data :project-log/beginning]))))
    (testing "the continuation carries the new location and runs open-ended"
      (let [data (:data (first @creates))]
        (is (= location-id (:project-log/location-id data)))
        (is (= project-id (:project-log/project-id data)))
        (is (= "UTC" (:project-log/time-zone data)))
        (is (nil? (:project-log/end data)))))
    (testing "notes stay with the finished segment"
      (is (nil? (:project-log/notes (:data (first @creates))))))))
