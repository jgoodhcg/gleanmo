(ns tech.jgood.gleanmo.cronometer.biometrics
  "Cronometer metric and unit tables for `biometric-log`.

   Diary entries carry only a `metricId` and `unitId`. Names come from the
   metric catalog (`get_metrics`), which lists each metric with its units; unit
   ids are unique across the whole catalog, so one id -> name index serves
   both a reading's own unit and the units inside a blood pressure composite."
  (:require
   [tech.jgood.gleanmo.schema.biometric-schema :as bios]
   [tech.jgood.gleanmo.schema.utils :as schema-utils]))

(def unit-keywords
  "Cronometer unit name -> the keyword stored in a `-unit` field."
  {"lbs"    :lbs
   "kg"     :kg
   "st"     :st
   "%"      :percent
   "mmHg"   :mmhg
   "mg/dL"  :mg-dl
   "mmol/L" :mmol-l
   "bpm"    :bpm
   "ms"     :ms
   "brpm"   :brpm
   "hr:min" :hours
   "°C"     :celsius
   "°F"     :fahrenheit
   "Kelvin" :kelvin})

(def metric-fields
  "Cronometer metric id -> the `biometric-log` value field it fills. A metric
   absent here lands as a row with `label` and raw only, until it earns
   fields."
  {1     :biometric-log/weight
   8     :biometric-log/body-fat
   6     :biometric-log/blood-glucose
   3     :biometric-log/heart-rate
   40002 :biometric-log/hrv
   50088 :biometric-log/respiration-rate
   ;; `hr:min` unit, but the amount is decimal hours
   764   :biometric-log/sleep
   57418 :biometric-log/sleep-score
   47185 :biometric-log/recovery
   52242 :biometric-log/temp-variation})

(def blood-pressure-metric
  "Blood pressure is one entry: `amount` is a placeholder `1` and the values
   sit in `meta.composite` under the systolic and diastolic metric ids."
  32524)

(def composite-fields
  "Composite part metric id -> field, for blood pressure only."
  {4 :biometric-log/bp-systolic
   5 :biometric-log/bp-diastolic})

(def sleep-metric 764)

(defn unit-field
  "`:biometric-log/weight` -> `:biometric-log/weight-unit`."
  [field]
  (keyword (namespace field) (str (name field) "-unit")))

(def ^:private allowed-units
  "Value field -> the unit keywords its `-unit` enum accepts, read off the
   schema so the two cannot drift."
  (into {}
        (for [field (concat (vals metric-fields) (vals composite-fields))
              :let  [t (schema-utils/field-type
                        (schema-utils/schema-field bios/biometric-log
                                                   (unit-field field)))]]
          [field (set (remove map? (rest t)))])))

(defn unit-allowed?
  "Whether `unit` (a keyword) fits `field`'s `-unit` enum."
  [field unit]
  (contains? (get allowed-units field) unit))

(defn catalog-index
  "Index a `get_metrics` catalog: `{:metrics {id metric} :units {id name}}`."
  [metrics]
  {:metrics (into {} (map (juxt :id identity)) metrics)
   :units   (into {}
                  (for [{:keys [units]} metrics
                        {:keys [id name]} units]
                    [id name]))})
