(ns repl.airtable.mood
  "Airtable migration for mood-log entities.

   Airtable has two tables: 'moods' (32 Plutchik-wheel emotion labels with
   emoji, e.g. \"😊 serenity\") and 'mood log' (timestamp, single mood link,
   notes). The app schema instead uses circumplex dimensions (valence x
   arousal — Russell 1980), so each Plutchik label maps to a fixed
   valence/arousal pair below; the raw label is preserved on
   airtable/original-mood.

   Mapping rationale: Plutchik's wheel is eight emotion families at three
   intensities plus dyads. Intensity within a family moves both valence
   extremity and arousal (serenity -> joy -> ecstasy), which translates
   cleanly onto the circumplex. Placements follow published affective
   norms (Russell's circumplex; Warriner et al. valence/arousal ratings)
   rounded to the schema's 5-point enums.

   Export quirks (2026-07-26): one log row has a timestamp but no mood
   link and no notes — it is skipped and counted in the report. One label
   has a leading space (\" 😩 remorse\") and 'ecstacy' is misspelled, so
   labels are matched on their extracted lowercase word."
  (:require
   [clojure.string :as str]
   [malli.core :as m]
   [repl.airtable.core :as core]
   [tech.jgood.gleanmo :as main]
   [tech.jgood.gleanmo.schema.meta :as sm]
   [tech.jgood.gleanmo.schema.mood-schema :as ms]))

(def mood-log-namespace-uuid
  "Namespace UUID for mood-log entities, derived from Airtable ids."
  #uuid "a5b6c7d8-e9f0-4bcd-8ef0-123456789012")

(def word->circumplex
  "Plutchik emotion word -> circumplex coordinates. Covers all 32 labels
   in the Airtable moods table (both spellings of ecstasy)."
  {;; joy family
   "serenity"       {:valence :pleasant       :arousal :low}
   "joy"            {:valence :very-pleasant  :arousal :high}
   "ecstacy"        {:valence :very-pleasant  :arousal :very-high}
   "ecstasy"        {:valence :very-pleasant  :arousal :very-high}
   ;; trust family
   "acceptance"     {:valence :pleasant       :arousal :low}
   "trust"          {:valence :pleasant       :arousal :moderate}
   "admiration"     {:valence :very-pleasant  :arousal :moderate}
   ;; fear family
   "apprehension"   {:valence :unpleasant     :arousal :moderate}
   "fear"           {:valence :very-unpleasant :arousal :high}
   "terror"         {:valence :very-unpleasant :arousal :very-high}
   ;; surprise family
   "distraction"    {:valence :neutral        :arousal :moderate}
   "surprise"       {:valence :neutral        :arousal :high}
   "amazement"      {:valence :neutral        :arousal :very-high}
   ;; sadness family
   "pensiveness"    {:valence :unpleasant     :arousal :very-low}
   "sadness"        {:valence :unpleasant     :arousal :low}
   "grief"          {:valence :very-unpleasant :arousal :moderate}
   ;; disgust family
   "boredom"        {:valence :unpleasant     :arousal :very-low}
   "disgust"        {:valence :unpleasant     :arousal :moderate}
   "loathing"       {:valence :very-unpleasant :arousal :high}
   ;; anger family
   "annoyance"      {:valence :unpleasant     :arousal :moderate}
   "anger"          {:valence :very-unpleasant :arousal :high}
   "rage"           {:valence :very-unpleasant :arousal :very-high}
   ;; anticipation family
   "interest"       {:valence :pleasant       :arousal :moderate}
   "anticipation"   {:valence :pleasant       :arousal :high}
   "vigilance"      {:valence :pleasant       :arousal :very-high}
   ;; dyads
   "love"           {:valence :very-pleasant  :arousal :moderate}
   "optimism"       {:valence :pleasant       :arousal :moderate}
   "submission"     {:valence :neutral        :arousal :moderate}
   "awe"            {:valence :pleasant       :arousal :high}
   "disapproval"    {:valence :unpleasant     :arousal :moderate}
   "remorse"        {:valence :unpleasant     :arousal :low}
   "contempt"       {:valence :unpleasant     :arousal :moderate}
   "aggressiveness" {:valence :unpleasant     :arousal :high}})

(defn label->word
  "Extract the emotion word from an Airtable mood Name (strips emoji,
   skin-tone modifiers, and stray whitespace)."
  [label]
  (some-> label (->> (re-find #"[A-Za-z]+")) str/lower-case))

(defn mood-lookup
  "Airtable mood record id -> raw Name string."
  [mood-records]
  (into {}
        (keep (fn [{:strs [id fields]}]
                (when-let [n (get fields "Name")] [id n])))
        mood-records))

(defn airtable->mood-log
  "Nil when the row has no mood link (nothing to interpret)."
  [{:strs [id createdTime fields]} mood-id->label user-id now]
  (let [{:strs [timestamp notes mood]} fields
        label   (some-> mood first mood-id->label)
        coords  (some-> label label->word word->circumplex)
        created (core/parse-timestamp createdTime)
        ts      (or (core/parse-timestamp timestamp) created)]
    (when label
      (when-not coords
        (println "  WARN: unmapped mood label:" (pr-str label)))
      (cond->
       {:db/doc-type :mood-log
        :xt/id (core/deterministic-uuid mood-log-namespace-uuid id)
        ::sm/type :mood-log
        ::sm/created-at created
        :user/id user-id
        :mood-log/timestamp ts
        :mood-log/valence (get coords :valence :neutral)
        :airtable/id id
        :airtable/created-time created
        :airtable/ported-at now
        :airtable/original-mood label}
        (:arousal coords) (assoc :mood-log/arousal (:arousal coords))
        notes (assoc :mood-log/notes notes)))))

(defn validate-mood-logs
  [logs]
  (let [registry  (:registry main/malli-opts)
        validator (m/validator ms/mood-log {:registry registry})
        results   (map (fn [e] {:entity e :valid? (validator e)}) logs)
        failed    (filter #(not (:valid? %)) results)]
    {:passed (count (filter :valid? results))
     :failed (map :entity failed)
     :total  (count results)}))
