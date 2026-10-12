(ns tech.jgood.gleanmo.worker
  (:require [clojure.tools.logging :as log]
            [com.biffweb :as biff]
            [tech.jgood.gleanmo.cronometer.connection :as cronometer-connection]
            [tech.jgood.gleanmo.cronometer.sync :as cronometer-sync]
            [tech.jgood.gleanmo.db.mutations :as mutations]
            [tech.jgood.gleanmo.db.queries :as queries]
            [tech.jgood.gleanmo.observability :as obs]
            [tech.jgood.gleanmo.schema.utils :as schema-utils]
            [xtdb.api :as xt])
  (:import
   [java.time ZonedDateTime ZoneOffset]
   [java.util Date]))

(defn every-n-minutes [n]
  (iterate #(biff/add-seconds % (* 60 n)) (java.util.Date.)))

(defn daily-at-utc
  "Chime schedule firing once a day at `hour` UTC, starting from the next
   occurrence.

   `(every-n-minutes (* 60 24))` would do the arithmetic but restart its clock
   from `now` on every boot, so on a day with a few deploys a once-a-day task
   can quietly never run. Anchoring to a wall-clock hour survives restarts."
  [hour]
  (let [now   (ZonedDateTime/now ZoneOffset/UTC)
        today (-> now
                  (.withHour hour)
                  (.withMinute 0)
                  (.withSecond 0)
                  (.withNano 0))
        start (if (.isAfter today now) today (.plusDays today 1))]
    (iterate #(biff/add-seconds % (* 60 60 24))
             (Date/from (.toInstant start)))))

(defn print-usage [{:keys [biff/db]}]
  ;; For a real app, you can have this run once per day and send you the output
  ;; in an email.
  (let [n-users (queries/count-users db)]
    (log/info "There are" n-users "users.")))

(defn reconcile-timer-flags
  "Re-derive every timer's `running` flag from its interval and repair any
   disagreement, once a day.

   The read path self-heals the flagged-but-ended direction by filtering, but
   it cannot see the opposite — an open interval carrying no flag is invisible
   to a query that filters on the flag. That is what this sweep is for. It runs
   the full set difference the flag replaced, which is the right tool once a
   day and the wrong one several times per page load.

   A quiet log is the signal that the write-time derivation is holding. If this
   starts printing, some path is writing timer documents without going through
   `db/mutations.clj`.

   Profiled: this deliberately runs the expensive set difference the flag
   replaced, so it is the one place that cost still lives. `wrap-request-profiling`
   only covers HTTP handlers, so without an explicit block the sweep would be
   the single unmeasured thing in the system — and the one most likely to grow
   with history. Timings land in the same accumulator as request profiles and
   surface on /app/monitoring/performance."
  [{:keys [biff.xtdb/node] :as ctx}]
  (obs/profile-block
   ::reconcile-timer-flags
   (doseq [[entity-key {:keys [beginning-key end-key running-key]}]
           schema-utils/running-flag-entities]
     ;; A snapshot per type rather than one for the whole sweep: the repairs
     ;; below write, and a snapshot taken before them does not advance.
     (let [{:keys [missing phantom]} (queries/running-flag-audit
                                      (xt/db node)
                                      beginning-key end-key running-key)]
       ;; `:db/dissoc`, never `false`. The flag is sparse by design — XTDB
       ;; indexes presence, so a stopped timer must carry no attribute at all.
       ;; Writing `false` here would keep every test green (the read filters on
       ;; `true`) while growing the index back toward the full scan this whole
       ;; change exists to remove. See :exercise-session/running in the schema.
       (doseq [[ids value direction] [[missing true :missing]
                                      [phantom :db/dissoc :phantom]]
               id                    ids]
         (log/warn "Reconciling" running-key direction "on" entity-key id)
         (mutations/update-entity! ctx {:entity-key entity-key,
                                        :entity-id  id,
                                        :data       {running-key value}}))))))

(def cronometer-user-pause-ms
  "Pause between users in the scheduled Cronometer sync."
  2000)

(defn sync-cronometer
  "Re-pull the last 7 Cronometer days for every user with a stored
   connection, one user at a time with a pause between them. One user's
   failure is logged and does not stop the others. A no-op without
   `:gleanmo/credentials-key`.

   Days are idempotent, so a double fire during a deploy overlap only repeats
   work (roadmap/077-scheduled-work-multi-instance.md)."
  [{:keys [biff.xtdb/node], :as ctx}]
  (when (cronometer-connection/configured? ctx)
    (obs/profile-block
     ::sync-cronometer
     (doseq [[i user] (map-indexed vector
                                   (queries/users-with-cronometer-connection
                                    (xt/db node)))]
       (when (pos? i) (Thread/sleep (long cronometer-user-pause-ms)))
       (try
         (let [outcome (cronometer-sync/sync-recent-locked! ctx user)]
           (cond
             (= ::cronometer-sync/already-running outcome)
             (log/info "Cronometer sync already running for user" (:xt/id user))

             (:error outcome)
             (log/warn "Cronometer sync stopped for user" (:xt/id user)
                       "on" (:failed-day outcome) ":" (:error outcome))

             :else
             (log/info "Cronometer sync done for user" (:xt/id user)
                       (count (:days outcome)) "days")))
         (catch Exception e
           (log/error e "Cronometer sync failed for user" (:xt/id user))))))))

(defn alert-new-user [{:keys [biff.xtdb/node]} tx]
  (doseq [_ [nil]
          :let [db-before (xt/db node {::xt/tx-id (dec (::xt/tx-id tx))})]
          [op & args] (::xt/tx-ops tx)
          :when (= op ::xt/put)
          :let [[doc] args]
          :when (and (contains? doc :user/email)
                     (nil? (queries/get-entity-by-id db-before (:xt/id doc))))]
    ;; You could send this as an email instead of printing.
    (log/info "WOAH there's a new user")))

(defn echo-consumer [{:keys [biff/job]}]
  (prn :echo job)
  (when-some [callback (:biff/callback job)]
    (callback job)))

(def module
  {:tasks [{:task #'print-usage
            :schedule #(every-n-minutes 5)}
           {:task #'reconcile-timer-flags
            :schedule #(daily-at-utc 9)}
           {:task #'sync-cronometer
            :schedule #(daily-at-utc 10)}]
   :on-tx alert-new-user
   :queues [{:id :echo
             :consumer #'echo-consumer}]})
