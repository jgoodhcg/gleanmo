(ns tech.jgood.gleanmo.test.cronometer.fixtures
  "Synthetic Cronometer payloads, hand-written to mirror the shapes in
   roadmap/056-cronometer-integration.md (Probe Findings). Nothing here comes
   from a real account: foods, amounts, readings, ids and dates are invented.
   Metric, unit and nutrient ids are Cronometer's catalog ids, which are
   definitions rather than personal data.")

(def user-id #uuid "00000000-0000-4000-8000-000000000001")

(def zone "America/New_York")

(def day "2030-01-15")

;; -----------------------------------------------------------------------------
;; Foods. Stock foods carry nutrients per 100 g; the recipe per one reference
;; serving. Ids -203 and 652 are absent from the nutrient table on purpose.
;; -----------------------------------------------------------------------------

(def oats
  {:id 101
   :name "Test Oats"
   :source "TESTDB:1"
   :foodTags ["Vegan" "Whole"]
   :owner 0
   :measures [{:id 1001 :name "cup" :type "Weight" :amount 1 :value 80}
              {:id 1002 :name "g" :type "Weight" :amount 1 :value 1}]
   :nutrients [{:id 208 :amount 400 :type "PRIMARY"}
               {:id 203 :amount 10 :type "PRIMARY"}
               {:id 205 :amount 60 :type "PRIMARY"}
               {:id 204 :amount 5 :type "PRIMARY"}
               {:id 291 :amount 8 :type "PRIMARY"}
               {:id -203 :amount 10 :type "PRIMARY"}
               {:id 652 :amount 0.1 :type "PRIMARY"}]})

(def milk
  {:id 102
   :name "Test Milk"
   :source "TESTDB:2"
   :foodTags ["Vegetarian"]
   :owner 0
   :measures [{:id 1011 :name "cup" :type "Volume" :amount 1 :value 240}]
   :nutrients [{:id 208 :amount 50 :type "PRIMARY"}
               {:id 203 :amount 3.5 :type "PRIMARY"}
               {:id 205 :amount 5 :type "PRIMARY"}
               {:id 204 :amount 2 :type "PRIMARY"}
               {:id 307 :amount 40 :type "PRIMARY"}]})

;; 80 g oats + 240 g milk, per one reference serving.
(def porridge
  {:id 201
   :name "Test Porridge"
   :source "Custom"
   :foodTags []
   :owner 999
   :measures [{:id 2001 :name "g" :type "Recipe" :amount 1 :value 320}
              {:id 2002 :name "full recipe" :type "Recipe" :amount 1 :value 1}]
   :ingredients [{:id 3001 :foodId 101 :measureId 1001 :grams 80 :value 80}
                 {:id 3002 :foodId 102 :measureId 1011 :grams 240 :value 240}]
   :nutrients [{:id 208 :amount 440 :type "PRIMARY"}
               {:id 203 :amount 16.4 :type "PRIMARY"}
               {:id 205 :amount 60 :type "PRIMARY"}
               {:id 204 :amount 8.8 :type "PRIMARY"}
               {:id 291 :amount 6.4 :type "PRIMARY"}
               {:id 307 :amount 96 :type "PRIMARY"}]})

(def foods [oats milk porridge])

;; -----------------------------------------------------------------------------
;; Metric catalog (subset of `get_metrics`)
;; -----------------------------------------------------------------------------

(def metric-catalog
  [{:id 1 :name "Weight" :units [{:id 1 :name "kg"} {:id 2 :name "lbs"}
                                 {:id 68524 :name "st"}]}
   {:id 8 :name "Body Fat" :units [{:id 13 :name "%"}]}
   {:id 6 :name "Blood Glucose" :units [{:id 8 :name "mmol/L"}
                                        {:id 9 :name "mg/dL"}]}
   {:id 32524 :name "Blood Pressure" :units [{:id 32455 :name "mmHg"}]}
   {:id 4 :name "Systolic Blood Pressure" :units [{:id 6 :name "mmHg"}]}
   {:id 5 :name "Diastolic Blood Pressure" :units [{:id 7 :name "mmHg"}]}
   {:id 40002 :name "Heart Rate Variability (HRV)" :units [{:id 39945 :name "ms"}]}
   {:id 764 :name "Sleep" :units [{:id 763 :name "hr:min"}]}
   {:id 47185 :name "Recovery" :units [{:id 47127 :name "%"}]}
   {:id 52242 :name "Body Temp Variation" :units [{:id 52186 :name "°C"}]}
   {:id 14 :name "Mood" :units [{:id 24 :name "Rating"}]}])

;; -----------------------------------------------------------------------------
;; Diary
;; -----------------------------------------------------------------------------

(def serving-oats
  {:type "Serving" :day day :servingId 5001 :foodId 101 :measureId 1001
   :grams 80 :time "07:30:00" :offset 0 :order 65537 :translationId 0})

(def serving-milk
  {:type "Serving" :day day :servingId 5002 :foodId 102 :measureId 1011
   :grams 120 :time "07:31:00" :offset 0 :order 65537 :translationId 11})

;; 1.5 recipe servings, logged with a nil offset.
(def serving-porridge
  {:type "Serving" :day day :servingId 5003 :foodId 201 :measureId 2002
   :grams 1.5 :time "12:15:00" :offset nil :order 131073 :translationId 0})

(def expected-totals
  "Oats 80 g + milk 120 g + porridge x1.5."
  {:energy  (+ 320.0 60.0 660.0)
   :protein (+ 8.0 4.2 24.6)
   :carbs   (+ 48.0 6.0 90.0)
   :fat     (+ 4.0 2.4 13.2)})

(def weight-with-stale-composite
  {:type "Biometric" :day day :biometricId 7001 :metricId 1 :unitId 2
   :amount 170.5 :time "08:00:00" :offset 0 :order 1
   :meta {:composite [{:metricId 4 :unitId 6 :value 110}
                      {:metricId 5 :unitId 7 :value 70}]}})

(def blood-pressure
  {:type "Biometric" :day day :biometricId 7002 :metricId 32524 :unitId 32455
   :amount 1 :time "08:05:00" :offset 0 :order 2
   :meta {:composite [{:metricId 4 :unitId 6 :value 118}
                      {:metricId 5 :unitId 6 :value 76}]}})

(def glucose
  {:type "Biometric" :day day :biometricId 7003 :metricId 6 :unitId 9
   :amount 95 :time "08:10:00" :offset 0 :order 3 :meta {}})

(def sleep
  {:type "Biometric" :day day :biometricId 7004 :metricId 764 :unitId 763
   :amount 7.5 :time "00:45:00" :offset -300 :source "Oura"
   :externalId "sleep-test-1"
   :meta {:wakeup_time "08:15:30" :time_in_bed 8.0}})

(def recovery
  {:type "Biometric" :day day :biometricId 7005 :metricId 47185 :unitId 47127
   :amount 80 :source "Oura" :externalId "readiness-test-1" :meta {}})

(def temp-variation
  {:type "Biometric" :day day :biometricId 7006 :metricId 52242 :unitId 52186
   :amount -0.1 :source "Oura" :meta {}})

(def mood
  {:type "Biometric" :day day :biometricId 7007 :metricId 14 :unitId 24
   :amount 7 :time "20:00:00" :offset 0 :meta {}})

;; A unit id the catalog does not list for HRV.
(def hrv-unknown-unit
  {:type "Biometric" :day day :biometricId 7008 :metricId 40002 :unitId 12345
   :amount 40 :time "08:20:00" :offset -12032 :source "Oura" :meta {}})

(def exercise
  {:type "Exercise" :day day :exerciseId 9001 :name "Daily Activity (Test)"
   :calories -300 :minutes 120 :source "Oura" :meta {}})

;; A timed session: `time` is when the walk began.
(def walk
  {:type "Exercise" :day day :exerciseId 9002 :name "walking (Oura)"
   :time "09:45:00" :offset -240 :calories -150 :minutes 40 :source "Oura"
   :externalId "00000000-0000-4000-8000-000000009002" :meta {}})

;; Untimed and not a "Daily Activity": its scope is unknown.
(def google-health
  {:type "Exercise" :day day :exerciseId 9003 :name "Google Health Activity"
   :calories -0.5 :minutes 0 :source "Google Health" :meta {}})

(def diary
  {:info     {:complete true :day day}
   :summary  {:consumed {:total 1040 :protein_g 36.8}
              :burned   {:total 2300}}
   :template {:name "Test Targets" :protein 120 :carbs 200 :fat 70}
   :fasts    []
   :diary    [exercise walk google-health sleep recovery temp-variation hrv-unknown-unit
              weight-with-stale-composite blood-pressure glucose mood
              serving-oats serving-milk serving-porridge]})

(def day-without-sleep "2030-01-16")

(def diary-without-sleep
  "A day with a timeless reading and no Sleep entry."
  {:info  {:complete false :day day-without-sleep}
   :diary [(assoc recovery :day day-without-sleep :biometricId 7105)]})
