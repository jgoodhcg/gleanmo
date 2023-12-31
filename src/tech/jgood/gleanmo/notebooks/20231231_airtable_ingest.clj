(ns tech.jgood.gleanmo.notebooks.20231231-airtable-ingest
  (:require [nextjournal.clerk :as clerk]
            [semantic-csv.core :as sc]
            [clojure.java.io :as io]
            [potpuri.core :as pot]
            [clojure-csv.core :as csv]
            [clojure.string :as str]
            [tick.core :as t]))

;; Hello
(def exercise-log-file "/notebook_data/2023-12-31__13_47_51_223729_exercise_log.edn")

(first (slurp exercise-log-file))
