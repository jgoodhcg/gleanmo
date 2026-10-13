(ns tech.jgood.gleanmo.cronometer.sync
  "Re-pull recent Cronometer diary days into Gleanmo (roadmap/056, Slice B).

   Per day: fetch the diary and its foods, build documents with
   `cronometer.transform`, then write the day in one transaction so it lands
   whole or not at all. Document ids are derived from Cronometer keys, so a
   re-sync addresses the same documents: new ones are created, changed ones
   updated, unchanged ones left alone, and food logs, biometric logs and
   activity observations that vanished from a re-pulled day are soft-deleted.

   Unchanged documents are skipped rather than rewritten so a daily 7-day
   re-sync does not add a version of every row to XTDB history each day. The
   one exception is `nutrition-day`, rewritten on every pull: its
   `cronometer/synced-at` is how the status page knows when the last sync ran."
  (:require
   [clojure.tools.logging :as log]
   [tech.jgood.gleanmo.cronometer.client :as client]
   [tech.jgood.gleanmo.cronometer.connection :as connection]
   [tech.jgood.gleanmo.cronometer.transform :as transform]
   [tech.jgood.gleanmo.db.mutations :as mutations]
   [tech.jgood.gleanmo.db.queries :as queries]
   [tech.jgood.gleanmo.schema.meta :as sm]
   [tick.core :as t]
   [xtdb.api :as xt])
  (:import
   [java.time LocalDate ZoneId]))

(def ^:dynamic *day-pause-ms*
  "Pause between days, to keep the request rate low on an unofficial API."
  250)

(def default-days 7)

