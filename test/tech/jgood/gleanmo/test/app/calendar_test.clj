(ns tech.jgood.gleanmo.test.app.calendar-test
  (:require
    [clojure.string]
    [clojure.test :refer [deftest is testing]]
    [com.biffweb :as biff :refer [test-xtdb-node]]
    [tech.jgood.gleanmo :as main]
    [tech.jgood.gleanmo.app.calendar :as calendar]
    [tech.jgood.gleanmo.db.queries :as queries]
    [tech.jgood.gleanmo.schema.meta :as sm]
    [tick.core :as t]
    [xtdb.api :as xt])
  (:import
    [java.util UUID]))

(defn get-context
  [node]
  {:biff.xtdb/node  node,
   :biff/db         (xt/db node),
   :biff/malli-opts #'main/malli-opts})


(deftest user-access-control-test
  "Test that users can only access their own events"
  (with-open [node (test-xtdb-node [])]
    (let [user-1-id (UUID/randomUUID)
          user-2-id (UUID/randomUUID)
          event-1-id (UUID/randomUUID)
          event-2-id (UUID/randomUUID)
          event-1 {:xt/id event-1-id
                   ::sm/type :calendar-event
                   ::sm/created-at (t/now)
                   :user/id user-1-id
                   :calendar-event/label "User 1 Event"
                   :calendar-event/beginning (t/instant "2024-06-15T10:00:00Z")
                   :calendar-event/source :gleanmo}
          event-2 {:xt/id event-2-id
                   ::sm/type :calendar-event
                   ::sm/created-at (t/now)
                   :user/id user-2-id
                   :calendar-event/label "User 2 Event"
                   :calendar-event/beginning (t/instant "2024-06-15T14:00:00Z")
                   :calendar-event/source :gleanmo}]
      
      (xt/submit-tx node [[:xtdb.api/put event-1]
                          [:xtdb.api/put event-2]])
      (xt/sync node)
      
      (testing "User 1 can only see their own events"
        (let [events (queries/get-events-for-user-year 
                       (:biff/db (get-context node)) user-1-id 2024 "UTC")]
          (is (= 1 (count events)) "Should find exactly one event")
          (is (= "User 1 Event" (:calendar-event/label (first events)))
              "Should be user 1's event")))
      
      (testing "User 2 can only see their own events"
        (let [events (queries/get-events-for-user-year
                       (:biff/db (get-context node)) user-2-id 2024 "UTC")]
          (is (= 1 (count events)) "Should find exactly one event")
          (is (= "User 2 Event" (:calendar-event/label (first events)))
              "Should be user 2's event"))))))

(deftest timezone-handling-test
  "Test timezone handling for events across different zones"
  (with-open [node (test-xtdb-node [])]
    (let [user-id (UUID/randomUUID)]
      
      (testing "Same UTC event appears in different local times"
        (let [event-id (UUID/randomUUID)
              ;; Create event at noon UTC on June 15, 2024
              utc-instant (t/instant "2024-06-15T12:00:00Z")
              event {:xt/id event-id
                     ::sm/type :calendar-event
                     ::sm/created-at (t/now)
                     :user/id user-id
                     :calendar-event/label "UTC Noon Event"
                     :calendar-event/beginning utc-instant
                     :calendar-event/source :gleanmo}]
          
          (xt/submit-tx node [[:xtdb.api/put event]])
          (xt/sync node)
          
          ;; Query in different timezones
          (let [utc-events (queries/get-events-for-user-year 
                             (:biff/db (get-context node)) user-id 2024 "UTC")
                est-events (queries/get-events-for-user-year
                             (:biff/db (get-context node)) user-id 2024 "America/New_York")
                pst-events (queries/get-events-for-user-year
                             (:biff/db (get-context node)) user-id 2024 "America/Los_Angeles")]
            
            (is (= 1 (count utc-events)) "Should find event in UTC")
            (is (= 1 (count est-events)) "Should find event in EST")
            (is (= 1 (count pst-events)) "Should find event in PST")
            (is (= utc-instant (:calendar-event/beginning (first utc-events)))
                "Event time should be same UTC instant")))))))

(deftest year-boundary-timezone-query-test
  "Critical test: events near year boundaries should appear in correct year despite timezone differences"
  (with-open [node (test-xtdb-node [])]
    (let [user-id (UUID/randomUUID)]
      
      (testing "Dec 31 11:30 PM EST should appear in 2024, not 2025 (tests year boundary logic)"
        ;; Event at 11:30 PM EST on December 31, 2024
        ;; In UTC this becomes 4:30 AM January 1, 2025  
        ;; The query must handle this correctly!
        (let [event-id (UUID/randomUUID)
              utc-instant (-> (t/date "2024-12-31")
                             (t/at (t/time "23:30"))
                             (t/in (java.time.ZoneId/of "America/New_York"))
                             (t/instant))
              event {:xt/id event-id
                     ::sm/type :calendar-event
                     ::sm/created-at (t/now)
                     :user/id user-id
                     :calendar-event/label "New Year's Eve Party"
                     :calendar-event/beginning utc-instant
                     :calendar-event/time-zone "America/New_York"
                     :calendar-event/all-day false
                     :calendar-event/source :gleanmo}]
          
          (xt/submit-tx node [[:xtdb.api/put event]])
          (xt/sync node)
          
          (let [events-2024 (queries/get-events-for-user-year 
                              (:biff/db (get-context node)) user-id 2024 "America/New_York")
                events-2025 (queries/get-events-for-user-year 
                              (:biff/db (get-context node)) user-id 2025 "America/New_York")]
            
            (is (= 1 (count events-2024)) 
                "Dec 31 11:30 PM EST event should appear in 2024 results")
            (is (= 0 (count events-2025)) 
                "Dec 31 11:30 PM EST event should NOT appear in 2025 results")))))))