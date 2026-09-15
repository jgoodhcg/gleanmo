(ns tech.jgood.gleanmo.test.goals.dashboard-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [clojure.string :as str]
   [rum.core :as rum]
   [tech.jgood.gleanmo.app.goals :as goals-page]
   [com.biffweb :refer [test-xtdb-node]]
   [tech.jgood.gleanmo :as main]
   [tech.jgood.gleanmo.db.mutations :as mutations]
   [tech.jgood.gleanmo.db.queries :as queries]
   [tech.jgood.gleanmo.goals.dashboard :as dashboard]
   [tech.jgood.gleanmo.goals.calc :as calc]
   [tech.jgood.gleanmo.goals.registry :as registry]
   [xtdb.api :as xt])
  (:import
   [java.time LocalDate LocalDateTime ZoneId]))

(def ^:private utc (ZoneId/of "UTC"))

(defn- at [s] (.toInstant (.atZone (LocalDateTime/parse s) utc)))

(defn- ctx [node]
  {:biff.xtdb/node  node
   :biff/db         (xt/db node)
   :biff/malli-opts #'main/malli-opts})

(defn- create! [node entity-key data]
  (mutations/create-entity! (ctx node) {:entity-key entity-key :data data}))

(def ^:private visible {:show-sensitive false :show-archived false})

(deftest goal-source-records-test
  (with-open [node (test-xtdb-node [])]
    (let [user      (random-uuid)
          other     (random-uuid)
          project   (create! node :project {:user/id user :project/label "Mine"})
          secret    (create! node :project {:user/id user :project/label "Secret"
                                            :project/sensitive true})
          theirs    (create! node :project {:user/id other :project/label "Theirs"})
          plog      (fn [owner p b e]
                      (create! node :project-log
                               {:user/id owner :project-log/project-id p
                                :project-log/beginning (at b) :project-log/end (at e)
                                :project-log/time-zone "UTC"}))
          kept      (plog user project "2026-07-02T10:00" "2026-07-02T11:00")
          _         (plog user secret "2026-07-02T12:00" "2026-07-02T13:00")
          _         (plog user theirs "2026-07-02T10:00" "2026-07-02T11:00")
          _         (plog other theirs "2026-07-02T10:00" "2026-07-02T11:00")
          _         (plog user project "2026-06-01T10:00" "2026-06-01T11:00")
          deleted   (plog user project "2026-07-03T10:00" "2026-07-03T11:00")
          _         (mutations/soft-delete-entity! (ctx node) {:entity-key :project-log
                                                               :entity-id deleted})
          request   {:source :project-log :since (at "2026-07-01T00:00")
                     :until (at "2026-08-01T00:00") :user-settings visible}
          records   (queries/goal-source-records (xt/db node) user request)]
      (testing "owner, window, deletion, and related-entity visibility"
        (is (= [kept] (mapv :id records)))
        (is (= #{project} (:relations (first records))))
        (is (false? (:open? (first records)))))
      (testing "sensitive parents appear when the user shows them"
        (is (= 2 (count (queries/goal-source-records
                         (xt/db node) user
                         (assoc request :user-settings
                                {:show-sensitive true :show-archived false}))))))
      (testing "relation scope"
        (is (empty? (queries/goal-source-records
                     (xt/db node) user (assoc request :relation-ids #{theirs}))))))))

(deftest exercise-line-records-test
  (with-open [node (test-xtdb-node [])]
    (let [user     (random-uuid)
          exercise (create! node :exercise {:user/id user :exercise/label "Pull-up"})
          other-ex (create! node :exercise {:user/id user :exercise/label "Squat"})
          session  (create! node :exercise-session
                            {:user/id user
                             :exercise-session/beginning (at "2026-07-02T09:00")})
          in-set   (create! node :exercise-set
                            {:user/id user :exercise-set/session-id session
                             :exercise-set/beginning (at "2026-07-02T09:05")})
          old-set  (create! node :exercise-set
                            {:user/id user :exercise-set/session-id session
                             :exercise-set/beginning (at "2026-06-02T09:05")})
          line     (fn [s ex reps]
                     (create! node :exercise-line
                              {:user/id user :exercise-line/set-id s
                               :exercise-line/exercise-id ex :exercise-line/reps reps}))
          kept     (line in-set exercise 10)
          _        (line in-set other-ex 5)
          _        (line old-set exercise 8)
          records  (queries/goal-source-records
                    (xt/db node) user
                    {:source :exercise-line :since (at "2026-07-01T00:00")
                     :until (at "2026-08-01T00:00") :relation-ids #{exercise}
                     :user-settings visible})]
      (is (= [kept] (mapv :id records)))
      (is (= (at "2026-07-02T09:05") (:at (first records))) "dated by the parent set")
      (is (= 10 (:reps (first records)))))))

(deftest dashboard-test
  (with-open [node (test-xtdb-node [])]
    (let [user  (random-uuid)
          book  (create! node :book {:user/id user :book/title "Odyssey"
                                     :book/total-pages 300})
          base  {:user/id user :goal/time-zone "UTC"
                 :goal/starts-on (LocalDate/parse "2026-07-01")}
          _     (create! node :goal (merge base {:goal/label "Reading"
                                                 :goal/source :reading-log
                                                 :goal/measure :duration
                                                 :goal/aggregation :total
                                                 :goal/target 36000.0
                                                 :goal/timing :open-ended}))
          _     (create! node :goal (merge base {:goal/label "Finish"
                                                 :goal/source :reading-log
                                                 :goal/measure :book-completion
                                                 :goal/aggregation :completion
                                                 :goal/timing :open-ended
                                                 :goal/book-ids #{book}}))
          _     (create! node :goal (merge base {:goal/label "Old"
                                                 :goal/source :habit-log
                                                 :goal/measure :records
                                                 :goal/aggregation :total
                                                 :goal/target 10
                                                 :goal/timing :open-ended
                                                 :goal/archived true}))
          _     (create! node :reading-log
                         {:user/id user :reading-log/book-id book
                          :reading-log/beginning (at "2026-07-02T10:00")
                          :reading-log/end (at "2026-07-02T11:00")
                          :reading-log/time-zone "UTC"
                          :reading-log/end-page 120
                          :reading-log/finished? true})
          data  (dashboard/dashboard (xt/db node) user
                                     {:now (at "2026-07-10T12:00")
                                      :user-settings visible})
          by-label (into {} (map (juxt (comp :goal/label :goal) identity))
                         (:active data))]
      (is (= #{"Reading" "Finish"} (set (keys by-label))))
      (is (= ["Old"] (mapv :goal/label (:archived data))))
      (is (= 3600.0 (get-in by-label ["Reading" :progress :logged])))
      (is (= (LocalDate/parse "2026-07-02")
             (get-in by-label ["Finish" :book-progress :completion :date])))
      (is (= 120 (get-in by-label ["Finish" :book-progress :measures :pages
                                   :latest :value])))
      (is (= 84 (count (get-in by-label ["Reading" :history])))))))

(deftest source-ancestry-test
  (with-open [node (test-xtdb-node [])]
    (let [user (random-uuid) foreign (random-uuid)
          exercise (create! node :exercise {:user/id user :exercise/label "Hold"})
          session (create! node :exercise-session
                           {:user/id user :exercise-session/beginning (at "2026-07-02T09:00")})
          set-id (create! node :exercise-set
                          {:user/id user :exercise-set/session-id session
                           :exercise-set/beginning (at "2026-07-02T09:00")})
          line #(create! node :exercise-line
                         {:user/id % :exercise-line/exercise-id exercise
                          :exercise-line/set-id set-id :exercise-line/reps 10})
          kept (line user)
          _ (line foreign)
          request {:source :exercise-line :since (at "2026-07-01T00:00")
                   :until (at "2026-07-05T00:00") :user-settings visible}
          read! #(queries/goal-source-records (xt/db node) user request)]
      (is (= [kept] (mapv :id (read!))))
      (mutations/update-entity! (ctx node)
                                {:entity-key :exercise-session :entity-id session
                                 :data {:user/id foreign}})
      (is (empty? (read!)) "foreign session ancestry is excluded")
      (mutations/update-entity! (ctx node)
                                {:entity-key :exercise-session :entity-id session
                                 :data {:user/id user}})
      (mutations/soft-delete-entity! (ctx node)
                                     {:entity-key :exercise-session :entity-id session})
      (is (empty? (read!)) "deleted session ancestry is excluded"))))

(deftest deleted-parent-and-long-overlap-test
  (with-open [node (test-xtdb-node [])]
    (let [user (random-uuid)
          project (create! node :project {:user/id user :project/label "Long"})
          _ (create! node :project-log
                     {:user/id user :project-log/project-id project
                      :project-log/beginning (at "2026-06-01T00:00")
                      :project-log/end (at "2026-07-02T01:00")
                      :project-log/time-zone "UTC"})
          request {:source :project-log :since (at "2026-07-01T00:00")
                   :until (at "2026-07-03T00:00") :overlap? true :user-settings visible}
          records (queries/goal-source-records (xt/db node) user request)
          g {:goal/timing :dated :goal/starts-on (LocalDate/parse "2026-07-01")
             :goal/ends-on (LocalDate/parse "2026-07-02") :goal/time-zone "UTC"
             :goal/target 100000.0}
          m (registry/measurement {:goal/source :project-log :goal/measure :duration
                                   :goal/aggregation :total})]
      (is (= 90000.0 (:logged (calc/numeric-progress g m records (LocalDate/parse "2026-07-03")))))
      (mutations/soft-delete-entity! (ctx node) {:entity-key :project :entity-id project})
      (is (empty? (queries/goal-source-records (xt/db node) user request))))))

(deftest book-location-visibility-test
  (with-open [node (test-xtdb-node [])]
    (let [user (random-uuid)
          book (create! node :book {:user/id user :book/title "Book"})
          location (create! node :location {:user/id user :location/label "Private"
                                            :location/sensitive true})
          _ (create! node :reading-log
                     {:user/id user :reading-log/book-id book :reading-log/location-id location
                      :reading-log/beginning (at "2026-07-02T09:00")
                      :reading-log/end (at "2026-07-02T10:00")
                      :reading-log/time-zone "UTC" :reading-log/finished? true
                      :reading-log/end-page 100})]
      (is (empty? (queries/reading-logs-for-book (xt/db node) user book visible)))
      (is (= 1 (count (queries/reading-logs-for-book
                       (xt/db node) user book (assoc visible :show-sensitive true)))))
      (mutations/update-entity! (ctx node)
                                {:entity-key :location :entity-id location
                                 :data {:location/sensitive false :location/archived true}})
      (is (empty? (queries/reading-logs-for-book (xt/db node) user book visible))))))

(deftest local-clock-and-bounded-requests-test
  (let [base {:goal/source :habit-log :goal/measure :records :goal/aggregation :total
              :goal/target 10 :goal/timing :weekly :goal/starts-on (LocalDate/parse "2020-01-01")}
        goals [(assoc base :xt/id (random-uuid) :goal/time-zone "Asia/Tokyo")
               (assoc base :xt/id (random-uuid) :goal/time-zone "America/Los_Angeles")]
        requests (atom [])
        data (with-redefs [queries/goals-for-user (fn [& _] goals)
                           queries/fetch-entities-by-ids (fn [& _] [])
                           queries/goal-source-records (fn [_ _ request]
                                                         (swap! requests conj request) [])]
               (dashboard/dashboard nil (random-uuid)
                                    {:now (at "2026-09-14T01:00") :user-settings visible}))
        dates (into {} (map (juxt #(get-in % [:goal :goal/time-zone]) :cutoff-date)) (:active data))]
    (is (= (LocalDate/parse "2026-09-14") (dates "Asia/Tokyo")))
    (is (= (LocalDate/parse "2026-09-13") (dates "America/Los_Angeles")))
    (is (every? #(pos? (compare (:since %) (at "2026-06-01T00:00"))) @requests)))
  (let [g {:goal/timing :dated :goal/time-zone "UTC"
           :goal/starts-on (LocalDate/parse "2020-01-01")
           :goal/ends-on (LocalDate/parse "2020-01-31")}
        requests (dashboard/source-requests
                  [{:goal g :measurement {:source :habit-log}
                    :cutoff-date (LocalDate/parse "2026-09-14")}])]
    (is (= 2 (count (:habit-log requests))) "the unused six-year gap is not scanned")))

(deftest supporting-panel-render-test
  (let [cutoff (LocalDate/parse "2026-07-05")
        goal {:xt/id (random-uuid) :goal/label "Best hold" :goal/source :exercise-line
              :goal/measure :duration :goal/aggregation :best :goal/time-zone "UTC"
              :goal/timing :open-ended :goal/starts-on (LocalDate/parse "2026-07-01")
              :goal/target 120}
        m (registry/measurement goal)
        records [{:at (at "2026-07-02T10:00") :duration 80}
                 {:at (at "2026-07-03T10:00") :duration 90}]
        entry {:goal goal :measurement m
               :progress (calc/numeric-progress goal m records cutoff)
               :history (calc/history goal m records cutoff 84)
               :recent (calc/recent-activity goal m records cutoff 28)}
        html (rum/render-static-markup (goals-page/goal-detail entry cutoff))]
    (is (str/includes? html "Best in the last 28 days"))
    (is (str/includes? html "90 s"))
    (is (not (str/includes? html "170 s")))
    (is (not (str/includes? html "Progress keeps accumulating")))
    (is (not (str/includes? html "Average so far")))
    (let [dated (assoc-in entry [:goal :goal/timing] :dated)
          dated (assoc-in dated [:goal :goal/ends-on] (LocalDate/parse "2026-07-10"))
          dated (assoc dated :comparison {:status :known :percent 12.5 :amount 80
                                          :window {:start (LocalDate/parse "2025-07-01")}})
          html (rum/render-static-markup (goals-page/goal-detail dated cutoff))]
      (is (str/includes? html "vs 2025"))
      (is (str/includes? html "+12.5%")))))
