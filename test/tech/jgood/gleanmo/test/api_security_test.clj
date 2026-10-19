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
   [tech.jgood.gleanmo.api.access :as access]
   [tech.jgood.gleanmo.db.mutations :as mutations]
   [xtdb.api :as xt])
  (:import
   [java.io ByteArrayInputStream InputStream]
   [java.util UUID]))

(defn- ctx
  [node]
  {:biff.xtdb/node  node
   :biff/db         (xt/db node)
   :biff/malli-opts #'main/malli-opts})

(defn- token!
  [node user-id & {:keys [write]}]
  (let [token (access/generate-token)]
    (mutations/create-entity!
     (ctx node)
     {:entity-key :api-token
      :data       {:user/id                user-id
                   :api-token/label        "test"
                   :api-token/token-hash   (access/hash-token token)
                   :api-token/prefix       (access/display-prefix token)
                   :api-token/read-groups  #{}
                   :api-token/write-groups (set write)}})
    token))

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
