(ns tech.jgood.gleanmo.prediction.heuristics
  (:require
   [clojure.string :as str]
   [tick.core :as t])
  (:import
   [java.time LocalDate]
   [java.time.temporal ChronoUnit]))

(defn- date-str->local-date
  "Convert a date string in format YYYY-MM-DD to a LocalDate object."
  [date-str]
  (LocalDate/parse date-str))

(defn- local-date->str
  "Convert a LocalDate to a string in format YYYY-MM-DD."
  [local-date]
  (str local-date))

(defn- days-between
  "Calculate days between two date strings."
  [date-str-1 date-str-2]
  (let [d1 (date-str->local-date date-str-1)
        d2 (date-str->local-date date-str-2)]
    (.between ChronoUnit/DAYS d1 d2)))

(defn- days-since
  "Calculate days since a date string to current date.
   Returns a positive number if date is in the past, negative if in the future."
  [date-str]
  (let [d1 (date-str->local-date date-str)
        now (LocalDate/now)]
    (.between ChronoUnit/DAYS d1 now)))

(defn- intervals
  "Calculate intervals (in days) between consecutive dates."
  [dates]
  (map (fn [[d1 d2]] (days-between d1 d2))
       (partition 2 1 dates)))

(defn calculate-most-common-interval
  "Find the most common interval between dates.
   Returns nil if there are fewer than 2 dates."
  [dates]
  (when (>= (count dates) 2)
    (let [intervals-seq (intervals dates)
          interval-freqs (frequencies intervals-seq)]
      (when (seq interval-freqs)
        (key (apply max-key val interval-freqs))))))

(defn predict-by-mode
  "Predict next date based on most common interval.
   Returns nil if prediction can't be made."
  [dates]
  (when-let [most-common-interval (calculate-most-common-interval dates)]
    (when-let [last-date (last dates)]
      (let [last-local-date (date-str->local-date last-date)
            next-date (.plusDays last-local-date most-common-interval)]
        (local-date->str next-date)))))

(defn calculate-average-interval
  "Calculate the average interval between dates.
   Returns nil if there are fewer than 2 dates."
  [dates]
  (when (>= (count dates) 2)
    (let [intervals-seq (intervals dates)]
      (when (seq intervals-seq)
        (let [avg (double (/ (apply + intervals-seq)
                           (count intervals-seq)))]
          (int (Math/round avg)))))))

(defn predict-by-average
  "Predict next date based on average interval.
   Returns nil if prediction can't be made."
  [dates]
  (when-let [avg-interval (calculate-average-interval dates)]
    (when-let [last-date (last dates)]
      (let [last-local-date (date-str->local-date last-date)
            next-date (.plusDays last-local-date avg-interval)]
        (local-date->str next-date)))))

(defn detect-weekly-pattern
  "Detect if the dates follow a weekly pattern (same day of week).
   Returns day of week (1-7, Monday is 1) if pattern exists, nil otherwise."
  [dates]
  (when (>= (count dates) 3)
    (let [days-of-week (map #(.getDayOfWeek (date-str->local-date %)) dates)
          freq (frequencies days-of-week)]
      (when (= 1 (count freq))
        (first days-of-week)))))

(defn detect-monthly-pattern
  "Detect if the dates follow a monthly pattern (same day of month).
   Returns day of month (1-31) if pattern exists, nil otherwise."
  [dates]
  (when (>= (count dates) 3)
    (let [days-of-month (map #(.getDayOfMonth (date-str->local-date %)) dates)
          freq (frequencies days-of-month)]
      (when (= 1 (count freq))
        (first days-of-month)))))

(defn predict-by-pattern
  "Predict next date based on weekly or monthly patterns.
   Returns nil if no clear pattern is detected."
  [dates]
  (when-let [last-date (last dates)]
    (let [last-local-date (date-str->local-date last-date)]
      (cond
        ;; Weekly pattern (same day of week)
        (detect-weekly-pattern dates)
        (let [target-day-of-week (detect-weekly-pattern dates)
              current-day-of-week (.getDayOfWeek last-local-date)
              days-to-add (if (= target-day-of-week current-day-of-week)
                            7 ; Same day, so add a week
                            (mod (- (+ (.getValue target-day-of-week) 7)
                                   (.getValue current-day-of-week))
                                7))]
          (local-date->str (.plusDays last-local-date days-to-add)))
        
        ;; Monthly pattern (same day of month)
        (detect-monthly-pattern dates)
        (let [target-day (detect-monthly-pattern dates)
              current-month (.getMonthValue last-local-date)
              next-month (if (= 12 current-month) 1 (inc current-month))
              next-year (if (= 12 current-month) 
                          (inc (.getYear last-local-date))
                          (.getYear last-local-date))
              max-days-in-month (.lengthOfMonth (LocalDate/of next-year next-month 1))
              actual-day (min target-day max-days-in-month)]
          (local-date->str (LocalDate/of next-year next-month actual-day)))
        
        :else nil))))

(defn predict-next-dates
  "Predict next dates using multiple strategies.
   Returns a map with predictions from different methods."
  [date-strings]
  (let [sorted-dates (sort date-strings)]
    (try
      {:mode-prediction (predict-by-mode sorted-dates)
       :average-prediction (predict-by-average sorted-dates)
       :pattern-prediction (predict-by-pattern sorted-dates)
       :days-since-last (when-let [last-date (last sorted-dates)] 
                          (days-since last-date))}
      (catch Exception e
        (println "Error in predict-next-dates:" (.getMessage e))
        {}))))

(defn format-prediction-date
  "Format a prediction date with readable description."
  [date days-since-last method-name]
  (when date
    (try
      {:date date
       :description (str method-name ": " date 
                        (when days-since-last
                          (str " (" 
                               (if (pos? days-since-last)
                                 (str days-since-last " days ago")
                                 (str (Math/abs days-since-last) " days from now"))
                               ")")))}
      (catch Exception e
        (println "Error in format-prediction-date:" (.getMessage e))
        {:date date
         :description (str method-name ": " date)}))))

(defn get-predictions
  "Get formatted predictions for a set of dates."
  [dates]
  (try
    (let [{:keys [mode-prediction average-prediction 
                  pattern-prediction days-since-last]} (predict-next-dates dates)]
      (remove nil?
              [(format-prediction-date mode-prediction days-since-last "Most frequent interval")
               (format-prediction-date average-prediction days-since-last "Average interval")
               (format-prediction-date pattern-prediction days-since-last "Pattern-based")]))
    (catch Exception e
      (println "Error in get-predictions:" (.getMessage e))
      [])))