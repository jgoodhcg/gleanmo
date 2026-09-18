(ns tasks.task-entities
  "Bulk review of task entities from the command line, for cleanups too big to
   click through one at a time and too specific to deserve UI.

   Two steps, with the decisions in between kept as data:

   1. `export-tasks` writes the user's tasks to an EDN file.
   2. A plan file lists the change for each task:

        [{:id #uuid \"…\" :label \"Renew passport\" :op :update
          :set {:task/state :done}}
         {:id #uuid \"…\" :label \"Old idea\" :op :delete}]

      `:label` is optional and checked against the stored task, so a pasted id
      that points at the wrong task fails instead of editing it. `:set` merges
      into the task; `:db/dissoc` removes a field. `:delete` soft-deletes.
   3. `apply-task-changes` validates the whole plan and prints every change.
      Nothing is written without `--commit`, and with it the plan lands in one
      transaction or not at all.

   Plans hold ids and personal data: keep them in `tmp/` (gitignored).

   Locally the dev server must be stopped first — RocksDB takes an exclusive
   lock. `--target prod` reads `XTDB_JDBC_URL` from the environment.

   Usage:
     clj -M:dev export-tasks --target dev --email you@example.com
     clj -M:dev apply-task-changes --target dev --email you@example.com \\
       --file tmp/task-plan.edn [--commit]"
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.pprint :as pprint]
   [clojure.set :as set]
   [clojure.tools.cli :refer [parse-opts]]
   [clojure.walk :as walk]
   [malli.core :as m]
   [malli.error :as me]
   [tasks.migrate :as migrate]
   [tasks.util :as u]
   [tech.jgood.gleanmo :as main]
   [tech.jgood.gleanmo.db.mutations :as mutations]
   [tech.jgood.gleanmo.db.queries :as queries]
   [tech.jgood.gleanmo.schema.meta :as sm]))

;; =============================================================================
;; CLI
;; =============================================================================

(def ^:private export-options
  [["-t" "--target TARGET" "Target database: dev or prod" :default "dev"]
   ["-e" "--email EMAIL" "Email of the user whose tasks to export"]
   ["-o" "--out FILE" "Output path (default tmp/task-export-<target>.edn)"]
   [nil "--all-states" "Include :done and :canceled tasks"]
   [nil "--include-sensitive" "Include tasks marked sensitive"]
   ["-h" "--help"]])

(def ^:private apply-options
  [["-t" "--target TARGET" "Target database: dev or prod" :default "dev"]
   ["-e" "--email EMAIL" "Email of the user who owns the tasks"]
   ["-f" "--file FILE" "Path to the EDN plan file"]
   [nil "--commit" "Write the changes (default is a dry run)"]
   ["-h" "--help"]])

