(ns tech.jgood.gleanmo.test.app.task-today-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [tech.jgood.gleanmo.app.shared :as shared]
   [tech.jgood.gleanmo.app.task-today :as task-today]
   [tech.jgood.gleanmo.db.queries :as queries])
  (:import
   [java.time LocalDate]
   [java.util UUID]))

(deftest today-content-uses-user-local-date
  (testing "today-content queries use user-local date instead of server local date"
    (let [fixed-today (LocalDate/of 2026 2 8)
          user-id     (UUID/randomUUID)
          ctx         {:biff/db :fake-db
                       :session {:uid user-id}}
          seen-dates  (atom [])]
      (with-redefs [shared/user-local-date (fn [_] fixed-today)
                    queries/tasks-for-today (fn [_db query-user-id today]
                                              (is (= user-id query-user-id))
                                              (swap! seen-dates conj today)
                                              [])
                    queries/tasks-completed-today (fn [_db query-user-id today]
                                                    (is (= user-id query-user-id))
                                                    (swap! seen-dates conj today)
                                                    [])
                    queries/count-tasks-completed-all-time (constantly 0)
                    queries/count-tasks-completed-in-range (constantly 0)
                    queries/projects-for-user (constantly [])]
        (task-today/today-content ctx)
        (is (= [fixed-today fixed-today] @seen-dates))))))
