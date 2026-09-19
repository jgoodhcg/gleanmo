(ns tasks.task-entities
  "Bulk review of task entities from the command line, for cleanups too big to
   click through one at a time and too specific to deserve UI.

   Two steps, with the decisions in between kept as data:

   1. `export-tasks` writes the user's tasks to an EDN file.
   2. A plan file lists the change for each task:

        [{:id #uuid \"…\" :label \"Renew passport\" :op :update
          :set {:task/state :done}}
         {:id #uuid \"…\" :label \"Old idea\" :op :delete}
         {:op :create :type :project :ref :errands
          :set {:project/label \"errands\"}}
         {:op :create :set {:task/label \"Buy stamps\" :task/state :later
                            :task/project-id :errands}}]

      `:label` is optional and checked against the stored task, so a pasted id
      that points at the wrong task fails instead of editing it. `:set` merges
      into the task; `:db/dissoc` removes a field. `:delete` soft-deletes.
      `:create` makes a task (or, with `:type :project`, a project) from `:set`;
      a project's `:ref` keyword stands in for its id in any `:task/project-id`
      of the plan. Creating a project whose label already exists is an error.
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
   [clojure.string :as str]
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

(def ^:private creatable-types
  #{:task :project})

;; Relation fields whose value may name a project created earlier in the same
;; plan by its `:ref` keyword instead of a uuid.
(def ^:private ref-keys
  #{:task/project-id})

(defn- merged
  [stored data]
  (reduce-kv (fn [doc k v]
               (if (= :db/dissoc v) (dissoc doc k) (assoc doc k v)))
             stored
             data))

(defn- entry-type
  [{:keys [op type]}]
  (if (= :create op) (or type :task) :task))

(defn- op-errors
  "Problems with one plan entry that are visible without the database."
  [{:keys [id op ref], changes :set, :as entry}]
  (let [etype       (entry-type entry)
        foreign     (when (map? changes)
                      (seq (remove #(= (name etype) (namespace %))
                                   (keys changes))))
        system-set  (when (map? changes)
                      (seq (set/intersection system-keys (set (keys changes)))))]
    (cond-> []
      (not (#{:update :delete :create} op))
      (conj ":op must be :update, :delete or :create")
      (and (#{:update :delete} op) (not (uuid? id)))
      (conj ":id must be a uuid")
      (and (= :create op) (contains? entry :id))
      (conj ":create takes no :id; one is generated")
      (and (= :create op) (not (creatable-types etype)))
      (conj (str ":type must be one of " (pr-str creatable-types)))
      (and (= :create op) (some? ref) (not (keyword? ref)))
      (conj ":ref must be a keyword")
      (and (#{:update :create} op) (not (and (map? changes) (seq changes))))
      (conj (str (pr-str op) " needs a non-empty :set map"))
      (and (= :delete op) (contains? entry :set))
      (conj ":delete takes no :set")
      foreign
      (conj (str ":set may only hold :" (name etype) "/* keys, got "
                 (pr-str foreign)))
      system-set
      (conj (str ":set may not hold system-managed keys " (pr-str system-set))))))

(defn plan-errors
  "Problems with the plan as a whole that are visible without the database:
   malformed entries, duplicate ids or refs, and refs that name no project
   created in the plan. Returns a seq of strings; empty when the plan is
   well-formed."
  [plan]
  (let [entry-errors (keep-indexed
                      (fn [i entry]
                        (when-let [es (seq (op-errors entry))]
                          (str "Entry " i " " (pr-str entry) ": "
                               (str/join "; " es))))
                      plan)
        dupes        (fn [xs] (keep (fn [[x n]] (when (< 1 n) x))
                                    (frequencies (remove nil? xs))))
        project-refs (set (keep #(when (and (= :create (:op %))
                                            (= :project (entry-type %)))
                                   (:ref %))
                                plan))
        dangling     (for [entry plan
                           [k v] (:set entry)
                           :when (and (ref-keys k) (keyword? v)
                                      (not= :db/dissoc v)
                                      (not (project-refs v)))]
                       (str "Unknown project ref " v " in " (pr-str entry)))]
    (concat entry-errors
            (map #(str "Duplicate id in plan: " %) (dupes (map :id plan)))
            (map #(str "Duplicate ref in plan: " %) (dupes (map :ref plan)))
            dangling)))

(defn- resolve-refs
  [changes refs]
  (reduce (fn [m k]
            (let [v (get m k)]
              (if (and (keyword? v) (not= :db/dissoc v))
                (assoc m k (get refs v))
                m)))
          changes
          (filter #(contains? changes %) ref-keys)))

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

(defn- normalize-label
  [s]
  (str/lower-case (str/trim (or s ""))))

(defn- project-errors
  "A task pointing at a project that neither exists nor is created in the
   plan would validate as a uuid and silently orphan."
  [doc known-project-ids]
  (let [pid (:task/project-id doc)]
    (when (and pid (not (known-project-ids pid)))
      [(str "unknown project id " pid)])))

(defn- resolve-create
  [{:keys [user-id now refs known-project-ids existing-labels]}
   {:keys [ref], changes :set, :as entry}]
  (let [etype  (entry-type entry)
        id     (if ref (get refs ref) (random-uuid))
        data   (cond-> (assoc (resolve-refs changes refs)
                              :xt/id id
                              :user/id user-id)
                 (= :done (:task/state changes)) (assoc :task/done-at now))
        doc    (mutations/entity-doc etype data)
        schema (some-> (m/explain etype doc main/malli-opts) me/humanize)
        errors (cond-> (vec (project-errors doc known-project-ids))
                 (and (= :project etype)
                      (existing-labels (normalize-label (:project/label doc))))
                 (conj (str "a project labelled "
                            (pr-str (:project/label doc)) " already exists"))
                 schema (conj (str "schema: " (pr-str schema))))]
    {:entry entry, :entity-key etype, :id id, :data data, :errors errors}))

(defn- resolve-existing
  [{:keys [db user-id now refs known-project-ids]}
   {:keys [id op label], changes :set, :as entry}]
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
                   :update (update-data stored (resolve-refs changes refs) now)
                   :delete {::sm/deleted-at now}))
        doc    (when (and data (= :update op)) (merged stored data))
        schema (when doc
                 (some-> (m/explain :task doc main/malli-opts) me/humanize))]
    {:entry      entry
     :entity-key :task
     :id         id
     :stored     stored
     :data       data
     :errors     (cond-> errors
                   doc    (into (project-errors doc known-project-ids))
                   schema (conj (str "schema: " (pr-str schema))))}))

(defn resolve-plan
  "Check every entry of a well-formed plan against the database. Returns one
   `{:entry :entity-key :id :stored :data :errors}` per entry, in plan order;
   creates get fresh ids, and refs to projects created in the plan resolve to
   theirs."
  [db user-id now plan]
  (let [projects (queries/projects-for-user
                  db user-id
                  :user-settings {:show-sensitive true, :show-archived true})
        refs     (into {}
                       (keep #(when (and (= :create (:op %)) (:ref %))
                                [(:ref %) (random-uuid)]))
                       plan)
        env      {:db                db
                  :user-id           user-id
                  :now               now
                  :refs              refs
                  :known-project-ids (into (set (map :xt/id projects))
                                           (keep #(when (and (= :create (:op %))
                                                             (= :project (entry-type %)))
                                                    (get refs (:ref %))))
                                           plan)
                  :existing-labels   (set (map (comp normalize-label :project/label)
                                               projects))}]
    (mapv #(if (= :create (:op %))
             (resolve-create env %)
             (resolve-existing env %))
          plan)))

(defn commit-plan!
  "Write a resolved plan with no errors in one transaction."
  [ctx resolved]
  (let [by-op (group-by (comp :op :entry) resolved)]
    (mutations/write-entities!
     ctx
     {:creates (mapv (fn [{:keys [entity-key data]}]
                       {:entity-key entity-key, :data data})
                     (:create by-op))
      :updates (mapv (fn [{:keys [entity-key id data]}]
                       {:entity-key entity-key, :entity-id id, :data data})
                     (concat (:update by-op) (:delete by-op)))})))

(defn- print-entry
  [{:keys [entry entity-key id stored data errors]}]
  (let [op    (:op entry)
        title (or (:task/label stored) (:task/label data) (:project/label data)
                  (:label entry) "?")
        head  (str (name op)
                   (when (= :create op) (str " " (name entity-key)))
                   "  " id "  " (pr-str title))]
    (if (seq errors)
      (do (u/print-red (str "✗ " head))
          (doseq [e errors] (u/print-red (str "    " e))))
      (do (println (str "✓ " head))
          (when (#{:update :create} op)
            (doseq [[k v] (sort-by key (dissoc data :xt/id :user/id))]
              (println (str "    " k "  "
                            (if (= :create op) "" (str (pr-str (get stored k)) " → "))
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
    (when-let [errors (seq (plan-errors plan))]
      (doseq [e errors] (u/print-red e))
      (System/exit 1))
    (with-user-ctx
      target email
      (fn [{:keys [biff/db], :as ctx} user-id]
        (let [resolved (resolve-plan db user-id (java.time.Instant/now) plan)
              failed   (filter (comp seq :errors) resolved)
              counts   (frequencies (map (comp :op :entry) resolved))]
          (run! print-entry resolved)
          (println)
          (println (str (count plan) " change(s): "
                        (get counts :create 0) " create, "
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
              (commit-plan! ctx resolved)
              (u/print-green (str "Committed " (count resolved)
                                  " change(s) in one transaction.")))))))))
