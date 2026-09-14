(ns tech.jgood.gleanmo.duration
  "Whole-second durations as `H:MM:SS` text.

   Shared by CRUD inputs, converters, and list formatting for fields marked
   `:crud/duration-format :hms`, and by the goals dashboard, so the parser and
   the display cannot disagree about the format."
  (:require
   [clojure.string :as str]))

(defn format-hms
  "Format whole seconds as `H:MM:SS`. Hours are not capped at 23. Returns nil
   for nil."
  [seconds]
  (when (some? seconds)
    (let [total (long (Math/floor (double seconds)))
          sign  (if (neg? total) "-" "")
          total (Math/abs total)
          h     (quot total 3600)
          m     (quot (rem total 3600) 60)
          s     (rem total 60)]
      (format "%s%d:%02d:%02d" sign h m s))))

(defn parse-hms
  "Parse nonnegative whole seconds (`\"5400\"`) or `H:MM:SS` (`\"1:30:00\"`),
   with minutes and seconds in 00–59. Returns a long, or nil for input that
   matches neither form. Fractions and signs are rejected."
  [s]
  (let [s (some-> s str str/trim)]
    (cond
      (str/blank? s) nil

      (re-matches #"\d+" s)
      (parse-long s)

      :else
      (when-let [[_ h m sec] (re-matches #"(\d+):([0-5]\d):([0-5]\d)" s)]
        (+ (* 3600 (parse-long h))
           (* 60 (parse-long m))
           (parse-long sec))))))
