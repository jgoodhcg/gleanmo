(ns tech.jgood.gleanmo.fake-cronometer.data
  "Invented Cronometer payloads for the dev-only fake API
   (`tech.jgood.gleanmo.fake-cronometer`, roadmap/056-cronometer-integration.md).

   Every food, amount, reading and name here is made up; nothing is read or
   derived from a real account, because this repository is public. Nutrient,
   metric and unit ids are Cronometer catalog ids, which are definitions.

   Pure and deterministic: the same (Cronometer user id, day) always yields the
   same diary, and neighbouring days differ. Shapes follow roadmap 056 Probe
   Findings. The diary `summary` is the day's servings summed with the scaling
   rule from those findings — computed here independently of
   `cronometer.transform`, so a test comparing the two checks both."
  (:require
   [clojure.string :as str]
   [tech.jgood.gleanmo.cronometer.nutrients :as nutrients])
  (:import
   [java.time LocalDate]
   [java.util SplittableRandom]))

;; =============================================================================
;; Identity
;; =============================================================================

(defn user-id
  "The fake account id for a login email: any email logs in, and the same
   email is always the same account."
  [email]
  (+ 1000000 (mod (Math/abs (long (.hashCode (str/lower-case (str email)))))
                  9000000)))

(defn- epoch-day
  ^long [^LocalDate day]
  (.toEpochDay day))

(def ^:private serving-id-base 500000000)
(def ^:private biometric-id-base 600000000)
(def ^:private exercise-id-base 700000000)

(defn- entry-id
  "Ids are unique per day and decode back to it, so `get_nutrition_scores`
   needs no state."
  [base ^LocalDate day i]
  (+ base (* 100 (epoch-day day)) i))

(defn serving-day
  "The day a fake `servingId` belongs to."
  ^LocalDate [serving-id]
  (LocalDate/ofEpochDay (quot (- serving-id serving-id-base) 100)))

;; =============================================================================
;; Foods
;; =============================================================================

(defn- nutrient-entries
  "`{nutrient-key amount}` -> `[{:id :amount :type}]`, plus `extra`
   `{id amount}` for ids the definition catalog lacks, which real foods carry."
  [amounts extra]
  (into (mapv (fn [[k amount]]
                {:id (:id (nutrients/by-key k)), :amount amount, :type "PRIMARY"})
              amounts)
        (map (fn [[id amount]] {:id id, :amount amount, :type "PRIMARY"}))
        extra))

(defn- household-measure-id [food-id] (+ (* 10 food-id) 1))
(defn- gram-measure-id [food-id] (+ (* 10 food-id) 2))

(defn- stock-food
  "A catalog food: nutrients per 100 g, a household measure and grams."
  [id label tags [measure-label measure-type grams] amounts & [extra]]
  {:id        id
   :name      label
   :source    (str "FAKEDB:" id)
   :foodTags  tags
   :owner     0
   :measures  [{:id     (household-measure-id id), :name measure-label
                :type   measure-type, :amount 1, :value grams}
               {:id     (gram-measure-id id), :name "g"
                :type   "Weight", :amount 1, :value 1}]
   ;; -203 mirrors protein, as on nearly every real food.
   :nutrients (nutrient-entries amounts (merge {-203 (:protein amounts 0)} extra))})

(def ^:private oats 910001)
(def ^:private milk 910002)
(def ^:private yogurt 910003)
(def ^:private blueberries 910004)
(def ^:private almonds 910005)
(def ^:private honey 910006)
(def ^:private coffee 910007)
(def ^:private lentils 910008)
(def ^:private carrots 910009)
(def ^:private olive-oil 910010)
(def ^:private rice 910011)
(def ^:private chicken 910012)
(def ^:private banana 910013)
(def ^:private bread 910014)
(def ^:private peanut-butter 910015)
(def ^:private vitamin-d 910016)

(def ^:private granola 920001)
(def ^:private yogurt-bowl 920002)
(def ^:private lentil-soup 920003)

