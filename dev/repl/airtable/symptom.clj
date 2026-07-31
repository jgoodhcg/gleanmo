(ns repl.airtable.symptom
  "Airtable migration for the pain log -> symptom-log entities.

   Airtable pain rows map to symptom-log with :symptom-log/type :pain.
   The descriptive rating ('3 - It's impactful') becomes a numeric
   severity-score on the app's 1-13 Fibonacci scale; the 39-value area
   multi-select maps to :symptom-log/areas; pain-type descriptors map to
   :symptom-log/qualifiers. Original strings are preserved on
   airtable/original-* fields."
  (:require
   [clojure.string :as str]
   [malli.core :as m]
   [repl.airtable.core :as core]
   [tech.jgood.gleanmo :as main]
   [tech.jgood.gleanmo.schema.meta :as sm]
   [tech.jgood.gleanmo.schema.symptom-schema :as ss]))

(def symptom-log-namespace-uuid
  "Namespace UUID for symptom-log entities, derived from Airtable ids."
  #uuid "b8c9d0e1-f2a3-4567-bcde-7890abcdef12")

(defn- norm
  "Normalize an Airtable label to a kebab keyword-ish string."
  [s]
  (-> s str/trim str/lower-case (str/replace #"\s+" "-")))

(def area->keyword
  {"si" :si-joint "stomach" :stomach "groin" :groin "finger" :finger
   "shin" :shin "abdomen" :abdomen "oblique" :oblique "forearm" :forearm
   "shoulder" :shoulder "quad" :quad "thoracic" :thoracic
   "hip-flexor" :hip-flexor "bottom-of-foot" :bottom-of-foot "wrist" :wrist
   "glute" :glute "thumb" :thumb "pec" :pec "top-of-foot" :top-of-foot
   "ribs" :ribs "knee" :knee "leg" :leg "face" :face "elbow" :elbow
   "hip" :hip "chest" :chest "hands" :hand "head" :head "tailbone" :tailbone
   "bicep" :bicep "toes" :toes "jaw" :jaw "calf" :calf
   "upper-trap" :upper-trap "nostril" :nostril "lumbar" :lumbar "neck" :neck
   "eye" :eye "adductor" :adductor "sternum" :sternum})

(def pain-type->qualifier
  {"stiff" :stiff "tight" :tight "sensitive" :sensitive "pinch" :pinch
   "stabbing" :stabbing "sore" :sore "throb" :throbbing "ache" :ache
   "burn" :burn "kink" :kink "squeeze" :squeeze "tingling" :tingling
   "swollen" :swollen "numb" :numb "cramp" :cramp "doms" :doms
   "pressure" :pressure "trembling" :trembling "tense" :tense
   "flutter" :flutter "bloating" :bloating})

(def rating->severity-score
  "Airtable pain-scale option -> app severity-score.

   Matched on the label's leading number rather than the whole string, because
   the export has inconsistent separators and trailing spaces ('0.5 Barely
   there' vs '3 - It's impactful '). Airtable numbered these 0.5/1/2/3/5/8;
   the app's scale is the same six labels shifted one position up the Fibonacci
   sequence (see symptom-schema/severity-scale). An explicit map rather than
   parsing the leading number, so a new or renamed Airtable option surfaces as
   an unmapped warning instead of silently importing Airtable's numbering."
  {"0.5" 1
   "1"   2
   "2"   3
   "3"   5
   "5"   8
   "8"   13})

(defn- rating->score
  [rating]
  (some->> rating (re-find #"[\d.]+") rating->severity-score))

(defn airtable->symptom-log
  [{:strs [id createdTime fields]} user-id now]
  (let [{:strs [time areas rating side type notes]} fields
        created (core/parse-timestamp createdTime)
        ts      (or (core/parse-timestamp time) created)
        score   (rating->score rating)
        areas'  (set (keep #(area->keyword (norm %)) areas))
        quals   (set (keep #(pain-type->qualifier (norm %)) type))
        side'   (some-> side norm keyword #{:left :right :both})]
    (when (and rating (nil? score))
      (println "  WARN: unmapped pain rating:" (pr-str rating)))
    (cond->
     {:db/doc-type :symptom-log
      :xt/id (core/deterministic-uuid symptom-log-namespace-uuid id)
      ::sm/type :symptom-log
      ::sm/created-at created
      :user/id user-id
      :symptom-log/timestamp ts
      :symptom-log/type :pain
      :airtable/id id
      :airtable/created-time created
      :airtable/ported-at now}
      score (assoc :symptom-log/severity-score score)
      (seq areas') (assoc :symptom-log/areas areas')
      side' (assoc :symptom-log/side side')
      (seq quals) (assoc :symptom-log/qualifiers quals)
      notes (assoc :symptom-log/notes notes)
      rating (assoc :airtable/original-rating rating)
      (seq areas) (assoc :airtable/original-areas (str/join ", " areas))
      (seq type) (assoc :airtable/original-type (str/join ", " type)))))

(defn validate-symptom-logs
  [logs]
  (let [registry  (:registry main/malli-opts)
        validator (m/validator ss/symptom-log {:registry registry})
        results   (map (fn [e] {:entity e :valid? (validator e)}) logs)
        failed    (filter #(not (:valid? %)) results)]
    {:passed (count (filter :valid? results))
     :failed (map :entity failed)
     :total  (count results)}))
