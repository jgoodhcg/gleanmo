
(ns tech.jgood.gleanmo.test.timer.routes-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [tech.jgood.gleanmo.schema :refer [schema]]
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