(def ^:private stock-foods
  [(stock-food oats "Rolled Oats (Fake)" ["Vegan" "Whole"] ["cup" "Weight" 80]
               {:energy 379 :protein 13.2 :carbs 67.7 :fat 6.5 :fiber 10.1
                :sugars 1 :sodium 6 :iron 4.3})
   (stock-food milk "Whole Milk (Fake)" ["Vegetarian"] ["cup" "Volume" 244]
               {:energy 61 :protein 3.2 :carbs 4.8 :fat 3.3 :fiber 0
                :sugars 5.1 :sodium 43 :calcium 113})
   (stock-food yogurt "Plain Greek Yogurt (Fake)" ["Vegetarian"] ["cup" "Volume" 245]
               {:energy 97 :protein 9 :carbs 3.9 :fat 5 :fiber 0
                :sugars 3.6 :sodium 35 :calcium 100})
   (stock-food blueberries "Blueberries (Fake)" ["Vegan" "Whole"] ["cup" "Volume" 148]
               {:energy 57 :protein 0.7 :carbs 14.5 :fat 0.3 :fiber 2.4
                :sugars 10 :sodium 1 :vitamin-c 9.7})
   (stock-food almonds "Almonds (Fake)" ["Vegan" "Whole" "Nuts"] ["oz" "Weight" 28.35]
               {:energy 579 :protein 21.2 :carbs 21.6 :fat 49.9 :fiber 12.5
                :sugars 4.4 :sodium 1 :calcium 269}
               {652 0.01})
   (stock-food honey "Honey (Fake)" ["Vegetarian"] ["tbsp" "Weight" 21]
               {:energy 304 :protein 0.3 :carbs 82.4 :fat 0 :fiber 0.2
                :sugars 82.1 :sodium 4})
   (stock-food coffee "Brewed Coffee (Fake)" ["Vegan"] ["cup" "Volume" 237]
               {:energy 1 :protein 0.1 :carbs 0 :fat 0 :fiber 0
                :sugars 0 :sodium 2 :caffeine 40})
   (stock-food lentils "Red Lentils, Cooked (Fake)" ["Vegan" "Whole"] ["cup" "Weight" 198]
               {:energy 116 :protein 9 :carbs 20.1 :fat 0.4 :fiber 7.9
                :sugars 1.8 :sodium 2 :iron 3.3})
   (stock-food carrots "Carrots (Fake)" ["Vegan" "Whole"] ["medium" "Weight" 61]
               {:energy 41 :protein 0.9 :carbs 9.6 :fat 0.2 :fiber 2.8
                :sugars 4.7 :sodium 69 :vitamin-a 835})
   (stock-food olive-oil "Olive Oil (Fake)" ["Vegan"] ["tbsp" "Volume" 13.5]
               {:energy 884 :protein 0 :carbs 0 :fat 100 :fiber 0
                :sugars 0 :sodium 2}
               {652 0.02})
   (stock-food rice "Brown Rice, Cooked (Fake)" ["Vegan" "Whole"] ["cup" "Weight" 195]
               {:energy 123 :protein 2.7 :carbs 25.6 :fat 1 :fiber 1.6
                :sugars 0.2 :sodium 4})
   (stock-food chicken "Roasted Chicken Breast (Fake)" ["Whole"] ["oz" "Weight" 28.35]
               {:energy 165 :protein 31 :carbs 0 :fat 3.6 :fiber 0
                :sugars 0 :sodium 74})
   (stock-food banana "Banana (Fake)" ["Vegan" "Whole"] ["medium" "Weight" 118]
               {:energy 89 :protein 1.1 :carbs 22.8 :fat 0.3 :fiber 2.6
                :sugars 12.2 :sodium 1 :potassium 358})
   (stock-food bread "Whole Wheat Bread (Fake)" ["Vegan" "Brand"] ["slice" "Weight" 32]
               {:energy 252 :protein 12.4 :carbs 42.7 :fat 3.5 :fiber 6
                :sugars 5.6 :sodium 410})
   (stock-food peanut-butter "Peanut Butter (Fake)" ["Vegan" "Nuts" "Brand"] ["tbsp" "Weight" 16]
               {:energy 588 :protein 25.1 :carbs 19.6 :fat 50.4 :fiber 6
                :sugars 9.2 :sodium 17})
   (stock-food vitamin-d "Vitamin D3 Softgel (Fake)" ["Supplement"] ["softgel" "Weight" 0.25]
               {:energy 0 :protein 0 :carbs 0 :fat 0 :vitamin-d 400000})])