(defn- parse-or-exit
  [usage args options required]
  (let [{:keys [options errors summary]} (parse-opts args options)
        missing (remove #(get options %) required)]
    (when (:help options)
      (println usage)
      (println summary)
      (System/exit 0))
    (when (or (seq errors) (seq missing))
      (doseq [e errors] (u/print-red (str "ERROR: " e)))
      (doseq [k missing] (u/print-red (str "ERROR: --" (name k) " is required")))
      (println usage)
      (println summary)
      (System/exit 1))
    options))

(defn- with-user-ctx
  "Start a node for `target`, resolve `email` to a user id, and call
   `(f ctx user-id)`. Closes the node afterwards, then exits non-zero if `f`
   returned `:failed` or the user does not exist."
  [target email f]
  (u/print-cyan (str "Starting XTDB node for target: " target))
  (let [node   (migrate/start-node target)
        result (try
                 (let [ctx     (migrate/build-ctx node)
                       user-id (queries/user-id-by-email (:biff/db ctx) email)]
                   (if user-id
                     (f ctx user-id)
                     (do (u/print-red (str "No user found for email: " email))
                         :failed)))
                 (finally
                   (.close node)
                   (u/print-cyan "Node closed.")))]
    (when (= :failed result)
      (System/exit 1))))

;; =============================================================================
;; EDN round-tripping
;; =============================================================================

;; Tagged literals keep dates readable in the export and give the plan the same
;; notation back, independent of whatever print-methods happen to be loaded.
(defn- ->printable
  [x]
  (walk/postwalk
   (fn [v]
     (cond
       (instance? java.time.LocalDate v) (tagged-literal 'time/date (str v))
       (instance? java.time.Instant v)   (tagged-literal 'time/instant (str v))
       :else                             v))
   x))

(def ^:private edn-readers
  {'time/date    #(java.time.LocalDate/parse %)
   'time/instant #(java.time.Instant/parse %)})

;; =============================================================================
;; Export
;; =============================================================================

(def ^:private state-order
  [:inbox :now :later :waiting :done :canceled])

(def ^:private dropped-keys
  #{:user/id ::sm/type})

(defn- export-row
  [project-labels task]
  (cond-> (apply dissoc task dropped-keys)
    (:task/project-id task)
    (assoc :project (get project-labels (:task/project-id task)))))

(defn- export-data
  [db user-id {:keys [all-states include-sensitive]}]
  (let [settings       {:show-sensitive true, :show-archived true}
        tasks          (queries/tasks-for-user db user-id :user-settings settings)
        projects       (queries/projects-for-user db user-id
                                                  :user-settings settings)
        project-labels (into {} (map (juxt :xt/id :project/label)) projects)
        state-rank     (zipmap state-order (range))
        kept           (cond->> tasks
                         (not all-states)
                         (remove (comp #{:done :canceled} :task/state))
                         (not include-sensitive)
                         (remove :task/sensitive))]
    {:summary  {:exported-at (java.time.Instant/now)
                :total       (count tasks)
                :exported    (count kept)
                :by-state    (frequencies (map :task/state kept))}
     :projects (mapv #(select-keys % [:xt/id :project/label]) projects)
     :tasks    (->> kept
                    (sort-by (juxt (comp state-rank :task/state)
                                   ::sm/created-at))
                    (mapv #(export-row project-labels %)))}))

(defn export-tasks
  "Write a user's open tasks to an EDN file for bulk review.

   Options: --target dev|prod, --email (required), --out FILE,
   --all-states (include done/canceled), --include-sensitive."
  [& args]
  (let [{:keys [target email out], :as options}
        (parse-or-exit "Usage: clj -M:dev export-tasks [options]"
                       args export-options [:email])
        out (or out (str "tmp/task-export-" target ".edn"))]
    (with-user-ctx
      target email
      (fn [{:keys [biff/db]} user-id]
        (let [data (export-data db user-id options)]
          (io/make-parents out)
          (spit out (with-out-str (pprint/pprint (->printable data))))
          (u/print-green (str "Wrote " (get-in data [:summary :exported])
                              " of " (get-in data [:summary :total])
                              " task(s) to " out))
          (println "By state:" (pr-str (get-in data [:summary :by-state]))))))))

;; =============================================================================
;; Apply
;; =============================================================================

;; Signal fields the app maintains itself. The plan may not set them; a state
;; change derives them the way `app.task/set-state!` does.
(def ^:private system-keys
  #{:task/done-at :task/snooze-count :task/state-change-count
    :task/last-state-change-at})

(defn- merged
  [stored data]
  (reduce-kv (fn [doc k v]
               (if (= :db/dissoc v) (dissoc doc k) (assoc doc k v)))
             stored
             data))

(defn- op-errors
  "Problems with one plan entry that are visible without the database."
  [{:keys [id op], changes :set, :as entry}]
  (cond-> []
    (not (uuid? id))
    (conj ":id must be a uuid")
    (not (#{:update :delete} op))
    (conj ":op must be :update or :delete")
    (and (= :update op) (not (and (map? changes) (seq changes))))
    (conj ":update needs a non-empty :set map")
    (and (= :delete op) (contains? entry :set))
    (conj ":delete takes no :set")
    (and (map? changes) (seq (remove #(= "task" (namespace %)) (keys changes))))
    (conj (str ":set may only hold :task/* keys, got "
               (pr-str (remove #(= "task" (namespace %)) (keys changes)))))
    (and (map? changes) (seq (set/intersection system-keys (set (keys changes)))))
    (conj (str ":set may not hold system-managed keys "
               (pr-str (set/intersection system-keys (set (keys changes))))))))

(defn- update-data
  [stored changes now]
  (let [new-state (:task/state changes)]
    (cond-> changes
      (and new-state (not= new-state (:task/state stored)))
      (assoc :task/last-state-change-at now
             :task/state-change-count (inc (or (:task/state-change-count stored)
                                               0)))
      (and (= :done new-state) (not= :done (:task/state stored)))
      (assoc :task/done-at now))))

(defn- resolve-entry
  "Check one plan entry against the stored task. Returns
   {:entry :stored :data :errors}."
  [db user-id now {:keys [id op label], changes :set, :as entry}]
  (let [stored (queries/get-entity-by-id db id)
        errors (cond
                 (nil? stored)                    ["no such entity"]
                 (not= :task (::sm/type stored))  ["not a task"]
                 (not= user-id (:user/id stored)) ["belongs to another user"]
                 (::sm/deleted-at stored)         ["already deleted"]
                 (and label (not= label (:task/label stored)))
                 [(str "label mismatch: stored " (pr-str (:task/label stored)))]
                 :else                            [])
        data   (when (empty? errors)
                 (case op
                   :update (update-data stored changes now)
                   :delete {::sm/deleted-at now}))
        schema-errors (when (and data (= :update op))
                        (some-> (m/explain :task (merged stored data)
                                           main/malli-opts)
                                me/humanize))]
    {:entry  entry
     :stored stored
     :data   data
     :errors (cond-> errors
               schema-errors (conj (str "schema: " (pr-str schema-errors))))}))

(defn- print-entry
  [{:keys [entry stored data errors]}]
  (let [title (or (:task/label stored) (:label entry) "?")
        head  (str (name (:op entry)) "  " (:id entry) "  " (pr-str title))]
    (if (seq errors)
      (do (u/print-red (str "✗ " head))
          (doseq [e errors] (u/print-red (str "    " e))))
      (do (println (str "✓ " head))
          (when (= :update (:op entry))
            (doseq [[k v] (sort-by key data)]
              (println (str "    " k "  "
                            (pr-str (get stored k)) " → "
                            (if (= :db/dissoc v) "(removed)" (pr-str v))))))))))

(defn apply-task-changes!
  "Validate and apply a plan file of task changes. Dry run unless --commit.

   Options: --target dev|prod, --email (required), --file (required), --commit."
  [& args]
  (let [{:keys [target email file commit]}
        (parse-or-exit "Usage: clj -M:dev apply-task-changes [options]"
                       args apply-options [:email :file])
        plan (edn/read-string {:readers edn-readers} (slurp file))]
    (when-not (sequential? plan)
      (u/print-red "Plan must be a vector of change maps.")
      (System/exit 1))
    (let [shape-errors (keep-indexed (fn [i entry]
                                       (when-let [es (seq (op-errors entry))]
                                         [i entry es]))
                                     plan)
          dupes        (->> (map :id plan) frequencies
                            (keep (fn [[id n]] (when (< 1 n) id))))]
      (when (or (seq shape-errors) (seq dupes))
        (doseq [[i entry es] shape-errors]
          (u/print-red (str "Entry " i ": " (pr-str entry)))
          (doseq [e es] (u/print-red (str "    " e))))
        (doseq [id dupes] (u/print-red (str "Duplicate id in plan: " id)))
        (System/exit 1)))
    (with-user-ctx
      target email
      (fn [{:keys [biff/db], :as ctx} user-id]
        (let [now      (java.time.Instant/now)
              resolved (mapv #(resolve-entry db user-id now %) plan)
              failed   (filter (comp seq :errors) resolved)
              counts   (frequencies (map (comp :op :entry) resolved))]
          (run! print-entry resolved)
          (println)
          (println (str (count plan) " change(s): "
                        (get counts :update 0) " update, "
                        (get counts :delete 0) " delete"))
          (cond
            (seq failed)
            (do (u/print-red (str (count failed)
                                  " entry(ies) failed — nothing written."))
                :failed)

            (not commit)
            (u/print-yellow "Dry run — re-run with --commit to write.")

            :else
            (do
              (mutations/update-entities!
               ctx
               (mapv (fn [{:keys [entry data]}]
                       {:entity-key :task
                        :entity-id  (:id entry)
                        :data       data})
                     resolved))
              (u/print-green (str "Committed " (count resolved)
                                  " change(s) in one transaction.")))))))))
