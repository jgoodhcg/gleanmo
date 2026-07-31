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
   rounded to the schema's 5-point scales.

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
   in the Airtable moods table (both spellings of ecstasy).

   Both axes are signed and centred on neutral (-2..2), matching
   mood-schema/valence-scale and arousal-scale: 0 is neutral valence /
   moderate arousal, so each quadrant is a sign pair."
  {;; joy family
   "serenity"       {:valence  1 :arousal -1}
   "joy"            {:valence  2 :arousal  1}
   "ecstacy"        {:valence  2 :arousal  2}
   "ecstasy"        {:valence  2 :arousal  2}
   ;; trust family
   "acceptance"     {:valence  1 :arousal -1}
   "trust"          {:valence  1 :arousal  0}
   "admiration"     {:valence  2 :arousal  0}
   ;; fear family
   "apprehension"   {:valence -1 :arousal  0}
   "fear"           {:valence -2 :arousal  1}
   "terror"         {:valence -2 :arousal  2}
   ;; surprise family
   "distraction"    {:valence  0 :arousal  0}
   "surprise"       {:valence  0 :arousal  1}
   "amazement"      {:valence  0 :arousal  2}
   ;; sadness family
   "pensiveness"    {:valence -1 :arousal -2}
   "sadness"        {:valence -1 :arousal -1}
   "grief"          {:valence -2 :arousal  0}
   ;; disgust family
   "boredom"        {:valence -1 :arousal -2}
   "disgust"        {:valence -1 :arousal  0}
   "loathing"       {:valence -2 :arousal  1}
   ;; anger family
   "annoyance"      {:valence -1 :arousal  0}
   "anger"          {:valence -2 :arousal  1}
   "rage"           {:valence -2 :arousal  2}
   ;; anticipation family
   "interest"       {:valence  1 :arousal  0}
   "anticipation"   {:valence  1 :arousal  1}
   "vigilance"      {:valence  1 :arousal  2}
   ;; dyads
   "love"           {:valence  2 :arousal  0}
   "optimism"       {:valence  1 :arousal  0}
   "submission"     {:valence  0 :arousal  0}
   "awe"            {:valence  1 :arousal  1}
   "disapproval"    {:valence -1 :arousal  0}
   "remorse"        {:valence -1 :arousal -1}
   "contempt"       {:valence -1 :arousal  0}
   "aggressiveness" {:valence -1 :arousal  1}})

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
        :mood-log/valence (get coords :valence 0)
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