(defn- recipe-measure? [measure] (= "Recipe" (:type measure)))

(defn- factor
  "The scaling rule (roadmap 056 Probe Findings): nutrients are per 100 g,
   or per reference serving for a `Recipe` measure, where `grams` is a serving
   count. A recipe ingredient on a recipe's `g` measure is real grams of a
   recipe whose reference serving weighs the measure's value."
  [measure grams]
  (if (recipe-measure? measure)
    (if (= "g" (:name measure))
      (/ grams (double (:value measure)))
      grams)
    (/ grams 100.0)))

(defn- find-measure
  [food measure-id]
  (some #(when (= measure-id (:id %)) %) (:measures food)))

(defn- scaled-nutrients
  "`{nutrient-id amount}` for `grams` of `food` on `measure-id`."
  [food measure-id grams]
  (let [f (factor (find-measure food measure-id) grams)]
    (into {} (map (fn [{:keys [id amount]}] [id (* amount f)])) (:nutrients food))))

(defn- full-recipe-measure-id [food-id] (+ (* 10 food-id) 2))
(defn- recipe-gram-measure-id [food-id] (+ (* 10 food-id) 1))

(defn- recipe
  "A custom recipe from `parts` (`[food-id grams]`, grams of the ingredient's
   own weight). Nutrients are per one reference serving: the full recipe."
  [foods-by-id id label parts]
  (let [ingredients (map-indexed
                     (fn [i [food-id grams]]
                       (let [food     (foods-by-id food-id)
                             recipe?  (some recipe-measure? (:measures food))
                             measure  (if recipe?
                                        (recipe-gram-measure-id food-id)
                                        (household-measure-id food-id))]
                         {:id        (+ (* 100 id) i)
                          :foodId    food-id
                          :measureId measure
                          :grams     grams
                          :value     grams}))
                     parts)
        weight      (reduce + 0.0 (map second parts))
        totals      (apply merge-with +
                           (for [{:keys [foodId measureId grams]} ingredients]
                             (scaled-nutrients (foods-by-id foodId) measureId grams)))]
    {:id          id
     :name        label
     :source      "Custom"
     ;; Custom recipes carry no foodTags (roadmap 056 Probe Findings).
     :foodTags    []
     :measures    [{:id (recipe-gram-measure-id id), :name "g"
                    :type "Recipe", :amount 1, :value weight}
                   {:id (full-recipe-measure-id id), :name "full recipe"
                    :type "Recipe", :amount 1, :value 1}]
     :ingredients (vec ingredients)
     :nutrients   (mapv (fn [[nid amount]] {:id nid, :amount amount, :type "PRIMARY"})
                        (sort-by key totals))}))

(def ^:private catalog
  "Food id -> food, owner left for `food` to fill. Granola is an ingredient of
   the yogurt bowl: the nested recipe."
  (let [stock (into {} (map (juxt :id identity)) stock-foods)
        with  (fn [m id label parts] (assoc m id (recipe m id label parts)))]
    (-> stock
        (with granola "Maple Granola (Fake)"
              [[oats 300] [almonds 100] [honey 84] [olive-oil 27]])
        (with yogurt-bowl "Yogurt Bowl (Fake)"
              [[yogurt 245] [blueberries 74] [granola 60]])
        (with lentil-soup "Lentil Soup (Fake)"
              [[lentils 594] [carrots 122] [olive-oil 13.5]]))))

(defn food
  "The food record for `food-id` as account `uid` sees it, or nil. Custom
   recipes are owned by the account; catalog foods by 0."
  [uid food-id]
  (when-let [f (catalog food-id)]
    (if (= "Custom" (:source f)) (assoc f :owner uid) f)))

;; =============================================================================
;; Randomness
;; =============================================================================

(defn- rng
  ^SplittableRandom [uid ^LocalDate day salt]
  (SplittableRandom. (+ (* 1000003 (long uid)) (* 31 (epoch-day day)) salt)))

(defn- between
  [^SplittableRandom r lo hi]
  (+ lo (* (.nextDouble r) (- hi lo))))

(defn- pick
  [^SplittableRandom r coll]
  (nth coll (.nextInt r (count coll))))

(defn- round-to
  [places x]
  (let [m (Math/pow 10 places)]
    (/ (Math/round (* (double x) m)) m)))

(defn- clock
  "A random \"HH:MM:SS\" between minute-of-day `from` and `to`."
  [r from to]
  (let [m (long (between r from to))]
    (format "%02d:%02d:00" (quot m 60) (mod m 60))))

(defn- day-str [^LocalDate day] (str day))

;; =============================================================================
;; Servings
;; =============================================================================

(def ^:private breakfast 65537)
(def ^:private lunch 131073)
(def ^:private dinner 196609)
(def ^:private snacks 262145)

(defn- portion
  "`[food-id measure-id grams]`: `n` household measures of a catalog food, or
   `n` servings of a recipe."
  [food-id n]
  (let [f (catalog food-id)]
    (if (= "Custom" (:source f))
      [food-id (full-recipe-measure-id food-id) n]
      [food-id (household-measure-id food-id)
       (round-to 2 (* n (:value (find-measure f (household-measure-id food-id)))))])))

(defn- weighed
  "`grams` of a catalog food on its household measure."
  [food-id grams]
  [food-id (household-measure-id food-id) (round-to 0 grams)])

(defn- meal-plan
  "`[[order from-minute to-minute [portion ...]] ...]` for one day."
  [r]
  ;; After the latest wake time `biometric-entries` produces (08:28).
  [[breakfast 515 560
    [(portion yogurt-bowl (pick r [1 1 1.5]))
     (portion coffee (pick r [1 1 2]))
     (portion vitamin-d 1)]]
   [lunch 720 810
    (pick r [[(portion lentil-soup (pick r [0.25 0.33 0.5]))
              (portion bread (pick r [1 2]))]
             [(weighed chicken (between r 100 160))
              (portion rice (pick r [0.5 1]))]])]
   [snacks 900 990
    (pick r [[(portion banana 1)]
             [(portion almonds (pick r [1 1.5]))]
             [(portion peanut-butter 2) (portion bread 1)]])]
   [dinner 1110 1200
    (pick r [[(weighed chicken (between r 120 180))
              (portion rice 1)
              (portion carrots 2)]
             [(portion lentil-soup 0.5) (portion bread 1)]
             [(portion lentils 1) (portion rice 0.5) (portion olive-oil 1)]])]])

(defn- servings
  [uid ^LocalDate day]
  (let [r (rng uid day 1)]
    (vec
     (map-indexed
      (fn [i [order time [food-id measure-id grams]]]
        {:type          "Serving"
         :day           (day-str day)
         :servingId     (entry-id serving-id-base day i)
         :foodId        food-id
         :measureId     measure-id
         :grams         grams
         :time          time
         ;; The supplement is logged with a nil offset, as one real serving was.
         :offset        (if (= vitamin-d food-id) nil 0)
         :order         order
         :translationId 0})
      (for [[order from to portions] (meal-plan r)
            :let [time (clock r from to)]
            p portions]
        [order time p])))))

(defn serving-nutrients
  "`{nutrient-id amount}` for one serving, by the scaling rule."
  [uid {:keys [foodId measureId grams]}]
  (scaled-nutrients (food uid foodId) measureId grams))

(defn- consumed
  [uid day-servings]
  (let [totals (apply merge-with + {} (map #(serving-nutrients uid %) day-servings))
        amount #(get totals (:id (nutrients/by-key %)) 0)]
    {:total     (amount :energy)
     :protein_g (amount :protein)
     :carbs_g   (amount :carbs)
     :fat_g     (amount :fat)
     :fiber_g   (amount :fiber)}))

;; =============================================================================
;; Biometrics and exercise
;; =============================================================================

(def metric-catalog
  "The `get_metrics` catalog subset the fake serves: the metrics its diaries
   use, with Cronometer's metric and unit ids."
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

(defn- add-minutes
  "\"HH:MM:SS\" plus `hours` (decimal)."
  [time-str hours]
  (let [[h m] (map parse-long (str/split time-str #":"))
        total (+ (* 60 h) m (Math/round (* 60.0 hours)))]
    (format "%02d:%02d:00" (mod (quot total 60) 24) (mod total 60))))

(defn- biometric-entries
  "Readings in the order a diary lists them, without ids."
  [uid ^LocalDate day]
  (let [r         (rng uid day 2)
        n         (epoch-day day)
        ;; Bedtime after midnight, so it reads as a time on the wake day.
        bedtime   (clock r 5 40)
        asleep    (round-to 2 (between r 6.0 7.2))
        in-bed    (round-to 2 (+ asleep (between r 0.3 0.6)))
        wakeup    (add-minutes bedtime in-bed)
        weight    (round-to 1 (+ 150 (mod uid 40)
                                 (* 3 (Math/sin (/ n 23.0)))
                                 (between r -0.6 0.6)))
        morning   (add-minutes wakeup (between r 0.1 0.25))]
    (cond-> [{:metricId 764 :unitId 763 :amount asleep :time bedtime :offset -240
              :source "Oura" :externalId (str "fake-sleep-" uid "-" n)
              :meta {:wakeup_time  wakeup
                     :time_in_bed  in-bed
                     :stage_summary {:deep  (round-to 2 (* asleep 0.2))
                                     :rem   (round-to 2 (* asleep 0.22))
                                     :light (round-to 2 (* asleep 0.58))
                                     :awake (round-to 2 (- in-bed asleep))}}}
             ;; Oura per-day values: no time.
             {:metricId 47185 :unitId 47127 :amount (long (between r 58 93))
              :source "Oura" :externalId (str "fake-readiness-" uid "-" n) :meta {}}
             {:metricId 52242 :unitId 52186 :amount (round-to 2 (between r -0.4 0.4))
              :source "Oura" :meta {}}
             {:metricId 40002 :unitId 39945 :amount (long (between r 28 66))
              :time (add-minutes bedtime 3) :offset -240 :source "Oura" :meta {}}
             {:metricId 1 :unitId 2 :amount weight :time morning :offset 0 :meta {}}
             ;; Blood pressure: one entry, a placeholder amount, a composite.
             {:metricId 32524 :unitId 32455 :amount 1 :time (add-minutes morning 0.1)
              :offset 0
              :meta {:composite [{:metricId 4 :unitId 6 :value (long (between r 108 129))}
                                 {:metricId 5 :unitId 6 :value (long (between r 66 83))}]}}
             {:metricId 6 :unitId 9 :amount (long (between r 81 99))
              :time (add-minutes morning 0.2) :offset 0 :meta {}}]
      (zero? (mod n 3))
      (conj {:metricId 8 :unitId 13 :amount (round-to 1 (between r 17 21))
             :time (add-minutes morning 0.3) :offset 0 :meta {}})

      (even? n)
      (conj {:metricId 14 :unitId 24 :amount (long (between r 5 9))
             :time (clock r 1230 1290) :offset 0 :meta {}}))))

(defn- biometrics
  [uid ^LocalDate day]
  (vec (map-indexed (fn [i entry]
                      (merge {:type        "Biometric"
                              :day         (day-str day)
                              :biometricId (entry-id biometric-id-base day i)
                              :order       (inc i)}
                             entry))
                    (biometric-entries uid day))))

(defn- exercise
  [uid ^LocalDate day]
  (let [r (rng uid day 3)]
    {:type       "Exercise"
     :day        (day-str day)
     :exerciseId (entry-id exercise-id-base day 0)
     :name       "Daily Activity (Oura)"
     :calories   (- (long (between r 240 620)))
     :minutes    (long (between r 70 190))
     :source     "Oura"
     :meta       {}}))

;; =============================================================================
;; Responses
;; =============================================================================

(defn diary
  "The `get_diary` response for account `uid` on `day`."
  [uid ^LocalDate day]
  (let [r            (rng uid day 4)
        day-servings (servings uid day)
        activity     (exercise uid day)]
    {:info     {:complete (< (.nextDouble r) 0.85), :day (day-str day)}
     :summary  {:consumed (consumed uid day-servings)
                :burned   {:total (+ 1750 (- (:calories activity)))}}
     :template {:name "Balanced (Fake)" :protein 130 :carbs 220 :fat 75}
     :fasts    []
     :diary    (vec (concat [activity] (biometrics uid day) day-servings))}))

(defn foods
  "The `get_foods` response: the records for `ids` this fake knows."
  [uid ids]
  {:foods (vec (keep #(food uid %) ids))})

(defn nutrient-definitions
  "The `get_nutrients` response: the definition catalog, no amounts."
  []
  {:nutrients (mapv (fn [{:keys [id label unit category]}]
                      {:id id, :name label, :unit (name unit), :category category})
                    nutrients/ordered)})

(defn nutrition-scores
  "The `get_nutrition_scores` response for `serving-ids`: consumed amounts per
   catalogued nutrient. The real shape was not recorded in roadmap 056; this
   one is invented, and nothing in the sync reads it."
  [uid serving-ids]
  (let [wanted (set serving-ids)
        found  (for [d (distinct (map serving-day serving-ids))
                     s (servings uid d)
                     :when (wanted (:servingId s))]
                 s)
        totals (apply merge-with + {} (map #(serving-nutrients uid %) found))]
    {:scores (vec (for [[id amount] (sort-by key totals)
                        :when (nutrients/by-id id)]
                    {:id id, :amount amount, :confidence 1.0}))}))

(def ^:private conversions
  "[from-unit to-unit] -> multiplier."
  {[2 1]     0.45359237
   [1 2]     (/ 1 0.45359237)
   [2 68524] (/ 1 14.0)
   [1 68524] (/ 1 6.35029318)
   [9 8]     (/ 1 18.0)
   [8 9]     18.0})

(defn- reading-value
  "The value of `metric-id` on `day`, in `unit-id`, or nil. Systolic and
   diastolic come from the blood pressure composite."
  [uid day metric-id unit-id]
  (when-let [[from value] (some (fn [{:keys [metricId unitId amount meta]}]
                                  (cond
                                    (= metric-id metricId)
                                    [unitId amount]

                                    (= 32524 metricId)
                                    (some #(when (= metric-id (:metricId %))
                                             [(:unitId %) (:value %)])
                                          (:composite meta))))
                                (biometric-entries uid day))]
    (if (= from unit-id)
      value
      (round-to 2 (* value (get conversions [from unit-id] 1))))))

(defn biometric-series
  "The `get_biometrics` response: `{:data [{:day :value}]}` for one metric over
   `start`..`end` inclusive, converted to `unit-id` where the fake knows how.
   Days without a reading are absent."
  [uid metric-id unit-id ^LocalDate start ^LocalDate end]
  {:data (vec (for [d (take-while #(not (.isAfter ^LocalDate % end))
                                  (iterate #(.plusDays ^LocalDate % 1) start))
                    :let [v (reading-value uid d metric-id unit-id)]
                    :when (some? v)]
                {:day (day-str d), :value v}))})
