(ns tech.jgood.gleanmo.db.queries
  (:require
   [com.biffweb :as    biff
    :refer [q]]
   [tech.jgood.gleanmo.schema :as schema-registry]
   [tech.jgood.gleanmo.schema.utils :as schema-utils]
   [tech.jgood.gleanmo.schema.meta :as sm]
   [tick.core :as t]
   [xtdb.api :as xt]
   [taoensso.tufte :refer [defnp]]))

(defnp get-entity-by-id
  "Get a single entity by ID.
   Returns the first result or nil if not found."
  [db entity-id]
  (let [result (q db
                  {:find  '(pull ?e [*]),
                   :where [['?e :xt/id entity-id]],
                   :in    '[entity-id]}
                  entity-id)]
    (when (seq result)
      (-> result
          first))))

(defn- ->instant
  "Coerce a variety of date/time values into java.time.Instant, if possible."
  [v]
  (cond
    (nil? v) nil
    (instance? java.time.Instant v) v
    (instance? java.time.LocalDateTime v) (.toInstant ^java.time.LocalDateTime v java.time.ZoneOffset/UTC)
    (instance? java.time.LocalDate v) (.toInstant (.atStartOfDay ^java.time.LocalDate v java.time.ZoneOffset/UTC))
    :else (try
            (t/instant v)
            (catch Exception _
              nil))))

(defn get-user-settings
  "Get all user settings in a single query. Returns a map with email and boolean settings."
  [db user-id]
  (when user-id
    (if-let [user (get-entity-by-id db user-id)]
      (let [show-bm-logs (cond
                           (contains? user :user/show-bm-logs)
                           (boolean (:user/show-bm-logs user))
                           (contains? user :user/hide-bm-logs)
                           (not (boolean (:user/hide-bm-logs user)))
                           :else
                           true)]
        {:email          (:user/email user),
         :show-sensitive (boolean (:user/show-sensitive user)),
         :show-archived  (boolean (:user/show-archived user)),
         :show-bm-logs   show-bm-logs})
      {:email          nil,
       :show-sensitive false,
       :show-archived  false,
       :show-bm-logs   true})))

(defn resolve-user-settings
  "Read user settings from request context if available, otherwise query DB.
   Prefer ctx-based lookup (O(0) DB calls) over get-user-settings (1 DB call)."
  ([ctx]
   (resolve-user-settings ctx (-> ctx :session :uid)))
  ([ctx user-id]
   (or (:user/settings ctx)
       (get-user-settings (:biff/db ctx) user-id))))

(defn- schema-has-field?
  "Check whether a schema (by entity keyword) contains a given field key."
  [entity-kw field-suffix]
  (let [field-key     (keyword (name entity-kw) field-suffix)
        entity-schema (get schema-registry/schema entity-kw)]
    (when entity-schema
      (some (fn [[k & _]] (= k field-key))
            (schema-utils/extract-schema-fields entity-schema)))))

(defn direct-sensitivity-clauses
  "Generate Datalog :where clauses for direct sensitivity/archived fields only.
   Does NOT include relationship joins — use for lightweight count/filter queries."
  [entity-type user-settings]
  (let [entity-type-str (name entity-type)
        {:keys [show-sensitive show-archived]} user-settings]
    (cond-> []
      (and (not show-sensitive)
           (schema-has-field? entity-type "sensitive"))
      (conj (list 'not ['?e (keyword entity-type-str "sensitive") true]))

      (and (not show-archived)
           (schema-has-field? entity-type "archived"))
      (conj (list 'not ['?e (keyword entity-type-str "archived") true])))))

(defn ^:deprecated relationship-sensitivity-clauses
  "DEPRECATED: Causes O(N×M) nested-loop evaluation in XTDB 1.x.
   Use two-phase exclusion via build-exclusion-map + apply-relationship-exclusions instead.
   Kept for rollback safety."
  [entity-type user-settings]
  (let [{:keys [show-sensitive show-archived]} user-settings
        entity-schema (get schema-registry/schema entity-type)
        rel-fields    (when entity-schema
                        (schema-utils/extract-relationship-fields
                         entity-schema :remove-system-fields true))]
    (when rel-fields
      (->> rel-fields
           (mapcat
            (fn [{:keys [field-key related-entity-str]}]
              (let [rel-kw   (keyword related-entity-str)
                    rel-var  (symbol (str "?rel-" related-entity-str))
                    sens-key (keyword related-entity-str "sensitive")
                    arch-key (keyword related-entity-str "archived")]
                (cond-> []
                  (and (not show-sensitive)
                       (schema-has-field? rel-kw "sensitive"))
                  (conj (concat ['not-join ['?e]]
                                [['?e field-key rel-var]
                                 [rel-var sens-key true]]))

                  (and (not show-archived)
                       (schema-has-field? rel-kw "archived"))
                  (conj (concat ['not-join ['?e]]
                                [['?e field-key rel-var]
                                 [rel-var arch-key true]]))))))
           vec))))

(defn sensitivity-clauses
  "Generate all sensitivity/archived where clauses — both direct and relationship.
   Suitable for pushing into XTDB queries that need full filtering."
  [entity-type user-settings]
  (into (direct-sensitivity-clauses entity-type user-settings)
        (relationship-sensitivity-clauses entity-type user-settings)))

(defn- excluded-parent-ids
  "Phase 1: Query for sensitive/archived parent IDs of a given entity type.
   Returns a set of UUIDs to exclude."
  [db user-id parent-entity-type {:keys [check-sensitive check-archived]}]
  (let [or-clauses (cond-> []
                     check-sensitive
                     (conj [(keyword (name parent-entity-type) "sensitive") true])
                     check-archived
                     (conj [(keyword (name parent-entity-type) "archived") true]))
        ;; Build where clauses with (or ...) for both flags
        base-where (cond-> ['[?p :user/id user-id]
                            ['?p ::sm/type parent-entity-type]
                            '(not [?p ::sm/deleted-at])]
                     (= (count or-clauses) 1)
                     (conj ['?p (ffirst or-clauses) (second (first or-clauses))])
                     (> (count or-clauses) 1)
                     (conj (concat ['or]
                                   (map (fn [[k v]] ['?p k v]) or-clauses))))]
    (when (seq or-clauses)
      (->> (q db {:find  '[?p]
                  :where base-where
                  :in    ['user-id]}
              user-id)
           (map first)
           set))))

(defn- build-exclusion-map
  "Phase 1: Build a map of {field-key #{excluded-parent-ids}} for relationship filtering.
   Mirrors the field iteration in relationship-sensitivity-clauses but returns data
   instead of Datalog clauses."
  [db user-id entity-type user-settings]
  (let [{:keys [show-sensitive show-archived]} user-settings
        entity-schema (get schema-registry/schema entity-type)
        rel-fields    (when entity-schema
                        (schema-utils/extract-relationship-fields
                         entity-schema :remove-system-fields true))]
    (when rel-fields
      (let [result (->> rel-fields
                        (keep
                         (fn [{:keys [field-key related-entity-str]}]
                           (let [rel-kw          (keyword related-entity-str)
                                 check-sensitive (and (not show-sensitive)
                                                      (schema-has-field? rel-kw "sensitive"))
                                 check-archived  (and (not show-archived)
                                                      (schema-has-field? rel-kw "archived"))
                                 excluded        (when (or check-sensitive check-archived)
                                                   (excluded-parent-ids
                                                    db user-id rel-kw
                                                    {:check-sensitive check-sensitive
                                                     :check-archived  check-archived}))]
                             (when (seq excluded)
                               [field-key excluded]))))
                        (into {}))]
        (when (seq result) result)))))

(defn- apply-relationship-exclusions
  "Phase 2: Post-filter entities whose related parent IDs intersect with exclusion sets.
   Handles both set-valued (many-relationship) and single-valued (single-relationship) fields.
   Short-circuits when exclusion-map is nil or all sets are empty."
  [exclusion-map entities]
  (if-not (seq exclusion-map)
    entities
    (remove
     (fn [entity]
       (some (fn [[field-key excluded-ids]]
               (let [v (get entity field-key)]
                 (cond
                   (set? v)     (some excluded-ids v)
                   (some? v)    (contains? excluded-ids v)
                   :else        false)))
             exclusion-map))
     entities)))

(defnp get-entity-for-user
  "Get a single entity by ID that belongs to a specific user.
   Returns the first result or nil if not found."
  [db entity-id user-id entity-type]
  (let [result (q db
                  {:find  '(pull ?e [*]),
                   :where '[[?e :xt/id id]
                            [?e :user/id user-id]
                            [?e ::sm/type entity-type]],
                   :in    '[id user-id entity-type]}
                  entity-id
                  user-id
                  entity-type)]
    (when (seq result)
      (-> result
          first))))

(def ^:private default-order-direction :desc)

(defn- build-entity-query
  "Build a Datalog query for entities of a given type.
   Accepts extra-where clauses (e.g. sensitivity) to push predicates into the DB."
  [_user-id entity-type order-key order-direction limit offset & {:keys [extra-where]}]
  (let [order-direction (or order-direction default-order-direction)
        order-var       '?order-value
        base-where      ['[?e :user/id user-id]
                         ['?e ::sm/type entity-type]
                         '(not [?e ::sm/deleted-at])]
        where-clauses   (cond-> base-where
                          order-key   (conj (vec ['?e order-key order-var]))
                          extra-where (into extra-where))
        find-elements   (if order-key
                          '[(pull ?e [*]) ?order-value]
                          '[(pull ?e [*])])]
    (cond-> {:find  find-elements
             :where where-clauses
             :in    ['user-id]}
      order-key (assoc :order-by [[order-var order-direction]])
      limit     (assoc :limit limit)
      offset    (assoc :offset offset))))

(defn build-count-query
  "Build a lightweight count query — returns entity IDs only, no pull [*].
   Accepts extra-where clauses for sensitivity/state filtering."
  [entity-type & {:keys [extra-where]}]
  (let [base-where ['[?e :user/id user-id]
                    ['?e ::sm/type entity-type]
                    '(not [?e ::sm/deleted-at])]
        where-clauses (if extra-where
                        (into base-where extra-where)
                        base-where)]
    {:find  '[?e]
     :where where-clauses
     :in    ['user-id]}))

(defnp all-entities-for-user
  "Get all entities of a specific type that belong to a user.
   Uses two-phase filtering: direct clauses in XTDB, relationship exclusion
   via post-filter with O(1) set lookups (replaces slow not-join approach)."
  [db user-id entity-type &
   {:keys [filter-sensitive filter-archived filter-references
           limit offset order-key order-direction]}]
  (let [entity-type-str (name entity-type)
        time-stamp-key  (keyword entity-type-str "timestamp")
        user-settings   {:show-sensitive (boolean filter-sensitive)
                         :show-archived  (boolean filter-archived)}
        ;; Always use direct-only clauses for XTDB (fast (not ...) clauses)
        extra-where     (direct-sensitivity-clauses entity-type user-settings)
        ;; Phase 1: build exclusion map when filter-references requested
        exclusion-map   (when filter-references
                          (build-exclusion-map db user-id entity-type user-settings))
        ;; When post-filtering, don't limit in XTDB — apply after filtering
        effective-limit  (if (and limit (seq exclusion-map)) nil limit)
        effective-offset (if (and offset (seq exclusion-map)) nil offset)
        query-map       (build-entity-query
                         user-id entity-type order-key order-direction
                         effective-limit effective-offset :extra-where extra-where)
        raw-results     (q db query-map user-id)
        entities        (map first raw-results)]
    ;; Phase 2: post-filter, sort, then apply pagination in Clojure
    (cond->> entities
      (seq exclusion-map)  (apply-relationship-exclusions exclusion-map)
      (nil? order-key)     (sort-by #(or (time-stamp-key %) (::sm/created-at %)))
      (nil? order-key)     reverse
      (and (seq exclusion-map) offset) (drop offset)
      (and (seq exclusion-map) limit)  (take limit))))

(defn tasks-for-user
  "Get all tasks for a user, respecting the user's sensitive setting."
  [db user-id & {:keys [user-settings]}]
  (let [{:keys [show-sensitive show-archived]}
        (or user-settings (get-user-settings db user-id))]
    (all-entities-for-user
     db
     user-id
     :task
     :filter-sensitive show-sensitive
     :filter-archived  show-archived)))

(defn tasks-by-state
  "Get tasks for a user in a specific state, respecting sensitive settings."
  [db user-id state & {:keys [user-settings]}]
  (->> (tasks-for-user db user-id :user-settings user-settings)
       (filter #(= (:task/state %) state))))

(defn count-tasks-by-state
  "Count tasks in a specific state for a user, pushing state predicate into XTDB."
  [db user-id state & {:keys [user-settings]}]
  (let [settings     (or user-settings (get-user-settings db user-id))
        sens-clauses (direct-sensitivity-clauses :task settings)
        base-where   (into ['[?e :user/id user-id]
                            ['?e ::sm/type :task]
                            '(not [?e ::sm/deleted-at])
                            '[?e :task/state task-state]]
                           sens-clauses)
        query        {:find  '[?e]
                      :where base-where
                      :in    '[user-id task-state]}]
    (count (q db query user-id state))))

(defn projects-for-user
  "Get all projects for a user, respecting the user's sensitive setting."
  [db user-id & {:keys [user-settings]}]
  (let [{:keys [show-sensitive show-archived]}
        (or user-settings (get-user-settings db user-id))]
    (->> (all-entities-for-user
          db
          user-id
          :project
          :filter-sensitive show-sensitive
          :filter-archived  show-archived)
         (sort-by :project/label))))

(defnp all-for-user-query
  "Get all entities for a user with include/exclude options from user settings.
   This function is a higher-level wrapper around all-entities-for-user that handles
   user settings for including sensitive entities, archived entities, and related entity filtering."
  [{:keys [entity-type-str schema filter-references limit offset order-key order-direction]}
   {:keys [biff/db session] :as ctx}]
  (let [user-id             (:uid session)
        ;; Get user's preferences from settings, with secure defaults
        {:keys [show-sensitive show-archived]} (resolve-user-settings ctx user-id)
        sensitive           show-sensitive
        archived            show-archived
        entity-type         (keyword entity-type-str)

        ;; Get relationship fields from schema, removing system fields
        relationship-fields (when (and schema filter-references)
                              (schema-utils/extract-relationship-fields
                               schema
                               :remove-system-fields
                               true))]

    ;; Use the core function
    (all-entities-for-user
     db
     user-id
     entity-type
     :filter-sensitive    sensitive
     :filter-archived     archived
     :filter-references   filter-references
     :relationship-fields relationship-fields
     :limit               limit
     :offset              offset
     :order-key           order-key
     :order-direction     order-direction)))

(defnp dashboard-recent-entities
  "Fetch a bounded set of recent entities per type for the dashboard, respecting user settings and related-entity filters."
  [db user-id {:keys [entity-types per-type-limit order-keys user-settings], :or {per-type-limit 20}}]
  (let [{:keys [show-sensitive show-archived]}
        (or user-settings (get-user-settings db user-id))]
    (mapcat
     (fn [entity-str]
       (let [entity-kw    (keyword entity-str)
             order-key    (get order-keys entity-str ::sm/created-at)
             entity-schema (get schema-registry/schema entity-kw)
             rel-fields    (schema-utils/extract-relationship-fields
                            entity-schema
                            :remove-system-fields true)]
         (all-entities-for-user
          db
          user-id
          entity-kw
          :filter-sensitive    show-sensitive
          :filter-archived     show-archived
          :filter-references   true
          :relationship-fields rel-fields
          :limit               per-type-limit
          :order-key           order-key
          :order-direction     :desc)))
     entity-types)))

(defnp dashboard-upcoming-events
  "Fetch upcoming calendar events with visibility filtering and a small oversample to survive filtering."
  [db user-id {:keys [limit user-settings], :or {limit 5}}]
  (let [{:keys [show-sensitive show-archived]}
        (or user-settings (get-user-settings db user-id))
        entity-kw     :calendar-event
        entity-schema (get schema-registry/schema entity-kw)
        rel-fields    (schema-utils/extract-relationship-fields
                       entity-schema
                       :remove-system-fields true)
        batch-limit   (max 40 (* 4 limit))
        now           (t/now)
        raw-events    (all-entities-for-user
                       db
                       user-id
                       entity-kw
                       :filter-sensitive    show-sensitive
                       :filter-archived     show-archived
                       :filter-references   true
                       :relationship-fields rel-fields
                       :limit               batch-limit
                       :order-key           :calendar-event/beginning
                       :order-direction     :desc)]
    (->> raw-events
         (keep (fn [e]
                 (when-let [inst (->instant (or (:calendar-event/beginning e)
                                                (::sm/created-at e)))]
                   (when (t/> inst now)
                     (assoc e ::order inst)))))
         (sort-by ::order)
         (take limit)
         (map #(dissoc % ::order)))))

(defn get-user-authz
  "Get user authorization info (super-user status) for a user ID."
  [db user-id]
  (when user-id
    (when-let [user (get-entity-by-id db user-id)]
      {:super-user (boolean (:authz/super-user user))})))

#_{:clj-kondo/ignore [:shadowed-var]}
(defnp db-viz-query
  "Execute a database visualization query with type and filter parameters."
  [db query type filter-email]
  (q db query [type filter-email]))

(defn get-last-tx-time
  [{:keys [biff/db xt/id]}]
  (let [history (xt/entity-history db id :desc)]
    (-> history
        first
        :xtdb.api/tx-time)))

(defn tasks-for-today
  "Get tasks focused for today (focus-date <= today, not terminal).
   Pushes date and state predicates into XTDB, then sorts in Clojure."
  [db user-id today & {:keys [user-settings]}]
  (let [settings     (or user-settings (get-user-settings db user-id))
        sens-clauses (direct-sensitivity-clauses :task settings)
        base-where   (into ['[?e :user/id user-id]
                            ['?e ::sm/type :task]
                            '(not [?e ::sm/deleted-at])
                            '[?e :task/focus-date ?fd]
                            '[?e :task/state ?state]
                            '[(not= ?state :done)]
                            '[(not= ?state :canceled)]
                            '[(<= ?fd today)]]
                           sens-clauses)
        query        {:find  '[(pull ?e [*])]
                      :where base-where
                      :in    '[user-id today]}
        results      (q db query user-id today)]
    (->> (map first results)
         (sort-by (juxt (fn [t] (or (:task/focus-order t) Integer/MAX_VALUE))
                        :task/focus-date)))))

(defn next-focus-order-for-date
  "Return the next focus-order value for tasks with focus-date equal to date.
   Pushes focus-date equality into XTDB, only pulls focus-order."
  [db user-id date & {:keys [user-settings]}]
  (let [settings     (or user-settings (get-user-settings db user-id))
        sens-clauses (direct-sensitivity-clauses :task settings)
        base-where   (into ['[?e :user/id user-id]
                            ['?e ::sm/type :task]
                            '(not [?e ::sm/deleted-at])
                            '[?e :task/focus-date focus-date]
                            '[?e :task/focus-order ?fo]]
                           sens-clauses)
        query        {:find  '[?fo]
                      :where base-where
                      :in    '[user-id focus-date]}
        results      (q db query user-id date)]
    (inc (reduce max 0 (map first results)))))

(defn tasks-completed-today
  "Get tasks completed today — pushes state and focus-date into XTDB."
  [db user-id today & {:keys [user-settings]}]
  (let [settings     (or user-settings (get-user-settings db user-id))
        sens-clauses (direct-sensitivity-clauses :task settings)
        base-where   (into ['[?e :user/id user-id]
                            ['?e ::sm/type :task]
                            '(not [?e ::sm/deleted-at])
                            '[?e :task/state :done]
                            '[?e :task/focus-date today]]
                           sens-clauses)
        query        {:find  '[(pull ?e [*])]
                      :where base-where
                      :in    '[user-id today]}
        results      (q db query user-id today)]
    (map first results)))

(defn count-tasks-completed-all-time
  "Count all completed tasks for a user — pushes state into XTDB count query."
  [db user-id & {:keys [user-settings]}]
  (let [settings     (or user-settings (get-user-settings db user-id))
        sens-clauses (direct-sensitivity-clauses :task settings)
        base-where   (into ['[?e :user/id user-id]
                            ['?e ::sm/type :task]
                            '(not [?e ::sm/deleted-at])
                            '[?e :task/state :done]]
                           sens-clauses)
        query        {:find  '[?e]
                      :where base-where
                      :in    '[user-id]}]
    (count (q db query user-id))))

(defn count-tasks-completed-in-range
  "Count tasks completed within a date range — pushes done-at range into XTDB."
  [db user-id start-instant end-instant & {:keys [user-settings]}]
  (let [settings     (or user-settings (get-user-settings db user-id))
        sens-clauses (direct-sensitivity-clauses :task settings)
        base-where   (into ['[?e :user/id user-id]
                            ['?e ::sm/type :task]
                            '(not [?e ::sm/deleted-at])
                            '[?e :task/done-at ?done-at]
                            '[(>= ?done-at start-inst)]
                            '[(< ?done-at end-inst)]]
                           sens-clauses)
        query        {:find  '[?e]
                      :where base-where
                      :in    '[user-id start-inst end-inst]}]
    (count (q db query user-id start-instant end-instant))))

(defnp get-events-for-user-year
  "Get all events for a user within a specific year, using user's timezone.
   Note: Performs date-range filtering in application code to avoid complex
   Datalog predicates on Instants."
  [db user-id year user-timezone]
  (let [zone       (java.time.ZoneId/of (or user-timezone "UTC"))
        year-start (-> (t/date (str year "-01-01"))
                       (t/at (t/time "00:00"))
                       (t/in zone)
                       (t/instant))
        year-end   (-> (t/date (str year "-12-31"))
                       (t/at (t/time "23:59:59"))
                       (t/in zone)
                       (t/instant))
        results    (q db
                      {:find  '(pull ?e [*]),
                       :where '[[?e :xt/id ?id]
                                [?e ::sm/type :calendar-event]
                                [?e :user/id ?user-id]
                                (not [?e ::sm/deleted-at])],
                       :in    '[?user-id]}
                      user-id)]

    (->> results
         (filter (fn [e]
                   (let [dt (:calendar-event/beginning e)]
                     (if (some? dt)
                       ;; If event has a dtstart, filter by year
                       (and (t/>= dt year-start)
                            (t/<= dt year-end))
                       ;; If no dtstart, include it (events without dates)
                       true)))))))
