(ns tech.jgood.gleanmo.test.observability-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [tech.jgood.gleanmo.observability :as obs]))

(defn- route-keys
  "The profiled route keys collected so far. Tufte hands stats to a background
   thread, so wait for the count to settle before reading it."
  []
  (loop [prev -1, tries 0]
    (Thread/sleep 100)
    (let [n (count (:performance-report/pstats (obs/aggregator-snapshot)))]
      (if (or (= n prev) (< 50 tries))
        (set (keys (:performance-report/pstats (obs/aggregator-snapshot))))
        (recur n (inc tries))))))

(deftest route-key-cardinality-test
  (let [handler (obs/wrap-request-profiling (fn [_] {:status 404}))
        n       (+ obs/max-route-keys 500)]
    (route-keys)
    (obs/aggregator-snapshot {:reset? true})
    (testing "made-up paths cannot grow the profiler's key set without bound"
      ;; Paced so tufte's bounded handler queue never drops a profile.
      (doseq [batch (partition-all 200 (range n))]
        (doseq [i batch]
          (handler {:request-method :get
                    :uri            (str "/api/v1/entities/x" (Long/toString i 36) "y")}))
        (Thread/sleep 100))
      (let [ks (route-keys)]
        (is (<= (count ks) (inc obs/max-route-keys)) (str (count ks) " keys"))
        (is (contains? ks :http/other))))
    (testing "the budget is per snapshot window"
      (obs/aggregator-snapshot {:reset? true})
      (handler {:request-method :get, :uri "/app/task/today"})
      (is (contains? (route-keys) :http/get-app-task-today)))
    (obs/aggregator-snapshot {:reset? true})))
