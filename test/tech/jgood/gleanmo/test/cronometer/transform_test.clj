(ns tech.jgood.gleanmo.test.cronometer.transform-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [malli.core :as m]
   [malli.error :as me]
   [tech.jgood.gleanmo :as main]
   [tech.jgood.gleanmo.cronometer.nutrients :as nutrients]
   [tech.jgood.gleanmo.cronometer.transform :as transform]
   [tech.jgood.gleanmo.schema.meta :as sm]
   [tech.jgood.gleanmo.test.cronometer.fixtures :as fx])
  (:import
   [java.time Instant]))

(def synced-at (Instant/parse "2030-01-16T12:00:00Z"))

(defn- docs
  ([] (docs fx/day fx/diary))
  ([day diary]
   (transform/diary->docs {:user-id        fx/user-id
                           :zone           fx/zone
                           :day            day
                           :diary          diary
                           :foods          fx/foods
                           :metric-catalog fx/metric-catalog
                           :synced-at      synced-at})))

(defn- approx=
  [a b]
  (< (Math/abs (- (double a) (double b))) 1e-9))

(defn- by-cronometer-id
  [k ds id]
  (some #(when (= id (get % k)) %) ds))

(defn- biometric
  [result id]
  (by-cronometer-id :cronometer/biometric-id (:biometric-logs result) id))

(defn- food-log
  [result id]
  (by-cronometer-id :cronometer/serving-id (:food-logs result) id))

(defn- stored
  "The document as `create-entity!` would store it."
  [doc]
  (assoc doc ::sm/created-at synced-at))

(deftest nutrient-table-test
  (testing "keys are unique"
    (is (= (count nutrients/by-id) (count nutrients/by-key))))
  (testing "parentheticals and punctuation fold into kebab-case keys"
    (is (= :b1-thiamine (get-in nutrients/by-id [404 :key])))
    (is (= :lutein-zeaxanthin (get-in nutrients/by-id [338 :key])))
    (is (= :ug (get-in nutrients/by-id [418 :unit])))))

(deftest scaling-test
  (let [result (docs)]
    (testing "a stock food scales its per-100 g nutrients by grams / 100"
      (let [log (food-log result 5001)]
        (is (approx= 320 (get-in log [:food-log/nutrients :energy])))
        (is (approx= 8 (get-in log [:food-log/nutrients :protein])))
        (is (= "cup" (:food-log/portion-label log)))
        (is (approx= 1 (:food-log/portion-quantity log)))))
    (testing "a recipe serving scales by the serving count in grams"
      (let [log (food-log result 5003)]
        (is (approx= 660 (get-in log [:food-log/nutrients :energy])))
        (is (approx= 1.5 (:food-log/grams log)))
        (is (= "full recipe" (:food-log/portion-label log)))))
    (testing "ids missing from the nutrient table stay out of the map"
      (is (= #{:energy :protein :carbs :fat :fiber}
             (set (keys (:food-log/nutrients (food-log result 5001)))))))
    (testing "summed food-log nutrients equal the day's totals"
      (let [totals (transform/macro-totals (:food-logs result))]
        (doseq [[k v] fx/expected-totals]
          (is (approx= v (get totals k)) (str k)))))))

(deftest food-test
  (let [foods (into {} (map (juxt :cronometer/food-id identity)) (:foods (docs)))
        oats  (get foods 101)
        recipe (get foods 201)]
    (testing "stock foods keep their tags and are kind :food"
      (is (= :food (:food/kind oats)))
      (is (= #{"Vegan" "Whole"} (:food/tags oats))))
    (testing "a recipe has kind :recipe, no tags, and ingredients pointing at food docs"
      (is (= :recipe (:food/kind recipe)))
      (is (nil? (:food/tags recipe)))
      (is (= 999 (:cronometer/owner recipe)))
      (is (= [(:xt/id oats) (:xt/id (get foods 102))]
             (mapv :food-id (:food/ingredients recipe))))
      (is (= "cup" (:portion-label (first (:food/ingredients recipe))))))))

(deftest food-log-time-test
  (let [result (docs)
        log    (food-log result 5001)]
    (testing "wall-clock time is read in the given zone"
      (is (= (Instant/parse "2030-01-15T12:30:00Z") (:food-log/timestamp log)))
      (is (= :logged (:food-log/timestamp-basis log))))
    (testing "raw lineage is kept; a nil offset is left out"
      (is (= fx/day (:cronometer/day log)))
      (is (= "07:30:00" (:cronometer/time log)))
      (is (= 0 (:cronometer/offset log)))
      (is (not (contains? (food-log result 5003) :cronometer/offset))))
    (testing "a serving without a time lands at local midnight"
      (let [timeless (transform/serving->food-log
                      fx/user-id fx/zone (dissoc fx/serving-oats :time) fx/oats synced-at)]
        (is (= (Instant/parse "2030-01-15T05:00:00Z") (:food-log/timestamp timeless)))
        (is (= :day-start (:food-log/timestamp-basis timeless)))))))

(deftest biometric-test
  (let [result (docs)]
    (testing "blood pressure is one row with both values and units"
      (let [bp (biometric result 7002)]
        (is (= 118 (:biometric-log/bp-systolic bp)))
        (is (= 76 (:biometric-log/bp-diastolic bp)))
        (is (= :mmhg (:biometric-log/bp-systolic-unit bp)))
        (is (= :mmhg (:biometric-log/bp-diastolic-unit bp)))
        (is (= "Blood Pressure" (:biometric-log/label bp)))))
    (testing "weight is stored in the unit given, and a stale composite is ignored"
      (let [weight (biometric result 7001)]
        (is (= 170.5 (:biometric-log/weight weight)))
        (is (= :lbs (:biometric-log/weight-unit weight)))
        (is (not (contains? weight :biometric-log/bp-systolic)))))
    (testing "glucose in mg/dL"
      (is (= :mg-dl (:biometric-log/blood-glucose-unit (biometric result 7003)))))
    (testing "Oura per-day values take the Sleep entry's wake time"
      (let [rec (biometric result 7005)]
        (is (= (Instant/parse "2030-01-15T13:15:30Z") (:biometric-log/timestamp rec)))
        (is (= :wake-time (:biometric-log/timestamp-basis rec)))
        (is (= 80 (:biometric-log/recovery rec)))
        (is (= "Oura" (:biometric-log/source rec)))
        (is (= :celsius (:biometric-log/temp-variation-unit (biometric result 7006))))))
    (testing "the Sleep entry itself keeps its logged bedtime"
      (let [s (biometric result 7004)]
        (is (= :logged (:biometric-log/timestamp-basis s)))
        (is (= 7.5 (:biometric-log/sleep s)))
        (is (= :hours (:biometric-log/sleep-unit s)))))
    (testing "an unmapped metric keeps its label and raw only"
      (let [mood (biometric result 7007)]
        (is (= "Mood" (:biometric-log/label mood)))
        (is (some? (:cronometer/raw mood)))
        (is (empty? (filter #(and (= "biometric-log" (namespace %))
                                  (not (#{"timestamp" "timestamp-basis" "label"} (name %))))
                            (keys mood))))))
    (testing "a unit outside the field's enum gets no typed value and a warning"
      (let [hrv (biometric result 7008)]
        (is (not (contains? hrv :biometric-log/hrv)))
        (is (= -12032 (:cronometer/offset hrv)))
        (is (some #(= 7008 (:biometricId %)) (:warnings result)))))
    (testing "with no Sleep entry a timeless reading lands at local midnight"
      (let [rec (biometric (docs fx/day-without-sleep fx/diary-without-sleep) 7105)]
        (is (= (Instant/parse "2030-01-16T05:00:00Z") (:biometric-log/timestamp rec)))
        (is (= :day-start (:biometric-log/timestamp-basis rec)))))))

(deftest nutrition-day-test
  (let [day (:nutrition-day (docs))]
    (is (= (java.time.LocalDate/parse fx/day) (:nutrition-day/day day)))
    (is (true? (:nutrition-day/completed day)))
    (is (= (:summary fx/diary) (read-string (:nutrition-day/summary day))))
    (is (= [fx/exercise fx/walk fx/google-health]
           (read-string (:nutrition-day/exercises day))))))

(deftest activity-observation-test
  (let [by-id (into {} (map (juxt :cronometer/exercise-id identity))
                    (:activity-observations (docs)))]
    (testing "a timed entry is a session that begins at its time"
      (let [walk (by-id 9002)]
        (is (= :session (:activity-observation/scope walk)))
        (is (= "walking (Oura)" (:activity-observation/label walk)))
        (is (= "Oura" (:activity-observation/source walk)))
        ;; 09:45 in America/New_York, not the entry's -240 offset
        (is (= (Instant/parse "2030-01-15T14:45:00Z")
               (:activity-observation/beginning walk)))
        (testing "and ends `minutes` later, marked as derived"
          (is (= (Instant/parse "2030-01-15T15:25:00Z")
                 (:activity-observation/end walk)))
          (is (true? (:activity-observation/auto-ended walk))))
        (is (not (contains? walk :activity-observation/day)))
        (is (= -240 (:cronometer/offset walk)))
        (is (= (:externalId fx/walk) (:cronometer/external-id walk)))
        (is (= fx/walk (read-string (:cronometer/raw walk))))))
    (testing "an untimed Daily Activity is the day's aggregate, with no boundaries"
      (let [daily (by-id 9001)]
        (is (= :daily (:activity-observation/scope daily)))
        (is (= (java.time.LocalDate/parse fx/day) (:activity-observation/day daily)))
        (is (not-any? #(contains? daily %)
                      [:activity-observation/beginning :activity-observation/end
                       :activity-observation/auto-ended]))))
    (testing "any other untimed entry has an unknown scope"
      (is (= :unknown (:activity-observation/scope (by-id 9003))))
      (is (= (java.time.LocalDate/parse fx/day)
             (:activity-observation/day (by-id 9003)))))
    (testing "a timed entry without positive minutes gets a beginning only"
      (let [obs (transform/exercise->activity-observation
                 fx/user-id fx/zone (assoc fx/walk :minutes 0) synced-at)]
        (is (some? (:activity-observation/beginning obs)))
        (is (not (contains? obs :activity-observation/end)))
        (is (not (contains? obs :activity-observation/auto-ended)))))))

(deftest deterministic-ids-test
  (let [a (docs)
        b (docs)
        ids (fn [r] (map :xt/id (concat (:foods r) (:food-logs r)
                                        (:biometric-logs r)
                                        (:activity-observations r)
                                        [(:nutrition-day r)])))]
    (is (= (ids a) (ids b)))
    (is (apply distinct? (ids a)))
    (testing "ids are scoped to the user"
      (is (not= (transform/doc-id fx/user-id :food 101)
                (transform/doc-id (random-uuid) :food 101))))))

(deftest documents-validate-test
  (let [result (docs)
        all    (concat (:foods result) (:food-logs result)
                       (:biometric-logs result) (:activity-observations result)
                       [(:nutrition-day result)]
                       (:biometric-logs (docs fx/day-without-sleep
                                              fx/diary-without-sleep)))]
    (doseq [doc all
            :let [schema (::sm/type doc)
                  stored (stored doc)]]
      (is (m/validate schema stored main/malli-opts)
          (pr-str [schema (:xt/id doc)
                   (me/humanize (m/explain schema stored main/malli-opts))])))))
