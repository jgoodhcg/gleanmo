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

(deftest line-params-test
  (let [line-params (deref #'workout/line-params)
        ex-id       (random-uuid)]
    (testing "the fragment forms' field name is read"
      (is (= ex-id (:exercise-id (line-params {:line-exercise-id (str ex-id)})))))
    (testing "the session form's field name is read"
      (is (= ex-id (:exercise-id (line-params {:exercise-id (str ex-id)})))))
    (testing "a blank or malformed id yields nil rather than throwing"
      (is (nil? (:exercise-id (line-params {}))))
      (is (nil? (:exercise-id (line-params {:exercise-id ""}))))
      (is (nil? (:exercise-id (line-params {:exercise-id "   "}))))
      (is (nil? (:exercise-id (line-params {:exercise-id "not-a-uuid"})))))
    (testing "zero weight is bodyweight — no weight and no unit stored"
      (let [{:keys [weight unit reps]}
            (line-params {:exercise-id (str ex-id) :reps "12"
                          :weight "0" :weight-unit "lbs"})]
        (is (= 12 reps))
        (is (nil? weight))
        (is (nil? unit))))
    (testing "weight carries its unit"
      (is (= {:weight 45.0 :unit :kg}
             (select-keys (line-params {:weight "45" :weight-unit "kg"})
                          [:weight :unit]))))
    (testing "an unrecognized unit falls back to lbs rather than failing malli"
      (is (= :lbs (:unit (line-params {:weight "45" :weight-unit "stone"}))))
      (is (= :lbs (:unit (line-params {:weight "45"})))))
    (testing "zero distance means the movement isn't measured that way"
      (let [{:keys [distance distance-unit]}
            (line-params {:distance "0" :distance-unit "km"})]
        (is (nil? distance))
        (is (nil? distance-unit))))
    (testing "distance carries its own unit, independent of weight's"
      (is (= {:distance 3.1 :distance-unit :km}
             (select-keys (line-params {:distance "3.1" :distance-unit "km"})
                          [:distance :distance-unit])))
      (is (= :meters (:distance-unit (line-params {:distance "400"
                                                   :distance-unit "meters"})))))
    (testing "an unrecognized distance unit falls back to miles"
      (is (= :miles (:distance-unit (line-params {:distance "2"
                                                  :distance-unit "furlongs"}))))
      (is (= :miles (:distance-unit (line-params {:distance "2"})))))))

(deftest add-line-without-exercise-writes-nothing-test
  (testing "a submit with no exercise leaves the set open instead of closing
            it on data it could not record"
    (let [writes (atom [])]
      (with-redefs [queries/get-entity-for-user (fn [_ id _ _]
                                                  {:xt/id id})
                    queries/sets-for-session (constantly [])
                    mutations/create-entity! (fn [_ spec] (swap! writes conj spec) (random-uuid))
                    mutations/update-entity! (fn [_ spec] (swap! writes conj spec) nil)]
        (let [res (workout/add-line!
                   {:session     {:uid (random-uuid)}
                    :biff/db     {}
                    :path-params {:id (str (random-uuid))}
                    :params      {:reps "12" :stop-set "true"}})]
          (is (= 303 (:status res)))
          (is (= "/app/exercise/session?error=pick-exercise"
                 (get-in res [:headers "location"])))
          (is (empty? @writes)))))))

(deftest add-line-to-set-does-not-touch-the-interval-test
  (testing "describing an already-timed set writes only the line"
    (let [set-id  (random-uuid)
          user-id (random-uuid)
          ex-id   (random-uuid)
          created (atom nil)
          updated (atom nil)]
      (with-redefs [queries/get-entity-for-user (fn [_ id _ _]
                                                  {:xt/id id
                                                   :exercise-set/beginning (t/now)
                                                   :exercise-set/end (t/now)})
                    mutations/create-entity! (fn [_ spec] (reset! created spec) (random-uuid))
                    mutations/update-entity! (fn [_ spec] (reset! updated spec) nil)]
        (let [res (workout/add-line-to-set!
                   {:session     {:uid user-id}
                    :biff/db     {}
                    :path-params {:id (str set-id)}
                    :headers     {"referer" "http://localhost:8080/app/exercise/session"}
                    :params      {:line-exercise-id (str ex-id)
                                  :reps "8" :weight "25" :weight-unit "lbs"}})]
          (is (= 303 (:status res)))
          (is (= "/app/exercise/session" (get-in res [:headers "location"])))
          (is (nil? @updated) "the set's interval must be left alone")
          (is (= {:user/id                    user-id
                  :exercise-line/set-id       set-id
                  :exercise-line/exercise-id  ex-id
                  :exercise-line/reps         8
                  :exercise-line/weight       25.0
                  :exercise-line/weight-unit  :lbs}
                 (:data @created))))))))

(deftest update-line-clears-emptied-fields-test
  (testing "dropping a measurement really drops it rather than keeping the old one"
    (let [updated (atom nil)
          ex-id   (random-uuid)]
      (with-redefs [queries/get-entity-for-user (fn [_ id _ _] {:xt/id id})
                    mutations/update-entity! (fn [_ spec] (reset! updated spec) nil)]
        (workout/update-line!
         {:session     {:uid (random-uuid)}
          :biff/db     {}
          :path-params {:id (str (random-uuid))}
          :params      {:line-exercise-id (str ex-id) :reps "10" :weight "0"
                        :distance "0"}})
        (is (= {:exercise-line/exercise-id   ex-id
                :exercise-line/reps          10
                :exercise-line/weight        :db/dissoc
                :exercise-line/weight-unit   :db/dissoc
                :exercise-line/distance      :db/dissoc
                :exercise-line/distance-unit :db/dissoc}
               (:data @updated))))))
  (testing "a distance survives the round trip with its unit"
    (let [updated (atom nil)
          ex-id   (random-uuid)]
      (with-redefs [queries/get-entity-for-user (fn [_ id _ _] {:xt/id id})
                    mutations/update-entity! (fn [_ spec] (reset! updated spec) nil)]
        (workout/update-line!
         {:session     {:uid (random-uuid)}
          :biff/db     {}
          :path-params {:id (str (random-uuid))}
          :params      {:line-exercise-id (str ex-id) :reps "0" :weight "0"
                        :distance "2.5" :distance-unit "km"}})
        (is (= {:exercise-line/distance      2.5
                :exercise-line/distance-unit :km}
               (select-keys (:data @updated)
                            [:exercise-line/distance
                             :exercise-line/distance-unit])))))))

(deftest resume-set-guards-test
  (let [session-id (random-uuid)
        set-id     (random-uuid)
        older-id   (random-uuid)
        ended      {:xt/id set-id
                    :exercise-set/session-id session-id
                    :exercise-set/beginning (t/instant "2026-08-01T12:00:00Z")
                    :exercise-set/end (t/instant "2026-08-01T12:01:00Z")}
        older      {:xt/id older-id
                    :exercise-set/session-id session-id
                    :exercise-set/beginning (t/instant "2026-08-01T11:00:00Z")
                    :exercise-set/end (t/instant "2026-08-01T11:01:00Z")}
        run        (fn [{:keys [target sets session]}]
                     (let [updated (atom nil)]
                       (with-redefs [queries/get-entity-for-user
                                     (fn [_ _ _ entity-key]
                                       (if (= entity-key :exercise-session) session target))
                                     queries/sets-for-session (constantly sets)
                                     mutations/update-entity! (fn [_ spec] (reset! updated spec) nil)]
                         (workout/resume-set! {:session {:uid (random-uuid)}
                                               :biff/db {}
                                               :path-params {:id (str (:xt/id target))}})
                         @updated)))]
    (testing "the newest ended set in an open session reopens"
      (is (= {:exercise-set/end :db/dissoc :exercise-set/auto-ended :db/dissoc}
             (:data (run {:target ended
                          :sets [older ended]
                          :session {:xt/id session-id}})))))
    (testing "an older set never reopens — it would absorb everything since"
      (is (nil? (run {:target older
                      :sets [older ended]
                      :session {:xt/id session-id}}))))
    (testing "nothing reopens once the session has ended"
      (is (nil? (run {:target ended
                      :sets [older ended]
                      :session {:xt/id session-id
                                :exercise-session/end (t/instant "2026-08-01T13:00:00Z")}}))))
    (testing "nothing reopens while another set is running"
      (is (nil? (run {:target ended
                      :sets [ended (dissoc older :exercise-set/end)]
                      :session {:xt/id session-id}}))))))
