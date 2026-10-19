(ns tech.jgood.gleanmo.test.app.deferred-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [tech.jgood.gleanmo.app.deferred :as deferred]))

(deftest full-url-test
  (testing "asks for the full page on the same path"
    (is (= "/app/goals?full=1"
           (deferred/full-url {:uri "/app/goals"}))))

  (testing "keeps filters and replaces any full/tier already present"
    (is (= "/app/task/focus?full=1&search=a+b&state=now"
           (deferred/full-url {:uri          "/app/task/focus"
                               :query-string "search=a%20b&full=0&tier=x&state=now"}))))

  (testing "names the tier"
    (is (= "/app?full=1&tier=goals"
           (deferred/full-url {:uri "/app"} :goals)))))

(deftest wants-test
  (testing "a plain full render computes every tier"
    (is (deferred/wants? {:params {:full "1"}} :goals)))
  (testing "a tier request computes only its own tier"
    (is (deferred/wants? {:params {:full "1" :tier "goals"}} :goals))
    (is (not (deferred/wants? {:params {:full "1" :tier "goals"}} :timeline)))))

(deftest handler-test
  (let [h (deferred/handler {:shell (constantly :shell) :full (constantly :full)})]
    (is (= :shell (h {:params {}})))
    (is (= :full (h {:params {:full "1"}})))
    (is (= :shell (h {:params {:full "true"}})))))

(deftest loader-test
  (let [[_ attrs] (deferred/loader {:uri "/app"}
                                   {:tier :goals :after :timeline
                                    :region-ids ["home-pinned-goals"]})]
    (is (= "/app?full=1&tier=goals" (:hx-get attrs)))
    (is (= "none" (:hx-swap attrs)))
    (is (= "#home-pinned-goals" (:hx-select-oob attrs)))
    (is (= "htmx:afterRequest from:#deferred-loader-timeline" (:hx-trigger attrs)))))
