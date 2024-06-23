(ns tasks
  (:require [com.biffweb.tasks :as tasks]
            [tasks.airtable :as airtable]))

(defn hello
  "Says 'Hello'"
  []
  (println "Hello"))

;; Tasks should be vars (#'hello instead of hello) so that `clj -Mdev help` can
;; print their docstrings.
(def custom-tasks
  {"hello" #'hello
   "download-airtable" #'airtable/download-all-records})

(def tasks (merge tasks/tasks custom-tasks))
