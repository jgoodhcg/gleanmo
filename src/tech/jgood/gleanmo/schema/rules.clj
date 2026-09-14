(ns tech.jgood.gleanmo.schema.rules
  "Write-time rules that the closed malli schemas cannot carry without
   becoming unparseable by the CRUD field parser (cross-field goal rules, the
   positive-finite exercise duration, relation ownership).

   `db/mutations.clj` applies these to the complete document on every create
   and update, so the rules hold for browser forms, custom screens, REPL
   writes, and imports alike."
  (:require
   [tech.jgood.gleanmo.goals.registry :as registry]
   [tech.jgood.gleanmo.goals.validation :as goal-validation]))

(def checked-entities
  "Entity keys with write-time rules, and the attributes whose change on an
   update requires re-checking. `:all` means any update."
  {:goal          :all
   :exercise-line #{:exercise-line/duration-seconds}})

(defn needs-check?
  "Whether a create or an update touching `data` must be checked."
  [entity-key data]
  (let [trigger (get checked-entities entity-key)]
    (cond
      (nil? trigger)  false
      (= :all trigger) true
      :else           (boolean (some #(contains? data %) trigger)))))

(defn- ownership-errors
  "Errors for relation filters naming records the goal's owner cannot use.
   `owned-ids` is `(fn [entity-type ids] owned-subset)`."
  [goal owned-ids]
  (into {}
        (keep (fn [[_ {:keys [relation]}]]
                (let [{k :key entity :entity} relation
                      ids (get goal k)]
                  (when (and k (seq ids))
                    (let [owned (owned-ids entity ids)]
                      (when (not= (set ids) (set owned))
                        [k ["Some selected records are unavailable."]]))))))
        registry/sources))

(defn write-errors
  "`{field-key [message ...]}` for the complete document `doc` of
   `entity-key`; empty when the write may proceed."
  [entity-key doc {:keys [owned-ids]}]
  (case entity-key
    :goal
    (let [errors (goal-validation/goal-errors doc)]
      (if (and owned-ids (empty? errors))
        (ownership-errors doc owned-ids)
        errors))

    :exercise-line
    (let [d (:exercise-line/duration-seconds doc)]
      (if (and (some? d) (not (goal-validation/finite-positive? d)))
        {:exercise-line/duration-seconds
         ["Duration must be a positive number of seconds."]}
        {}))

    {}))
