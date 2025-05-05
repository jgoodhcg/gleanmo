(ns tasks
  (:require
   [clojure.string    :as str]
   [clojure.test      :as test]
   [com.biffweb.tasks :as tasks]
   [tasks.airtable    :as airtable]))

(defn hello
  "Says 'Hello'"
  []
  (println "Hello"))

(defn run-tests
  "Run tests in tech.jgood.gleanmo.test namespace and sub-namespaces.
  
  Usage:
  - (run-tests) - Run all tests
  - (run-tests \"namespace.name\") - Run all tests in the specified namespace
  - (run-tests \"namespace.name/test-name\") - Run a specific test
  
  Note: The namespace can be provided with or without the 'tech.jgood.gleanmo.test' prefix."
  ([]
   (require 'tech.jgood.gleanmo.test :reload)
   (test/run-all-tests #"tech.jgood.gleanmo.test.*"))
  ([test-spec]
   (if (str/includes? test-spec "/")
     ;; Handle fully qualified test name (namespace/test-name)
     (let [[ns-name test-name] (str/split test-spec #"/")
           ;; Check if ns-name already has the prefix
           has-prefix (str/starts-with? ns-name "tech.jgood.gleanmo.test")
           ns-sym (symbol (if has-prefix 
                            ns-name 
                            (str "tech.jgood.gleanmo.test." ns-name)))
           _ (require ns-sym :reload)
           test-var (resolve (symbol (str ns-sym "/" test-name)))]
       (if test-var
         (test/test-vars [test-var])
         (println "Test" test-spec "not found.")))
     
     ;; Handle namespace name only
     (let [;; Check if the namespace already has the prefix
           has-prefix (str/starts-with? test-spec "tech.jgood.gleanmo.test")
           ns-sym (symbol (if has-prefix 
                            test-spec 
                            (str "tech.jgood.gleanmo.test." test-spec)))]
       (try
         (require ns-sym :reload)
         (test/run-tests ns-sym)
         (catch java.io.FileNotFoundException _
           (println "Namespace" ns-sym "not found.")))))))

;; Tasks should be vars (#'hello instead of hello) so that `clj -Mdev help` can
;; print their docstrings.
(def custom-tasks
  {"hello" #'hello,
   "download-airtable" #'airtable/download-all-records,
   "test"  #'run-tests})

(def tasks (merge tasks/tasks custom-tasks))
