(ns tech.jgood.gleanmo.test.api-security-test
  "Security properties of the agent API that only show up through the whole
   middleware stack — what happens before a token is checked, and what every
   response carries. Requests go through `main/handler`, the same handler
   Jetty calls, so route wiring is under test too. See
   roadmap/100-agent-api-security.md."
  (:require
   [cheshire.core :as json]
   [clojure.test :refer [deftest is testing]]
   [com.biffweb :refer [test-xtdb-node]]
   [tech.jgood.gleanmo :as main]
   [tech.jgood.gleanmo.api :as api]
   [tech.jgood.gleanmo.api.access :as access]
   [tech.jgood.gleanmo.app.api-tokens :as api-tokens]
   [tech.jgood.gleanmo.db.mutations :as mutations]
   [tech.jgood.gleanmo.schema.meta :as sm]
   [tick.core :as t]
   [xtdb.api :as xt])
  (:import
   [java.io ByteArrayInputStream InputStream]
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
  [node user-id & {:keys [read write sensitive archived deleted]}]
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
               deleted (assoc ::sm/deleted-at (t/now))))
    token))

(defn- call
  "Call `handler` behind the token check only, as the route tree does."
  [node token handler req]
  ((api/wrap-api-token handler)
   (merge (ctx node)
          {:headers (if token {"authorization" (str "Bearer " token)} {})}
          req)))

(defn- tx
  [node token body]
  (call node token api/tx-handler {:body-params body}))

