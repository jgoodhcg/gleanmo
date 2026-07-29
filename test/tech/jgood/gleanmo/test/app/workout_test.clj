(ns tech.jgood.gleanmo.test.app.workout-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [tech.jgood.gleanmo.app.workout :as workout]
   [tech.jgood.gleanmo.db.mutations :as mutations]
   [tech.jgood.gleanmo.db.queries :as queries]
   [tick.core :as t]))

(deftest adjusted-beginning-test
  (let [adjust    (deref #'workout/adjusted-beginning)
        beginning (t/instant "2026-07-29T12:00:00Z")
        now       (t/instant "2026-07-29T12:00:30Z")]
    (testing "positive adjustments add elapsed time"
      (is (= (t/instant "2026-07-29T11:59:00Z")
             (adjust beginning now 60))))
    (testing "negative adjustments clamp at zero elapsed time"
      (is (= now (adjust beginning now -60))))))

(deftest start-session-location-test
  (let [created (atom nil)
        user-id (random-uuid)]
    (with-redefs [queries/active-timers-for-user (constantly [])
                  queries/resolve-user-settings (constantly {})
                  mutations/create-entity! (fn [_ spec]
                                             (reset! created spec)
                                             (random-uuid))]
      (is (= 303
             (:status (workout/start-session!
                       {:session {:uid user-id}
                        :params {:location "  Neighborhood gym  "}}))))
      (is (= :exercise-session (:entity-key @created)))
      (is (= user-id (get-in @created [:data :user/id])))
      (is (= "Neighborhood gym"
             (get-in @created [:data :exercise-session/location]))))))
