(ns tech.jgood.gleanmo.test.api-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [com.biffweb :refer [test-xtdb-node]]
   [tech.jgood.gleanmo :as main]
   [tech.jgood.gleanmo.api :as api]
   [tech.jgood.gleanmo.api.access :as access]
   [tech.jgood.gleanmo.cronometer.transform :as transform]
   [tech.jgood.gleanmo.db.mutations :as mutations]
   [tech.jgood.gleanmo.schema.meta :as sm]
   [tech.jgood.gleanmo.test.cronometer.fixtures :as fx]
   [tick.core :as t]
   [xtdb.api :as xt])
  (:import
   [java.util UUID]))

(defn- ctx
  [node]
  {:biff.xtdb/node  node
   :biff/db         (xt/db node)
   :biff/malli-opts #'main/malli-opts})

(defn- create!
  [node entity-key data]
  (mutations/create-entity! (ctx node) {:entity-key entity-key, :data data}))

(defn- token!
  "Create a token for `user-id` and return its plaintext."
  [node user-id & {:keys [read write sensitive archived revoked]}]
  (let [token (access/generate-token)]
    (create! node :api-token
             (cond-> {:user/id                     user-id
                      :api-token/label             "test"
                      :api-token/token-hash        (access/hash-token token)
                      :api-token/prefix            (access/display-prefix token)
                      :api-token/read-groups       (set read)
                      :api-token/write-groups      (set write)
                      :api-token/sensitive-granted (boolean sensitive)
                      :api-token/archived-granted  (boolean archived)}
               revoked (assoc :api-token/revoked-at (t/now))))
    token))

(defn- call
  [node token handler req]
  ((api/wrap-api-token handler)
   (merge (ctx node)
          {:headers (if token {"authorization" (str "Bearer " token)} {})}
          req)))

(defn- list-items
  [node token type-str params]
  (let [resp (call node token api/list-handler
                   {:path-params {:type type-str}, :params params})]
    (is (= 200 (:status resp)) (pr-str (:body resp)))
    (get-in resp [:body "items"])))

(defn- labels
  [items k]
  (set (map #(get % k) items)))

(defn- tx
  [node token body]
  (call node token api/tx-handler {:body-params body}))

(deftest authentication-test
  (with-open [node (test-xtdb-node [])]
    (let [user-id (UUID/randomUUID)
          good    (token! node user-id :read [:tasks])
          revoked (token! node user-id :read [:tasks] :revoked true)]
      (testing "missing, unknown, and revoked tokens are refused"
        (is (= 401 (:status (call node nil api/types-handler {}))))
        (is (= 401 (:status (call node (access/generate-token) api/types-handler {}))))
        (is (= 401 (:status (call node revoked api/types-handler {})))))
      (testing "a valid token reaches the handler"
        (is (= 200 (:status (call node good api/types-handler {}))))))))

(deftest types-test
  (with-open [node (test-xtdb-node [])]
    (let [token (token! node (UUID/randomUUID) :read [:habits] :write [:tasks])
          body  (:body (call node token api/types-handler {}))]
      (is (= {"tasks" "write", "habits" "read"}
             (into {} (map (juxt #(get % "group") #(get % "access")))
                   (get body "groups"))))
      (testing "fields are described with their values"
        (let [task   (->> (get body "groups")
                          (mapcat #(get % "types"))
                          (some #(when (= "task" (get % "type")) %)))
              state  (some #(when (= "task/state" (:name %)) %)
                           (get task "fields"))]
          (is (= "enum" (:type state)))
          (is (some #{"done"} (:values state)))
          (is (true? (:writable state))))))))

(deftest list-test
  (with-open [node (test-xtdb-node [])]
    (let [user-id  (UUID/randomUUID)
          other-id (UUID/randomUUID)]
      (create! node :task {:user/id user-id, :task/label "Buy stamps",
                           :task/state :now})
      (create! node :task {:user/id user-id, :task/label "Call bank",
                           :task/state :later})
      (create! node :task {:user/id user-id, :task/label "Secret",
                           :task/state :now, :task/sensitive true})
      (create! node :task {:user/id other-id, :task/label "Not mine",
                           :task/state :now})
      (let [hidden-habit (create! node :habit {:user/id user-id,
                                               :habit/label "Private",
                                               :habit/sensitive true})]
        (create! node :habit-log {:user/id          user-id
                                  :habit-log/time-zone "UTC"
                                  :habit-log/timestamp (t/now)
                                  :habit-log/habit-ids #{hidden-habit}}))
      (let [plain     (token! node user-id :read [:tasks :habits])
            sensitive (token! node user-id :read [:tasks] :sensitive true)]
        (testing "only the token user's non-sensitive records, by default"
          (is (= #{"Buy stamps" "Call bank"}
                 (labels (list-items node plain "task" {}) "task/label"))))
        (testing "asking for sensitive records needs the grant"
          (let [resp (call node plain api/list-handler
                           {:path-params {:type "task"}
                            :params      {:include-sensitive "true"}})]
            (is (= 400 (:status resp))))
          (is (= #{"Buy stamps" "Call bank" "Secret"}
                 (labels (list-items node sensitive "task"
                                     {:include-sensitive "true"})
                         "task/label"))))
        (testing "the grant alone does not sweep sensitive records into lists"
          (is (= #{"Buy stamps" "Call bank"}
                 (labels (list-items node sensitive "task" {}) "task/label"))))
        (testing "equality filters and text search"
          (is (= #{"Buy stamps"}
                 (labels (list-items node plain "task" {"task/state" "now"})
                         "task/label")))
          (is (= #{"Call bank"}
                 (labels (list-items node plain "task" {:q "BANK"})
                         "task/label"))))
        (testing "a log under a sensitive parent stays hidden"
          (is (empty? (list-items node plain "habit-log" {}))))
        (testing "ungranted and unknown types look the same"
          (is (= 404 (:status (call node plain api/list-handler
                                    {:path-params {:type "mood-log"}}))))
          (is (= 404 (:status (call node plain api/list-handler
                                    {:path-params {:type "user"}})))))
        (testing "bad params are reported, not ignored"
          (is (= 400 (:status (call node plain api/list-handler
                                    {:path-params {:type "task"}
                                     :params      {"task/nope" "x"}})))))))))

(deftest get-test
  (with-open [node (test-xtdb-node [])]
    (let [user-id   (UUID/randomUUID)
          mine      (create! node :task {:user/id user-id, :task/label "Mine",
                                         :task/state :now})
          secret    (create! node :task {:user/id user-id, :task/label "Secret",
                                         :task/state :now, :task/sensitive true})
          theirs    (create! node :task {:user/id (UUID/randomUUID),
                                         :task/label "Theirs", :task/state :now})
          token     (token! node user-id :read [:tasks])
          get-task  (fn [id]
                      (call node token api/get-handler
                            {:path-params {:type "task", :id (str id)}}))]
      (is (= "Mine" (get-in (get-task mine) [:body "task/label"])))
      (is (= 404 (:status (get-task secret))))
      (is (= 404 (:status (get-task theirs))))
      (is (= 404 (:status (get-task "not-a-uuid")))))))

(defn- stored
  [node id]
  (xt/entity (xt/db node) id))

(deftest tx-test
  (with-open [node (test-xtdb-node [])]
    (let [user-id   (UUID/randomUUID)
          task-id   (create! node :task {:user/id user-id, :task/label "Renew",
                                         :task/state :later, :task/notes "keep"})
          theirs    (create! node :project {:user/id (UUID/randomUUID),
                                            :project/label "Theirs"})
          reader    (token! node user-id :read [:tasks])
          writer    (token! node user-id :write [:tasks])]

      (testing "a read-only token cannot write"
        (let [resp (tx node reader {:ops [{:op "update", :type "task",
                                           :id (str task-id),
                                           :set {:task/state "done"}}]})]
          (is (= 422 (:status resp)))
          (is (= :later (:task/state (stored node task-id))))))

      (testing "dry run reports changes without writing"
        (let [resp (tx node writer {:dry_run true
                                    :ops [{:op "update", :type "task",
                                           :id (str task-id),
                                           :set {:task/state "now"}}]})]
          (is (= 200 (:status resp)))
          (is (true? (get-in resp [:body "valid"])))
          (is (= {"from" "later", "to" "now"}
                 (get-in resp [:body "ops" 0 "changes" "task/state"])))
          (is (= :later (:task/state (stored node task-id))))))

      (testing "a dry run shows no id for a create that leaves it to the server"
        (let [chosen (UUID/randomUUID)
              resp   (tx node writer {:dry_run true
                                      :ops [{:op "create", :type "task",
                                             :set {:task/label "a", :task/state "now"}}
                                            {:op "create", :type "task",
                                             :id (str chosen),
                                             :set {:task/label "b", :task/state "now"}}]})]
          (is (= 200 (:status resp)))
          (is (nil? (get-in resp [:body "ops" 0 "id"])))
          (is (= (str chosen) (get-in resp [:body "ops" 1 "id"])))))

      (testing "one bad op rejects the whole batch"
        (let [resp (tx node writer {:ops [{:op "update", :type "task",
                                           :id (str task-id),
                                           :set {:task/state "now"}}
                                          {:op "update", :type "task",
                                           :id (str task-id),
                                           :set {:task/done-at "2026-01-01T00:00:00Z"}}]})]
          (is (= 422 (:status resp)))
          (is (= :later (:task/state (stored node task-id))))))

      (testing "a stale expect rejects the batch"
        (let [resp (tx node writer {:ops [{:op "update", :type "task",
                                           :id (str task-id),
                                           :expect {:task/state "now"},
                                           :set {:task/state "done"}}]})]
          (is (= 422 (:status resp)))
          (is (= :later (:task/state (stored node task-id))))))

      (testing "an update merges, keeps other fields, and derives task signals"
        (let [resp (tx node writer {:ops [{:op "update", :type "task",
                                           :id (str task-id),
                                           :expect {:task/state "later"},
                                           :set {:task/state "done"}}]})
              doc  (stored node task-id)]
          (is (= 200 (:status resp)) (pr-str (:body resp)))
          (is (= :done (:task/state doc)))
          (is (= "keep" (:task/notes doc)))
          (is (= user-id (:user/id doc)))
          (is (some? (:task/done-at doc)))
          (is (= 1 (:task/state-change-count doc)))))

      (testing "setting sensitive needs the grant"
        (is (= 422 (:status (tx node writer {:ops [{:op "update", :type "task",
                                                    :id (str task-id),
                                                    :set {:task/sensitive true}}]})))))

      (testing "relations must point at the user's own records"
        (is (= 422 (:status (tx node writer
                                {:ops [{:op "create", :type "task",
                                        :set {:task/label "x", :task/state "now",
                                              :task/project-id (str theirs)}}]})))))

      (testing "a create may name its id so later ops can relate to it"
        (let [project-id (UUID/randomUUID)
              resp       (tx node writer
                             {:ops [{:op "create", :type "project",
                                     :id (str project-id),
                                     :set {:project/label "Errands"}}
                                    {:op "create", :type "task",
                                     :set {:task/label "Stamps", :task/state "now",
                                           :task/project-id (str project-id)}}]})
              task       (stored node (parse-uuid (get-in resp [:body "ops" 1 "id"])))]
          (is (= 200 (:status resp)) (pr-str (:body resp)))
          (is (= project-id (:task/project-id task)))
          (is (= user-id (:user/id task)))))

      (testing "a create cannot claim an id already in use"
        (is (= 422 (:status (tx node writer
                                {:ops [{:op "create", :type "project",
                                        :id (str theirs),
                                        :set {:project/label "Hijack"}}]}))))
        (is (= "Theirs" (:project/label (stored node theirs)))))

      (testing "an id taken between the check and the commit is refused, not overwritten"
        (let [racing-id (UUID/randomUUID)
              checked   (xt/db node)
              _         (create! node :project {:xt/id racing-id, :user/id user-id,
                                                :project/label "First"})
              resp      ((api/wrap-api-token api/tx-handler)
                         (merge (ctx node)
                                {:biff/db     checked
                                 :headers     {"authorization" (str "Bearer " writer)}
                                 :body-params {:ops [{:op "create", :type "project",
                                                      :id (str racing-id),
                                                      :set {:project/label "Second"}}]}}))]
          (is (= 409 (:status resp)) (pr-str (:body resp)))
          (is (= "First" (:project/label (stored node racing-id))))))

      (testing "delete is a soft delete"
        (let [resp (tx node writer {:ops [{:op "delete", :type "task",
                                           :id (str task-id)}]})]
          (is (= 200 (:status resp)))
          (is (some? (::sm/deleted-at (stored node task-id))))
          (is (= 404 (:status (call node writer api/get-handler
                                    {:path-params {:type "task",
                                                   :id   (str task-id)}})))))))))

(deftest task-signals-test
  (let [now (t/now)]
    (testing "a snooze to a new date counts as a snooze"
      (is (= 3 (:task/snooze-count
                (api/task-signals {:task/snooze-count 2}
                                  {:task/snooze-until (t/date "2026-11-01")}
                                  now)))))
    (testing "clearing a snooze does not"
      (is (nil? (:task/snooze-count
                 (api/task-signals {:task/snooze-count 2}
                                   {:task/snooze-until :db/dissoc}
                                   now)))))))

(deftest read-only-groups-test
  (with-open [node (test-xtdb-node [])]
    (let [user-id (UUID/randomUUID)
          ;; A write grant on a read-only group, as a tampered form could store.
          token   (token! node user-id :write [:nutrition :biometrics])
          log     (transform/serving->food-log user-id fx/zone fx/serving-oats
                                               fx/oats (t/now))
          _       (create! node :food-log log)]
      (testing "synced types are never writable through the API"
        (is (not (access/can? {:api-token/write-groups #{:nutrition}} :write :food-log)))
        (is (access/can? {:api-token/write-groups #{:nutrition}} :read :food-log))
        (is (= [] (:write (access/permissions-summary
                           {:api-token/write-groups #{:biometrics}})))))

      (testing "a write is rejected"
        (let [resp (tx node token {:ops [{:op "update", :type "food-log",
                                          :id (str (:xt/id log)),
                                          :set {:food-log/label "edited"}}]})]
          (is (= 422 (:status resp)))
          (is (= "Test Oats" (:food-log/label (stored node (:xt/id log)))))))

      (testing "types describes the groups as read access, with the nutrient table"
        (let [body      (:body (call node token api/types-handler {}))
              groups    (into {} (map (juxt #(get % "group") identity)) (get body "groups"))
              nutrition (get groups "nutrition")]
          (is (= "read" (get nutrition "access")))
          (is (= "read" (get-in groups ["biometrics" "access"])))
          (is (some #(= {"key" "protein", "label" "Protein", "unit" "g",
                         "category" "Protein", "cronometer-id" 203} %)
                    (get nutrition "nutrients")))
          (is (= #{"food" "food-log" "nutrition-day"}
                 (set (map #(get % "type") (get nutrition "types")))))
          (is (every? #(false? (:writable %))
                      (mapcat #(get % "fields") (get nutrition "types"))))))

      (testing "raw payloads stay out of API output"
        (let [items (list-items node token "food-log" {})]
          (is (= 1 (count items)))
          (is (not (contains? (first items) "cronometer/raw")))
          (is (= 8.0 (get-in (first items) ["food-log/nutrients" "protein"]))))))))

(deftest activities-group-test
  (with-open [node (test-xtdb-node [])]
    (let [user-id  (UUID/randomUUID)
          session  (create! node :exercise-session
                            {:user/id                   user-id
                             :exercise-session/label    "Private session label"
                             :exercise-session/beginning (t/now)})
          walk     (-> (transform/exercise->activity-observation
                        user-id fx/zone fx/walk (t/now))
                       (assoc :activity-observation/exercise-session-id session))
          _        (create! node :activity-observation walk)
          ;; A write grant, as a tampered form could store, and nothing else.
          token    (token! node user-id :write [:activities])
          untouched (token! node user-id :read [:biometrics])]
      (testing "activity observations are readable, never writable"
        (is (access/can? {:api-token/read-groups #{:activities}}
                         :read :activity-observation))
        (is (not (access/can? {:api-token/write-groups #{:activities}}
                              :write :activity-observation)))
        (is (= :activities (access/type->group :activity-observation))))

      (testing "existing grants do not reach the new group"
        (is (= 404 (:status (call node untouched api/list-handler
                                  {:path-params {:type "activity-observation"},
                                   :params      {}})))))

      (testing "a write is rejected"
        (let [resp (tx node token {:ops [{:op "update", :type "activity-observation",
                                          :id (str (:xt/id walk)),
                                          :set {:activity-observation/label "edited"}}]})]
          (is (= 422 (:status resp)))
          (is (= "walking (Oura)"
                 (:activity-observation/label (stored node (:xt/id walk)))))))

      (testing "types lists the group as read, with read-only fields"
        (let [body   (:body (call node token api/types-handler {}))
              groups (into {} (map (juxt #(get % "group") identity)) (get body "groups"))
              group  (get groups "activities")
              fields (mapcat #(get % "fields") (get group "types"))
              link   (first (filter #(= "activity-observation/exercise-session-id"
                                        (:name %))
                                    fields))]
          (is (= "read" (get group "access")))
          (is (= ["activity-observation"] (map #(get % "type") (get group "types"))))
          (is (every? #(false? (:writable %)) fields))
          (testing "the session relation names only its target type"
            (is (= {:name "activity-observation/exercise-session-id",
                    :type "relation", :relation "exercise-session",
                    :required false, :writable false}
                   link)))
          (is (not (contains? groups "exercise")))))

      (testing "list and get return the document, raw payload dropped"
        (let [[item :as items] (list-items node token "activity-observation" {})
              by-id            (:body (call node token api/get-handler
                                            {:path-params {:type "activity-observation",
                                                           :id   (str (:xt/id walk))}}))]
          (is (= 1 (count items)))
          (is (= item by-id))
          (is (= "session" (get item "activity-observation/scope")))
          (is (true? (get item "activity-observation/auto-ended")))
          (is (not (contains? item "cronometer/raw")))
          (testing "without the exercise group, the link is the bare id"
            (is (= (str session) (get item "activity-observation/exercise-session-id")))
            (is (not-any? #(= "Private session label" %) (vals item)))
            (is (= 404 (:status (call node token api/get-handler
                                      {:path-params {:type "exercise-session",
                                                     :id   (str session)}}))))))))))
