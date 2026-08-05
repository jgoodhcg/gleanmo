(ns tech.jgood.gleanmo.test.worker-test
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [clojure.tools.logging :as log]
   [com.biffweb :as biff :refer [test-xtdb-node]]
   [tech.jgood.gleanmo :as main]
   [tech.jgood.gleanmo.db.mutations :as mutations]
   [tech.jgood.gleanmo.worker :as worker]
   [tick.core :as t]
   [xtdb.api :as xt])
  (:import
   [java.util UUID]))

(defn- get-context
  [node]
  {:biff.xtdb/node  node,
   :biff/db         (xt/db node),
   :biff/malli-opts #'main/malli-opts})

(defn- planted-session
  "A session written straight past the mutations-layer derivation, the way a
   stray write path or a pre-flag document would look."
  [user-id & {:keys [end running]}]
  (cond-> {:db/doc-type                :exercise-session,
           :xt/id                      (UUID/randomUUID),
           :tech.jgood.gleanmo.schema.meta/type :exercise-session,
           :tech.jgood.gleanmo.schema.meta/created-at (t/now),
           :user/id                    user-id,
           :exercise-session/beginning (t/now)}
    end     (assoc :exercise-session/end (t/now))
    running (assoc :exercise-session/running true)))

(deftest reconcile-timer-flags-test
  (testing "both directions are repaired and each is logged"
    (with-open [node (test-xtdb-node [])]
      (let [ctx      (get-context node)
            user-id  (UUID/randomUUID)
            ;; Open interval, no flag — invisible to a read that filters on the
            ;; flag, which is the whole reason this sweep exists.
            missing  (planted-session user-id)
            ;; Flagged but ended — the read path already filters these; the
            ;; sweep is what actually clears them.
            phantom  (planted-session user-id :end true :running true)
            ;; Agreeing documents must be left alone.
            correct  (mutations/create-entity!
                      ctx {:entity-key :exercise-session,
                           :data {:user/id user-id,
                                  :exercise-session/beginning (t/now)}})
            logged   (atom [])]
        (biff/submit-tx ctx [missing phantom])
        (with-redefs [log/log* (fn [_ level _ message]
                                 (swap! logged conj [level (str message)]))]
          (worker/reconcile-timer-flags ctx))
        (let [db (xt/db node)]
          (is (= true (:exercise-session/running
                       (xt/entity db (:xt/id missing)))))
          (is (not (contains? (xt/entity db (:xt/id phantom))
                              :exercise-session/running)))
          (is (= true (:exercise-session/running (xt/entity db correct)))))
        (testing "each repair says which document and which direction"
          (let [warnings (->> @logged
                              (filter (comp #{:warn} first))
                              (map second))]
            (is (some #(and (str/includes? % (str (:xt/id missing)))
                            (str/includes? % ":missing"))
                      warnings))
            (is (some #(and (str/includes? % (str (:xt/id phantom)))
                            (str/includes? % ":phantom"))
                      warnings))
            (is (not-any? #(str/includes? % (str correct)) warnings)
                "a quiet log is the signal that the derivation is holding")))))))

(deftest reconcile-timer-flags-idempotent-test
  (testing "a second sweep over reconciled data writes nothing"
    (with-open [node (test-xtdb-node [])]
      (let [ctx     (get-context node)
            user-id (UUID/randomUUID)]
        (biff/submit-tx ctx [(planted-session user-id)
                             (planted-session user-id :end true :running true)])
        (worker/reconcile-timer-flags ctx)
        (let [after-first (::xt/tx-id (xt/latest-completed-tx node))]
          (worker/reconcile-timer-flags ctx)
          (is (= after-first (::xt/tx-id (xt/latest-completed-tx node)))))))))

(deftest daily-at-utc-test
  (testing "fires once a day, anchored to the hour rather than to boot time"
    ;; Sampled before the call, not after: `daily-at-utc` anchors to its own
    ;; `now`, so a clock that crosses the target hour between the two would
    ;; make the first firing equal to `now` rather than after it. Once a day,
    ;; for a microsecond — but it is a wall-clock race in a test, which is the
    ;; one place they are least welcome.
    (let [before  (java.util.Date.)
          [a b c] (take 3 (worker/daily-at-utc 9))
          hour-of (fn [^java.util.Date d]
                    (-> (.toInstant d)
                        (.atZone java.time.ZoneOffset/UTC)
                        .getHour))]
      (is (= 9 (hour-of a) (hour-of b) (hour-of c)))
      (is (.after a before))
      (is (= (* 60 60 24 1000) (- (.getTime b) (.getTime a))))
      (is (= (* 60 60 24 1000) (- (.getTime c) (.getTime b)))))))