(defn recent-days
  "The last `n` days ending today in `zone`, oldest first."
  [zone n]
  (let [today (LocalDate/now (ZoneId/of (str zone)))]
    (mapv #(.minusDays today (long %)) (range (dec n) -1 -1))))

(defn user-zone
  [user]
  (or (:user/time-zone user) "UTC"))

;; =============================================================================
;; Fetch
;; =============================================================================

(defn fetch-foods
  "Food records for `food-ids` and, recursively, every recipe ingredient.
   Always refetched: custom recipes get edited."
  [conn food-ids]
  (loop [fetched   {}
         requested (set food-ids)
         pending   (set food-ids)]
    (if (empty? pending)
      (vec (vals fetched))
      (let [foods   (client/get-foods conn pending)
            fetched (into fetched (map (juxt :id identity)) foods)
            more    (remove requested (transform/ingredient-food-ids foods))]
        (recur fetched (into requested more) (set more))))))

;; =============================================================================
;; Write plan
;; =============================================================================

(def ^:private user-managed-keys
  "Keys the user sets on an imported document. Cronometer knows nothing of
   them, so a re-sync neither compares nor removes them."
  #{:activity-observation/exercise-session-id})

(def ^:private kept-keys
  "Keys a re-sync never removes from a stored document."
  (into #{:xt/id ::sm/type ::sm/created-at :user/id} user-managed-keys))

(defn- comparable
  [doc]
  (apply dissoc doc ::sm/created-at :cronometer/synced-at user-managed-keys))

(defn- update-data
  "The `:update` data that turns `stored` into `doc`: every key of `doc`, plus
   `:db/dissoc` for keys `stored` has and `doc` does not, so a field removed
   upstream does not linger. A soft-deleted document that reappears is
   restored."
  [stored doc]
  (merge (into {}
               (keep (fn [k]
                       (when-not (or (kept-keys k) (contains? doc k))
                         [k :db/dissoc])))
               (keys stored))
         (dissoc doc :xt/id ::sm/type)))

(defn plan-day
  "Creates and updates for one day's `docs` against `existing` (id -> stored
   doc), plus soft deletes for `vanished-ids` (`[entity-type id]`)."
  [docs existing vanished now]
  (let [ops (for [doc docs
                  :let [stored (get existing (:xt/id doc))
                        entity-key (::sm/type doc)]]
              (cond
                (nil? stored)
                [:create {:entity-key entity-key, :data doc}]

                (not= (comparable stored) (comparable doc))
                [:update {:entity-key entity-key
                          :entity-id  (:xt/id doc)
                          :data       (update-data stored doc)}]

                ;; Rewritten although unchanged: its synced-at records the sync.
                (= :nutrition-day entity-key)
                [:touch {:entity-key entity-key
                         :entity-id  (:xt/id doc)
                         :data       {:cronometer/synced-at (:cronometer/synced-at doc)}}]

                :else
                [:unchanged nil]))
        by-op (group-by first ops)]
    {:creates   (mapv second (:create by-op))
     :updates   (-> (mapv second (:update by-op))
                    (into (map second) (:touch by-op))
                    (into (for [[entity-key id] vanished]
                            {:entity-key entity-key
                             :entity-id  id
                             :data       {::sm/deleted-at now}})))
     :updated   (count (:update by-op))
     :unchanged (+ (count (:unchanged by-op)) (count (:touch by-op)))
     :deleted   (count vanished)}))

;; =============================================================================
;; Sync
;; =============================================================================

(def ^:private day-scoped-types
  "Types whose rows are removed when they vanish from a re-pulled day."
  [:food-log :biometric-log :activity-observation])

(defn sync-day!
  "Pull and write one day. Returns `{:day :created :updated :deleted
   :unchanged :warnings}`."
  [{:keys [biff.xtdb/node], :as ctx} conn user metric-catalog ^LocalDate day]
  (let [user-id   (:xt/id user)
        day-str   (str day)
        now       (t/now)
        diary     (client/get-diary conn day)
        food-ids  (distinct (keep :foodId (transform/servings diary)))
        foods     (fetch-foods conn food-ids)
        result    (transform/diary->docs {:user-id        user-id
                                          :zone           (user-zone user)
                                          :day            day-str
                                          :diary          diary
                                          :foods          foods
                                          :metric-catalog metric-catalog
                                          :synced-at      now})
        docs      (concat (:foods result) (:food-logs result)
                          (:biometric-logs result)
                          (:activity-observations result)
                          [(:nutrition-day result)])
        produced  (set (map :xt/id docs))
        db        (xt/db node)
        existing  (into {}
                        (map (juxt :xt/id identity))
                        (queries/fetch-entities-by-ids db (map :xt/id docs)))
        vanished  (for [entity-type day-scoped-types
                        id          (queries/cronometer-ids-for-day
                                     db user-id entity-type day-str)
                        :when       (not (produced id))]
                    [entity-type id])
        {:keys [creates updates] :as plan} (plan-day docs existing vanished now)]
    (doseq [w (:warnings result)]
      (log/warn "Cronometer sync" day-str w))
    (mutations/write-entities! ctx {:creates creates, :updates updates})
    {:day       day-str
     :created   (count creates)
     :updated   (:updated plan)
     :deleted   (:deleted plan)
     :unchanged (:unchanged plan)
     :warnings  (:warnings result)}))

(defn sync-days!
  "Sync `days` (oldest first) for `user` from the user's stored connection.
   Stops at the first failing day; earlier days stay written.

   Returns `{:days [day-summary ...]}`, with `:error` set when a day failed
   and `:not-connected true` when the user has no connection."
  [ctx user days]
  (let [user-id (:xt/id user)
        setup   (try
                  (when-let [conn (connection/client-connection ctx user-id)]
                    {:conn           conn
                     :metric-catalog (client/get-metrics conn)})
                  (catch Exception e
                    (log/error e "Cronometer sync could not start for user" user-id)
                    {:error (ex-message e)}))]
    (cond
      (nil? setup)     {:days [], :not-connected true}
      (:error setup)   {:days [], :error (:error setup)}
      :else
      (let [{:keys [conn metric-catalog]} setup]
        (loop [[day & more] (sort days)
               done         []]
          (if-not day
            {:days done}
            (let [outcome (try
                            (sync-day! ctx conn user metric-catalog day)
                            (catch Exception e
                              (log/error e "Cronometer sync failed for" (str day))
                              {:day (str day), :error (ex-message e)}))]
              (if (:error outcome)
                {:days done, :error (:error outcome), :failed-day (:day outcome)}
                (do (when (and (seq more) (pos? *day-pause-ms*))
                      (Thread/sleep (long *day-pause-ms*)))
                    (recur more (conj done outcome)))))))))))

(defn sync-recent!
  "Sync the last `n` days (default 7) in the user's time zone."
  ([ctx user] (sync-recent! ctx user default-days))
  ([ctx user n]
   (sync-days! ctx user (recent-days (user-zone user) n))))

(defonce ^:private running
  ;; user ids with a sync in progress in this process
  (atom #{}))

(defn sync-recent-locked!
  "`sync-recent!`, unless a sync for the same user is already running in this
   process; then `::already-running`. Two overlapping syncs would race to
   create the same documents. The guard is per process, so with more than one
   backend instance it does not hold (roadmap/077)."
  [ctx user]
  (let [user-id (:xt/id user)
        [before _] (swap-vals! running conj user-id)]
    (if (contains? before user-id)
      ::already-running
      (try
        (sync-recent! ctx user)
        (finally
          (swap! running disj user-id))))))
