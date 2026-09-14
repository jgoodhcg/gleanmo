(ns tech.jgood.gleanmo.goals.validation
  "Cross-field goal rules the closed malli schema cannot express. Pure: the
   ownership check needs the database and lives with the write boundary in
   `tech.jgood.gleanmo.schema.rules`."
  (:require
   [clojure.string :as str]
   [tech.jgood.gleanmo.goals.registry :as registry])
  (:import
   [java.time LocalDate ZoneId]))

(def ^:private zone-ids (delay (set (ZoneId/getAvailableZoneIds))))

(defn valid-time-zone?
  "True for an IANA region identifier such as `America/Detroit`."
  [s]
  (and (string? s) (contains? @zone-ids s)))

(defn finite-positive?
  [x]
  (and (number? x) (Double/isFinite (double x)) (pos? (double x))))

(defn- whole?
  [x]
  (== (double x) (Math/rint (double x))))

(defn- add-error
  [errors k msg]
  (update errors k (fnil conj []) msg))

(defn goal-errors
  "`{field-key [message ...]}` for every rule a goal breaks; empty when valid."
  [goal]
  (let [{:goal/keys [label target timing starts-on ends-on time-zone
                     threshold-step progress-measure even-pace-enabled
                     book-ids]} goal
        m          (registry/measurement goal)
        relation   (:relation m)
        completion (= :completion (:aggregation m))]
    (cond-> {}
      (or (not (string? label)) (str/blank? label))
      (add-error :goal/label "Give the goal a name.")

      (nil? m)
      (add-error :goal/measure
                 "This source does not support that measurement.")

      (and m (not (contains? (:timings m) timing)))
      (add-error :goal/timing
                 (str "This measurement supports "
                      (str/join ", " (map registry/timing-labels
                                          (sort (:timings m))))
                      " timing."))

      ;; Target
      (and m (not completion) (not (finite-positive? target)))
      (add-error :goal/target "Enter a target greater than zero.")

      (and m (not completion) (finite-positive? target) (:integer? m)
           (not (whole? target)))
      (add-error :goal/target "Counts need a whole-number target.")

      (and completion (some? target))
      (add-error :goal/target "Book completion goals have no numeric target.")

      ;; Threshold step
      (and completion (some? threshold-step))
      (add-error :goal/threshold-step
                 "Book completion goals have no thresholds.")

      (and (some? threshold-step) (not completion)
           (not (finite-positive? threshold-step)))
      (add-error :goal/threshold-step "A threshold step must be greater than zero.")

      (and (some? threshold-step) m (:integer? m)
           (finite-positive? threshold-step) (not (whole? threshold-step)))
      (add-error :goal/threshold-step "Counts need a whole-number threshold step.")

      ;; Book preferences only mean something for completion goals.
      (and (not completion) (some? progress-measure))
      (add-error :goal/progress-measure
                 "Only book completion goals chart reading positions.")

      (and (not completion) (true? even-pace-enabled))
      (add-error :goal/even-pace-enabled
                 "Only book completion goals have an even-pace guide.")

      ;; Dates
      (not (instance? LocalDate starts-on))
      (add-error :goal/starts-on "Choose a start date.")

      (and (= :dated timing) (nil? ends-on))
      (add-error :goal/ends-on "A dated goal needs an end date.")

      (and (= :open-ended timing) (some? ends-on))
      (add-error :goal/ends-on "An open-ended goal has no end date.")

      (and (instance? LocalDate starts-on) (instance? LocalDate ends-on)
           (.isBefore ^LocalDate ends-on starts-on))
      (add-error :goal/ends-on "The end date cannot precede the start date.")

      (not (valid-time-zone? time-zone))
      (add-error :goal/time-zone "Choose a time zone such as America/Detroit.")

      ;; Book completion needs exactly one book.
      (and completion (not= 1 (count book-ids)))
      (add-error :goal/book-ids "Choose exactly one book to finish.")

      ;; Relation filters: only the source's own, and never explicitly empty.
      true
      (as-> errors
            (reduce (fn [errors k]
                      (let [v (get goal k)]
                        (cond
                          (nil? v) errors

                          (not= k (:key relation))
                          (add-error errors k
                                     "This source cannot be filtered by that.")

                          (and (set? v) (empty? v))
                          (add-error errors k
                                     "Choose at least one, or clear the filter to count all.")

                          :else errors)))
                    errors
                    (sort registry/relation-keys))))))
