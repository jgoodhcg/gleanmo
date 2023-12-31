(ns tech.jgood.gleanmo.notebooks.2023-12-29-goals
  (:require [nextjournal.clerk :as clerk]
            [semantic-csv.core :as sc]
            [clojure.java.io :as io]
            [potpuri.core :as pot]
            [clojure-csv.core :as csv]
            [clojure.string :as str]
            [tick.core :as t]))

;; ## Load data from csv
(def e-file "notebook_data/exercises-everything.csv")

(def el-file "notebook_data/exercise-log-everything.csv")

(defn remove-non-printable [s]
  (str/replace s #"\p{C}" ""))

(defn clean-keys [keys]
  (map remove-non-printable keys))

(defn process-csv [file]
  (with-open [in-file (io/reader file)]
    (let [lines         (csv/parse-csv in-file)
          cleaned-lines (lazy-seq
                         (cons (clean-keys (first lines))
                               (rest lines)))]
      (->> cleaned-lines
           (sc/remove-comments)
           (sc/mappify)
           doall))))

(def exercise-logs (process-csv el-file))

;; Daily average in interval
(defn parse-int [s]
  (try
    (Integer/parseInt s)
    (catch NumberFormatException e 0)))

(defn within-interval? [start end log]
  (let [log-date (t/date (:day log))]
    (and (t/<= start log-date)
         (t/<= log-date end))))

(comment
  (->> exercise-logs
       (map (partial within-interval?
                     (t/date "2023-11-10")
                     (t/date "2023-11-15")))
       #_#_
       (group-by identity)
       (pot/map-vals count))
  )
(defn daily-average-reps [logs start end]
  (let [filtered-logs (filter #(within-interval? % start end) logs)
        #_#_#_#_
        total-reps (reduce + 0 (map #(parse-int (:reps %)) filtered-logs))
        interval-days (t/days (t/new-duration start end))]
    filtered-logs
    #_
    (if (zero? interval-days)
      0
      (/ total-reps interval-days))))

(def start-date (t/date "2023-11-10"))
(def end-date (t/date "2023-11-15"))

(def avg-reps (daily-average-reps exercise-logs start-date end-date))
