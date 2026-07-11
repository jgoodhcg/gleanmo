(ns tech.jgood.gleanmo.test.app.overview-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [tech.jgood.gleanmo.app.overview :as overview]
   [tech.jgood.gleanmo.schema.meta :as sm]
   [tick.core :as t]))

(def ctx {:user/settings {:time-zone "UTC"}})

(deftest timeline-activity-types-test
  (testing "timeline includes reading, exercise, symptoms, and calendar events"
    (is (every? (set overview/recent-activity-types)
                ["reading-log"
                 "calendar-event"
                 "exercise-session"
                 "exercise-block"
                 "symptom-episode"
                 "symptom-log"
                 "mood-log"
                 "boulder-session"]))))

(deftest activity-time-test
  (testing "flat beginning/end keys are used for exercise entities"
    (let [start  (t/instant "2026-06-26T12:00:00Z")
          end    (t/instant "2026-06-26T12:45:00Z")
          result (#'overview/activity-time
                  ctx
                  {::sm/type :exercise-block
                   :exercise-block/beginning start
                   :exercise-block/end end})]
      (is (= start (:instant result)))
      (is (= end (:end-instant result)))
      (is (= :beginning (:source result)))))

  (testing "tasks prefer user-facing date anchors over created time"
    (let [due    (t/date "2026-06-27")
          result (#'overview/activity-time
                  ctx
                  {::sm/type :task
                   ::sm/created-at (t/instant "2026-06-01T00:00:00Z")
                   :task/due-on due})]
      (is (= (t/instant "2026-06-27T00:00:00Z") (:instant result)))
      (is (= :task-date (:source result))))))

(deftest timeline-sort-key-test
  (testing "near future appears before farther future"
    (let [now       (t/now)
          near-item {::overview/activity-time
                     {:sort-instant (t/>> now (t/new-duration 1 :hours))}}
          far-item  {::overview/activity-time
                     {:sort-instant (t/>> now (t/new-duration 1 :days))}}]
      (is (neg? (compare (#'overview/timeline-item-sort-key ctx near-item)
                         (#'overview/timeline-item-sort-key ctx far-item)))))))

(deftest edit-form-url-test
  (testing "timeline edit links return to the home page after save"
    (let [entity-id (java.util.UUID/fromString "00000000-0000-0000-0000-000000000001")]
      (is (= (str "/app/crud/form/reading-log/edit/" entity-id
                  "?redirect=%2Fapp")
             (#'overview/edit-form-url "reading-log" entity-id))))))
