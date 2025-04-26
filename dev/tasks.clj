(ns tasks
  (:require [com.biffweb.tasks :as tasks]
            [tasks.airtable :as airtable]
            [clojure.test :as test]))

(defn hello
  "Says 'Hello'"
  []
  (println "Hello"))

(defn run-tests
  "Run tests in tech.jgood.gleanmo.test namespace and sub-namespaces"
  ([]
   (require 'tech.jgood.gleanmo.test :reload)
   (test/run-all-tests #"tech.jgood.gleanmo.test.*"))
  ([test-name]
   (require 'tech.jgood.gleanmo.test :reload)
   (let [test-var (resolve (symbol (str "tech.jgood.gleanmo.test/" test-name)))]
     (if test-var
       (test/test-vars [test-var])
       (let [test-var-in-sub (first 
                              (filter some?
                                      [(resolve (symbol (str "tech.jgood.gleanmo.test.db.mutations-test/" test-name)))
                                       ;; Add other test namespaces as they are created
                                       ]))]
         (if test-var-in-sub
           (test/test-vars [test-var-in-sub])
           (println "Test" test-name "not found in any test namespace.")))))))

;; Tasks should be vars (#'hello instead of hello) so that `clj -Mdev help` can
;; print their docstrings.
(def custom-tasks
  {"hello" #'hello
   "download-airtable" #'airtable/download-all-records
   "test" #'run-tests})

(def tasks (merge tasks/tasks custom-tasks))
