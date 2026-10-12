(ns tech.jgood.gleanmo.cronometer.transform
  "Cronometer API payloads -> Gleanmo documents. Pure: no I/O, no clock, no
   logging. Problems that should not fail a day's write come back as
   `:warnings` for the caller to log.

   The shapes are documented in roadmap/056-cronometer-integration.md (Probe
   Findings and Data Model)."
  (:require
   [tech.jgood.gleanmo.cronometer.biometrics :as bio]
   [tech.jgood.gleanmo.cronometer.nutrients :as nutrients]
   [tech.jgood.gleanmo.schema.meta :as sm])
  (:import
   [java.nio.charset StandardCharsets]
   [java.time LocalDate LocalTime ZoneId]
   [java.util UUID]))

;; =============================================================================
;; Identity and time
;; =============================================================================

(defn doc-id
  "Name-based UUID of `<user-id>/<kind>/<key>`, so a re-sync addresses the same
   document without a lookup. The user id is part of the name because
   Cronometer food ids are global while `food` docs are per user."
  [user-id kind k]
  (UUID/nameUUIDFromBytes
   (.getBytes (str user-id "/" (name kind) "/" k) StandardCharsets/UTF_8)))

(defn ->instant
  "The instant of wall-clock `time-str` on `day-str` in `zone`. Responses carry
   zero-padded ISO days (\"2026-10-11\"); only requests use `YYYY-M-D`."
  [day-str time-str zone]
  (-> (LocalDate/parse day-str)
      (.atTime (LocalTime/parse time-str))
      (.atZone (ZoneId/of (str zone)))
      (.toInstant)))

(defn- day-start
  [day-str zone]
  (-> (LocalDate/parse day-str)
      (.atStartOfDay (ZoneId/of (str zone)))
      (.toInstant)))

(defn- lineage
  "Raw day/time/offset plus the payload, so a time-zone correction is a
   recompute rather than a re-fetch."
  [entry synced-at]
  (cond-> {:cronometer/raw       (pr-str entry)
           :cronometer/synced-at synced-at}
    (:day entry)            (assoc :cronometer/day (:day entry))
    (:time entry)           (assoc :cronometer/time (:time entry))
    (int? (:offset entry))  (assoc :cronometer/offset (:offset entry))))

;; =============================================================================
;; Nutrients and foods
;; =============================================================================

(defn translate-nutrients
  "Cronometer `[{:id :amount}]` -> `{nutrient-key (* amount factor)}`. Ids
   missing from the nutrient table stay in raw only."
  [nutrient-entries factor]
  (into {}
        (keep (fn [{:keys [id amount]}]
                (when-let [{k :key} (and (number? amount) (nutrients/by-id id))]
                  [k (* amount factor)])))
        nutrient-entries))

(defn recipe-measure?
  [measure]
  (= "Recipe" (:type measure)))

(defn serving-factor
  "What a food's nutrients are multiplied by for a serving of `grams`. Foods
   carry nutrients per 100 g. Recipes carry them per reference serving, and a
   serving on a `Recipe` measure records a serving count in `grams`."
  [measure grams]
  (if (recipe-measure? measure)
    grams
    (/ grams 100.0)))

(defn- find-measure
  [food measure-id]
  (some #(when (= measure-id (:id %)) %) (:measures food)))

(defn- portion
  "Portion label and quantity for `grams` on `measure`: `1.5` of \"cup\"."
  [measure grams]
  (cond-> {}
    (:name measure)
    (assoc :portion-label (:name measure))

    (and (number? grams) (number? (:value measure)) (pos? (:value measure)))
    (assoc :portion-quantity (/ (double grams) (:value measure)))))

(defn- ->measure
  [{:keys [id name type amount value]}]
  {:label                 (str name)
   :type                  (str type)
   :amount                (or amount 1)
   :value                 (or value 0)
   :cronometer/measure-id id})

(defn- ->ingredient
  [user-id foods-by-id {:keys [foodId measureId grams id]}]
  (let [measure (find-measure (get foods-by-id foodId) measureId)
        {:keys [portion-label portion-quantity]} (portion measure grams)]
    (cond-> {:food-id                  (doc-id user-id :food foodId)
             :grams                    grams
             :cronometer/food-id       foodId
             :cronometer/ingredient-id id}
      portion-label    (assoc :portion-label portion-label)
      portion-quantity (assoc :portion-quantity portion-quantity))))

(defn food->doc
  "A `food` document for a Cronometer food or recipe. `foods-by-id` (optional)
   holds the ingredient foods, for ingredient portion labels."
  ([user-id food synced-at]
   (food->doc user-id food synced-at {}))
  ([user-id food synced-at foods-by-id]
   (let [{:keys [id name source foodTags measures ingredients owner]} food
         recipe? (boolean (some recipe-measure? measures))]
     (cond-> {:xt/id              (doc-id user-id :food id)
              ::sm/type           :food
              :user/id            user-id
              :food/label         (str name)
              :food/kind          (if recipe? :recipe :food)
              :food/nutrients     (translate-nutrients (:nutrients food) 1)
              :cronometer/food-id id
              :cronometer/raw       (pr-str food)
              :cronometer/synced-at synced-at}
       (seq source)      (assoc :food/source source)
       (seq foodTags)    (assoc :food/tags (set foodTags))
       (seq measures)    (assoc :food/measures (mapv ->measure measures))
       (seq ingredients) (assoc :food/ingredients
                                (mapv #(->ingredient user-id foods-by-id %)
                                      ingredients))
       (int? owner)      (assoc :cronometer/owner owner)))))

(defn serving->food-log
  "A `food-log` document for one diary serving. `food` is the serving's food
   record, or nil when Cronometer did not return it — the log then has no
   label, portion or nutrients."
  [user-id zone serving food synced-at]
  (let [{:keys [servingId foodId measureId translationId order grams day time]} serving
        measure (find-measure food measureId)
        {:keys [portion-label portion-quantity]} (portion measure grams)]
    (cond-> (merge
             {:xt/id                    (doc-id user-id :food-log servingId)
              ::sm/type                 :food-log
              :user/id                  user-id
              :food-log/timestamp       (if time
                                          (->instant day time zone)
                                          (day-start day zone))
              :food-log/timestamp-basis (if time :logged :day-start)
              :food-log/food-id         (doc-id user-id :food foodId)
              :cronometer/serving-id    servingId
              :cronometer/food-id       foodId}
             (lineage serving synced-at))
      (:name food)          (assoc :food-log/label (:name food))
      (number? grams)       (assoc :food-log/grams grams)
      portion-label         (assoc :food-log/portion-label portion-label)
      portion-quantity      (assoc :food-log/portion-quantity portion-quantity)
      (and food measure (number? grams))
      (assoc :food-log/nutrients
             (translate-nutrients (:nutrients food)
                                  (serving-factor measure grams)))
      (int? measureId)      (assoc :cronometer/measure-id measureId)
      (int? translationId)  (assoc :cronometer/translation-id translationId)
      (int? order)          (assoc :cronometer/order order))))

;; =============================================================================
;; Biometrics
;; =============================================================================

(defn- entries-of-type
  [diary type-name]
  (filter #(= type-name (:type %)) (:diary diary)))

(defn- wake-instant
  "The day's wake time from its Sleep entry (`meta.wakeup_time`), or nil."
  [diary zone]
  (some (fn [{:keys [metricId meta day]}]
          (when (and (= bio/sleep-metric metricId) (:wakeup_time meta))
            (->instant day (:wakeup_time meta) zone)))
        (entries-of-type diary "Biometric")))

(defn- typed-value
  "`{field value, unit-field unit}` for `value` in Cronometer unit `unit-id`,
   or a warning when the unit is unknown or outside the field's enum."
  [field value unit-id units entry]
  (let [unit-name (get units unit-id)
        unit      (bio/unit-keywords unit-name)]
    (if (and (number? value) (bio/unit-allowed? field unit))
      {:fields {field value, (bio/unit-field field) unit}}
      {:warning {:message     "Biometric unit not stored as a typed value"
                 :field       field
                 :unit        unit-name
                 :unit-id     unit-id
                 :biometricId (:biometricId entry)}})))

(defn- biometric-values
  "Typed fields for one entry, with any warnings."
  [{:keys [metricId unitId amount meta] :as entry} units]
  (let [parts (cond
                ;; Composites are read only on the composite metric. A manual
                ;; weight entry has been seen carrying a stale composite.
                (= bio/blood-pressure-metric metricId)
                (keep (fn [{part-metric :metricId part-unit :unitId value :value}]
                        (when-let [field (bio/composite-fields part-metric)]
                          (typed-value field value part-unit units entry)))
                      (:composite meta))

                (bio/metric-fields metricId)
                [(typed-value (bio/metric-fields metricId) amount unitId units entry)]

                :else [])]
    {:fields   (apply merge {} (keep :fields parts))
     :warnings (vec (keep :warning parts))}))

(defn- biometric-timestamp
  "Timestamp and basis for a reading.

   Oura's per-day values (Recovery, Sleep Score, Respiration Rate, Body Temp
   Variation) arrive with no time. They describe the night that ended that
   morning and become knowable at waking, so they take the day's Sleep entry
   wake time (`:wake-time`). With no time and no Sleep entry that day, the
   reading falls back to local midnight (`:day-start`)."
  [{:keys [day time]} zone wake]
  (cond
    time {:timestamp (->instant day time zone), :basis :logged}
    wake {:timestamp wake, :basis :wake-time}
    :else {:timestamp (day-start day zone), :basis :day-start}))

(defn- biometric->doc
  [user-id zone {:keys [metrics units]} wake synced-at entry]
  (let [{:keys [biometricId metricId unitId externalId source]} entry
        {:keys [timestamp basis]} (biometric-timestamp entry zone wake)
        {:keys [fields warnings]} (biometric-values entry units)
        metric-name (:name (get metrics metricId))]
    {:doc      (cond-> (merge
                        {:xt/id                          (doc-id user-id :biometric-log biometricId)
                         ::sm/type                       :biometric-log
                         :user/id                        user-id
                         :biometric-log/timestamp        timestamp
                         :biometric-log/timestamp-basis  basis
                         :biometric-log/label            (or metric-name
                                                             (str "Metric " metricId))
                         :cronometer/biometric-id        biometricId
                         :cronometer/metric-id           metricId}
                        fields
                        (lineage entry synced-at))
                 (seq source)     (assoc :biometric-log/source source)
                 (int? unitId)    (assoc :cronometer/unit-id unitId)
                 (seq externalId) (assoc :cronometer/external-id externalId))
     :warnings warnings}))

(defn diary->biometric-logs
  "`{:docs [biometric-log] :warnings [...]}` for a day's Biometric entries.
   `metric-catalog` is the `get_metrics` `:metrics` vector."
  [user-id zone diary metric-catalog synced-at]
  (let [index   (bio/catalog-index metric-catalog)
        wake    (wake-instant diary zone)
        results (map #(biometric->doc user-id zone index wake synced-at %)
                     (entries-of-type diary "Biometric"))]
    {:docs     (mapv :doc results)
     :warnings (into [] (mapcat :warnings) results)}))

;; =============================================================================
;; Day
;; =============================================================================

(defn diary->nutrition-day
  "The `nutrition-day` document for `day-str`'s diary."
  [user-id day-str diary synced-at]
  (let [exercises (vec (entries-of-type diary "Exercise"))]
    (cond-> {:xt/id                (doc-id user-id :nutrition-day day-str)
             ::sm/type             :nutrition-day
             :user/id              user-id
             :nutrition-day/day    (LocalDate/parse day-str)
             :cronometer/day       day-str
             :cronometer/synced-at synced-at}
      (boolean? (get-in diary [:info :complete]))
      (assoc :nutrition-day/completed (get-in diary [:info :complete]))
      (some? (:summary diary))  (assoc :nutrition-day/summary (pr-str (:summary diary)))
      (some? (:template diary)) (assoc :nutrition-day/template (pr-str (:template diary)))
      (some? (:fasts diary))    (assoc :nutrition-day/fasts (pr-str (:fasts diary)))
      (seq exercises)           (assoc :nutrition-day/exercises (pr-str exercises)))))

(defn servings
  "A diary's Serving entries."
  [diary]
  (vec (entries-of-type diary "Serving")))

(defn ingredient-food-ids
  "Cronometer food ids of every ingredient of `foods`."
  [foods]
  (into #{} (comp (mapcat :ingredients) (keep :foodId)) foods))

(defn diary->docs
  "Every document for one day: `{:foods :food-logs :biometric-logs
   :nutrition-day :warnings}`.

   `foods` are the food records for the day's servings and their recipe
   ingredients, recursively."
  [{:keys [user-id zone day diary foods metric-catalog synced-at]}]
  (let [foods-by-id (into {} (map (juxt :id identity)) foods)
        servings    (servings diary)
        missing     (remove foods-by-id (distinct (keep :foodId servings)))
        bio         (diary->biometric-logs user-id zone diary metric-catalog synced-at)]
    {:foods          (mapv #(food->doc user-id % synced-at foods-by-id) foods)
     :food-logs      (mapv #(serving->food-log user-id zone %
                                               (get foods-by-id (:foodId %))
                                               synced-at)
                           servings)
     :biometric-logs (:docs bio)
     :nutrition-day  (diary->nutrition-day user-id day diary synced-at)
     :warnings       (cond-> (:warnings bio)
                       (seq missing)
                       (conj {:message  "Cronometer returned no food record"
                              :food-ids (vec missing)}))}))

(defn macro-totals
  "Summed nutrients over `food-logs`, for checking against the diary summary."
  [food-logs]
  (apply merge-with + {} (keep :food-log/nutrients food-logs)))