(defn- op-errors
  [resp]
  (mapcat #(get % "errors") (get-in resp [:body "ops"])))

(defn- stored
  [node id]
  (xt/entity (xt/db node) id))

(def ^:private big-body-bytes (* 16 1024 1024))

(defn- counting-stream
  "16 MiB of spaces that counts the bytes read from it, so a test can tell
   whether anything consumed the body."
  [counter]
  (proxy [InputStream] []
    (read
      ([]
       (if (< @counter big-body-bytes) (do (swap! counter inc) 32) -1))
      ([^bytes b] (.read ^InputStream this b 0 (alength b)))
      ([^bytes b off len]
       (let [n (min len (- big-body-bytes @counter))]
         (if (pos? n)
           (do (java.util.Arrays/fill b (int off) (int (+ off n)) (byte 32))
               (swap! counter + n)
               n)
           -1))))))

(defn- request
  [node {:keys [method uri token body headers]}]
  (main/handler
   (merge (ctx node)
          {:request-method (or method :get)
           :uri            uri
           :scheme         :https
           :server-name    "localhost"
           :server-port    443
           :headers        (cond-> (merge {"host" "localhost"} headers)
                             token (assoc "authorization" (str "Bearer " token)))
           :body           (cond
                             (string? body) (ByteArrayInputStream. (.getBytes ^String body "UTF-8"))
                             :else          body)})))

(defn- json-body
  [resp]
  (let [b (:body resp)]
    (json/parse-string (if (instance? InputStream b) (slurp b) (str b)))))

(deftest body-size-limit-test
  (with-open [node (test-xtdb-node [])]
    (let [token (token! node (UUID/randomUUID) :write [:tasks])]
      (testing "a declared oversized body is refused before anything reads it"
        (let [read (atom 0)
              resp (request node {:method  :post, :uri "/api/v1/tx"
                                  :headers {"content-type"   "application/json"
                                            "content-length" (str (* 64 1024 1024))}
                                  :body    (counting-stream read)})]
          (is (= 413 (:status resp)))
          (is (zero? @read))))

      (testing "an undeclared (chunked) body is cut off at the limit, token or not"
        (doseq [t [nil token]]
          (let [read (atom 0)
                resp (request node {:method  :post, :uri "/api/v1/tx", :token t
                                    :headers {"content-type" "application/json"}
                                    :body    (counting-stream read)})]
            (is (= 413 (:status resp)))
            (is (< @read (* 8 1024 1024))))))

      (testing "form bodies are bounded too"
        (let [read (atom 0)
              resp (request node {:method  :post, :uri "/api/v1/tx"
                                  :headers {"content-type"
                                            "application/x-www-form-urlencoded"}
                                  :body    (counting-stream read)})]
          (is (= 413 (:status resp)))
          (is (< @read (* 8 1024 1024)))))

      (testing "a normal batch still goes through"
        (let [resp (request node {:method  :post, :uri "/api/v1/tx", :token token
                                  :headers {"content-type" "application/json"}
                                  :body    (json/generate-string
                                            {:dry_run true
                                             :ops     [{:op  "create", :type "task"
                                                        :set {"task/label" "x"
                                                              "task/state" "now"}}]})})]
          (is (= 200 (:status resp)))
          (is (true? (get (json-body resp) "valid"))))))))

(deftest malformed-body-test
  (with-open [node (test-xtdb-node [])]
    (let [token (token! node (UUID/randomUUID) :write [:tasks])
          resp  (request node {:method  :post, :uri "/api/v1/tx", :token token
                               :headers {"content-type" "application/json"}
                               :body    "{\"ops\": [nope"})]
      (testing "malformed JSON is the client's error, answered in JSON"
        (is (= 400 (:status resp)))
        (is (re-find #"(?i)json" (get-in resp [:headers "Content-Type"] "")))
        (is (string? (get (json-body resp) "error")))))))

(deftest no-store-test
  (with-open [node (test-xtdb-node [])]
    (let [token (token! node (UUID/randomUUID) :write [:tasks])]
      (testing "API responses are never stored by a cache"
        (doseq [resp [(request node {:uri "/api/v1/types", :token token})
                      (request node {:uri "/api/v1/entities/task", :token token})
                      (request node {:uri "/api/v1/types"})]]
          (is (= "no-store" (get-in resp [:headers "Cache-Control"]))
              (pr-str (:status resp))))))))

;; ---------------------------------------------------------------------------
;; Verdicts recorded as "sound" in roadmap/100 — these pin them down.
;; ---------------------------------------------------------------------------

(deftest token-format-test
  (let [tokens (repeatedly 20 access/generate-token)]
    (testing "32 random bytes behind a fixed prefix, no repeats"
      (is (every? #(re-matches #"gmn_[A-Za-z0-9_-]{43}" %) tokens))
      (is (= 20 (count (set tokens)))))
    (testing "the stored prefix reveals 8 of the 43 random characters"
      (is (= 12 (count (access/display-prefix (first tokens))))))
    (testing "the stored hash is SHA-256 hex, not the token"
      (is (re-matches #"[0-9a-f]{64}" (access/hash-token (first tokens))))
      (is (not= (first tokens) (access/hash-token (first tokens)))))))

(deftest bearer-parsing-test
  (with-open [node (test-xtdb-node [])]
    (let [user-id (UUID/randomUUID)
          good    (token! node user-id :read [:tasks])
          deleted (token! node user-id :read [:tasks] :deleted true)
          status  (fn [headers & [extra]]
                    (:status ((api/wrap-api-token api/types-handler)
                              (merge (ctx node) {:headers headers} extra))))]
      (testing "only an exact bearer token authenticates"
        (is (= 200 (status {"authorization" (str "Bearer " good)})))
        (is (= 200 (status {"authorization" (str "bearer  " good " ")})))
        (is (= 401 (status {"authorization" (str "Basic " good)})))
        (is (= 401 (status {"authorization" "Bearer"})))
        (is (= 401 (status {"authorization" (str "Bearer " good " extra")})))
        (is (= 401 (status {"authorization" (str "Bearer " (subs good 0 30))})))
        (is (= 401 (status {"authorization" (str "Bearer " (access/hash-token good))}))
            "the stored hash is not itself a credential"))
      (testing "a soft-deleted token is refused"
        (is (= 401 (status {"authorization" (str "Bearer " deleted)}))))
      (testing "a signed-in session is never consulted"
        (is (= 401 (status {} {:session {:uid user-id}})))))))

(deftest unreachable-types-test
  (with-open [node (test-xtdb-node [])]
    (let [user-id  (UUID/randomUUID)
          everything (vec access/group-keys)
          token    (token! node user-id :write everything :sensitive true :archived true)
          token-id (:xt/id (first (filter #(= :api-token (::sm/type %))
                                          (map first (xt/q (xt/db node)
                                                           '{:find [(pull ?e [*])]
                                                             :where [[?e :user/id u]]
                                                             :in [u]}
                                                           user-id)))))]
      (testing "account, calendar-feed and token documents are never readable"
        (doseq [type-str ["api-token" "user" "ical-url"]]
          (is (= 404 (:status (call node token api/list-handler
                                    {:path-params {:type type-str}, :params {}}))))
          (is (= 404 (:status (call node token api/get-handler
                                    {:path-params {:type type-str, :id (str token-id)}}))))))
      (testing "nor writable, even by a token granted every group"
        (doseq [type-str ["api-token" "user" "ical-url"]]
          (is (= 422 (:status (tx node token {:ops [{:op "update", :type type-str
                                                     :id (str token-id)
                                                     :set {:api-token/revoked-at nil}}]})))))))))

(deftest protected-fields-test
  (with-open [node (test-xtdb-node [])]
    (let [user-id  (UUID/randomUUID)
          other-id (UUID/randomUUID)
          task-id  (create! node :task {:user/id user-id, :task/label "Mine",
                                        :task/state :now})
          project  (create! node :project {:user/id user-id, :project/label "P"})
          token    (token! node user-id :write [:tasks :habits])
          rejected (fn [type-str field value]
                     (let [create (tx node token {:dry_run true
                                                  :ops [{:op "create", :type type-str
                                                         :set {field value}}]})
                           update (tx node token {:ops [{:op "update", :type "task"
                                                         :id (str task-id)
                                                         :set {field value}}]})]
                       (and (some #(re-find #"not writable|not a field" %)
                                  (op-errors create))
                            (= 422 (:status update)))))]
      (testing "identity, ownership, meta and lineage fields are refused, not ignored"
        (is (rejected "task" :user/id (str other-id)))
        (is (rejected "task" :xt/id (str (UUID/randomUUID))))
        (is (rejected "task" ::sm/type "habit"))
        (is (rejected "task" ::sm/created-at "2020-01-01T00:00:00Z"))
        (is (rejected "task" ::sm/deleted-at "2020-01-01T00:00:00Z"))
        (is (rejected "habit" :airtable/id "rec123")))
      (testing "derived fields are refused"
        (is (rejected "task" :task/done-at "2020-01-01T00:00:00Z"))
        (is (rejected "task" :task/state-change-count 0)))
      (testing "a timer's running flag is refused"
        (let [resp (tx node (token! node user-id :write [:tasks])
                       {:dry_run true
                        :ops [{:op "create", :type "project-log"
                               :set {:project-log/project-id (str project)
                                     :project-log/beginning "2026-01-01T00:00:00Z"
                                     :project-log/time-zone "UTC"
                                     :project-log/running true}}]})]
          (is (some #(re-find #"not writable" %) (op-errors resp)))))
      (testing "nothing was written"
        (is (= user-id (:user/id (stored node task-id))))
        (is (nil? (::sm/deleted-at (stored node task-id))))))))

(deftest cross-user-write-test
  (with-open [node (test-xtdb-node [])]
    (let [user-id (UUID/randomUUID)
          theirs  (create! node :task {:user/id (UUID/randomUUID), :task/label "Theirs",
                                       :task/state :now, :task/notes "keep"})
          habit   (create! node :habit {:user/id (UUID/randomUUID), :habit/label "Theirs"})
          token   (token! node user-id :write [:tasks :habits])]
      (testing "another user's record cannot be updated or deleted"
        (doseq [op [{:op "update", :type "task", :id (str theirs), :set {:task/notes "x"}}
                    {:op "delete", :type "task", :id (str theirs)}]]
          (let [resp (tx node token {:ops [op]})]
            (is (= 422 (:status resp)))
            (is (= ["not found"] (op-errors resp))))))
      (testing "a dry run shows nothing of it"
        (let [resp (tx node token {:dry_run true
                                   :ops [{:op "delete", :type "task", :id (str theirs)}]})]
          (is (nil? (get-in resp [:body "ops" 0 "before"])))))
      (testing "expect on another user's record reports not found, not its values"
        (let [resp (tx node token {:dry_run true
                                   :ops [{:op "update", :type "task", :id (str theirs)
                                          :expect {:task/notes "guess"}
                                          :set {:task/notes "x"}}]})]
          (is (= ["not found"] (op-errors resp)))))
      (testing "a set relation cannot point at another user's record"
        (let [resp (tx node token {:ops [{:op "create", :type "habit-log"
                                          :set {:habit-log/timestamp "2026-01-01T00:00:00Z"
                                                :habit-log/time-zone "UTC"
                                                :habit-log/habit-ids [(str habit)]}}]})]
          (is (= 422 (:status resp)))
          (is (some #(re-find #"unknown related ids" %) (op-errors resp)))))
      (testing "the record is untouched"
        (is (= "keep" (:task/notes (stored node theirs))))
        (is (nil? (::sm/deleted-at (stored node theirs))))))))

(deftest group-scope-write-test
  (with-open [node (test-xtdb-node [])]
    (let [user-id (UUID/randomUUID)
          habit   (create! node :habit {:user/id user-id, :habit/label "Mine"})
          token   (token! node user-id :write [:tasks] :read [:habits])]
      (testing "a write grant on one group does not reach another"
        (let [resp (tx node token {:ops [{:op "update", :type "habit", :id (str habit)
                                          :set {:habit/label "edited"}}]})]
          (is (= 422 (:status resp)))
          (is (= "Mine" (:habit/label (stored node habit)))))))))

(deftest visibility-write-test
  (with-open [node (test-xtdb-node [])]
    (let [user-id  (UUID/randomUUID)
          secret   (create! node :task {:user/id user-id, :task/label "Secret",
                                        :task/state :now, :task/sensitive true})
          plain    (create! node :task {:user/id user-id, :task/label "Plain",
                                        :task/state :now})
          token    (token! node user-id :write [:tasks])]
      (testing "a sensitive record is not found by a token without the grant"
        (is (= ["not found"]
               (op-errors (tx node token {:ops [{:op "update", :type "task"
                                                  :id (str secret)
                                                  :set {:task/label "x"}}]})))))
      (testing "archiving needs the archived grant"
        (is (= 422 (:status (tx node token {:ops [{:op "update", :type "task"
                                                    :id (str plain)
                                                    :set {:task/archived true}}]})))))
      (is (= "Secret" (:task/label (stored node secret))))
      (is (nil? (:task/archived (stored node plain)))))))

(deftest token-management-test
  (with-open [node (test-xtdb-node [])]
    (let [user-id  (UUID/randomUUID)
          other-id (UUID/randomUUID)
          resp     (api-tokens/create! (merge (ctx node)
                                              {:session {:uid user-id}
                                               :params  {:label        "agent"
                                                         :group-tasks  "write"
                                                         :group-habits "read"}}))
          body     (str (:body resp))
          plain    (re-find #"gmn_[A-Za-z0-9_-]{43}" body)
          docs     (fn [uid] (->> (xt/q (xt/db node)
                                        '{:find [(pull ?e [*])]
                                          :where [[?e :user/id u] [?e ::sm/type :api-token]]
                                          :in [u]}
                                        uid)
                                  (map first)))
          doc      (first (docs user-id))]
      (testing "the plaintext is sent once, uncached, and never stored"
        (is (some? plain))
        (is (= "no-store" (get-in resp [:headers "Cache-Control"])))
        (is (= (access/hash-token plain) (:api-token/token-hash doc)))
        (is (not (re-find (re-pattern plain) (pr-str doc)))))
      (testing "the grants are what the form asked for"
        (is (= #{:tasks} (:api-token/write-groups doc)))
        (is (= #{:habits} (:api-token/read-groups doc))))
      (testing "another user cannot revoke the token"
        (api-tokens/revoke! (merge (ctx node)
                                   {:session     {:uid other-id}
                                    :path-params {:id (str (:xt/id doc))}}))
        (is (nil? (:api-token/revoked-at (stored node (:xt/id doc)))))
        (is (= 200 (:status (call node plain api/types-handler {})))))
      (testing "the owner's revoke takes effect on the next request"
        (api-tokens/revoke! (merge (ctx node)
                                   {:session     {:uid user-id}
                                    :path-params {:id (str (:xt/id doc))}}))
        (is (some? (:api-token/revoked-at (stored node (:xt/id doc)))))
        (is (= 401 (:status (call node plain api/types-handler {}))))))))

(deftest hidden-relation-target-test
  (with-open [node (test-xtdb-node [])]
    (let [user-id  (UUID/randomUUID)
          private  (create! node :habit {:user/id user-id, :habit/label "Private",
                                         :habit/sensitive true})
          open     (create! node :habit {:user/id user-id, :habit/label "Open"})
          shelved  (create! node :project {:user/id user-id, :project/label "Shelved",
                                           :project/archived true})
          log      (create! node :habit-log {:user/id             user-id
                                             :habit-log/timestamp (t/now)
                                             :habit-log/time-zone "UTC"
                                             :habit-log/habit-ids #{open}})
          task     (create! node :task {:user/id user-id, :task/label "Visible",
                                        :task/state :now})
          plain    (token! node user-id :write [:habits :tasks])
          granted  (token! node user-id :write [:habits :tasks]
                           :sensitive true :archived true)
          refused? (fn [resp]
                     (and (= 422 (:status resp))
                          (some #(re-find #"unknown related ids" %) (op-errors resp))))]
      (testing "a token cannot move a record it sees under a parent it cannot see"
        (is (refused? (tx node plain {:ops [{:op "update", :type "habit-log"
                                             :id (str log)
                                             :set {:habit-log/habit-ids [(str private)]}}]})))
        (is (refused? (tx node plain {:ops [{:op "update", :type "task", :id (str task)
                                             :set {:task/project-id (str shelved)}}]})))
        (is (= #{open} (:habit-log/habit-ids (stored node log))))
        (is (nil? (:task/project-id (stored node task)))))
      (testing "nor create one there"
        (is (refused? (tx node plain {:ops [{:op "create", :type "habit-log"
                                             :set {:habit-log/timestamp "2026-01-01T00:00:00Z"
                                                   :habit-log/time-zone "UTC"
                                                   :habit-log/habit-ids [(str private)]}}]}))))
      (testing "a visible parent is still fine"
        (is (= 200 (:status (tx node plain {:dry_run true
                                            :ops [{:op "update", :type "habit-log"
                                                   :id (str log)
                                                   :set {:habit-log/habit-ids
                                                         [(str open)]}}]})))))
      (testing "with the grants, the hidden parents are reachable"
        (is (true? (get-in (tx node granted
                               {:dry_run true
                                :ops [{:op "update", :type "habit-log", :id (str log)
                                       :set {:habit-log/habit-ids [(str private)]}}
                                      {:op "update", :type "task", :id (str task)
                                       :set {:task/project-id (str shelved)}}]})
                           [:body "valid"])))))))
