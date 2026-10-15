(ns tech.jgood.gleanmo.test.api.discovery-test
  (:require
   [cheshire.core :as json]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [reitit.ring :as ring]
   [tech.jgood.gleanmo.api :as api]
   [tech.jgood.gleanmo.api.access :as access]
   [tech.jgood.gleanmo.api.discovery :as discovery]))

(def ^:private base "https://gleanmo.example")

(def ^:private handler
  (ring/ring-handler (ring/router (:api-routes api/module))))

(defn- GET
  [path & {:as headers}]
  (handler {:request-method :get
            :uri            path
            :headers        (or headers {})
            :biff/base-url  (str base "/")}))

(deftest documented-list-params-match-the-handler-test
  (is (= (:list-params api/limits)
         (set (map :name discovery/list-param-docs)))))

(deftest public-routes-test
  (testing "the guide is plain text at both URLs, readable cross-origin"
    (let [agents (GET "/agents")
          llms   (GET "/llms.txt")]
      (is (= 200 (:status agents)))
      (is (str/starts-with? (get-in agents [:headers "Content-Type"]) "text/plain"))
      (is (= "*" (get-in agents [:headers "Access-Control-Allow-Origin"])))
      (is (= (:body agents) (:body llms)))))

  (testing "the skill has Agent Skills frontmatter"
    (let [body (:body (GET "/agents/SKILL.md"))]
      (is (str/starts-with? body "---\nname: gleanmo\ndescription: "))
      (is (str/includes? body (str base "/agents")))))

  (testing "index and OpenAPI need no token"
    (is (= (str base "/agents") (get-in (GET "/api/v1") [:body "docs"])))
    (is (= "3.1.0" (get-in (GET "/api/v1/openapi.json") [:body "openapi"]))))

  (testing "the api-catalog is an RFC 9727 linkset"
    (let [resp (GET "/.well-known/api-catalog")
          body (json/parse-string (:body resp))]
      (is (str/starts-with? (get-in resp [:headers "Content-Type"])
                            "application/linkset+json"))
      (is (= (str base "/api/v1/openapi.json")
             (get-in body ["linkset" 0 "service-desc" 0 "href"]))))))

(deftest unauthenticated-api-points-at-the-guide-test
  (let [resp (GET "/api/v1/types")]
    (is (= 401 (:status resp)))
    (is (= "Bearer realm=\"gleanmo\"" (get-in resp [:headers "WWW-Authenticate"])))
    (is (= (str base "/agents") (get-in resp [:body "docs"])))))

(deftest guide-test
  (let [guide (discovery/guide base api/limits)]
    (testing "every placeholder is filled except the path params it documents"
      (is (= #{"{type}" "{id}"} (set (re-seq #"\{[a-z-]+\}" guide)))))
    (testing "every permission group and its types are listed"
      (doseq [{:keys [key types]} access/groups]
        (is (str/includes? guide (str "`" (name key) "`")))
        (doseq [t types]
          (is (str/includes? guide (str "`" (name t) "`"))))))
    (testing "limits come from the handler"
      (is (str/includes? guide (str "Up to " (:max-ops api/limits) " ops"))))
    (testing "the guide sends the user to create the token themselves"
      (is (str/includes? guide (str base "/app/api-tokens")))
      (is (str/includes? guide discovery/token-env-var)))))

(deftest openapi-test
  (let [doc (discovery/openapi base api/limits)]
    (is (= (mapv name (mapcat :types access/groups))
           (get-in doc ["components" "parameters" "type" "schema" "enum"])))
    (is (= #{"/types" "/entities/{type}" "/entities/{type}/{id}" "/tx"}
           (set (keys (get doc "paths")))))
    (testing "it encodes as JSON"
      (is (string? (json/generate-string doc))))))

(deftest setup-prompt-test
  (let [token  (access/generate-token)
        doc    {:api-token/label        "Laptop"
                :api-token/token-hash   (access/hash-token token)
                :api-token/prefix       (access/display-prefix token)
                :api-token/read-groups  #{:habits}
                :api-token/write-groups #{:tasks}}
        prompt (discovery/setup-prompt base doc)]
    (is (str/includes? prompt (str base "/agents")))
    (is (str/includes? prompt "write: tasks"))
    (is (str/includes? prompt "read: habits"))
    (is (str/includes? prompt "sensitive records: not granted"))
    (testing "the prompt never carries the token or its prefix"
      (is (not (str/includes? prompt token)))
      (is (not (str/includes? prompt access/token-prefix))))))
