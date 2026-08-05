(ns tech.jgood.gleanmo.db.queries
  (:require
   [clojure.string :as str]
   [clojure.tools.logging :as log]
   [com.biffweb :as    biff
    :refer [q]]
   [tech.jgood.gleanmo.schema :as schema-registry]
   [tech.jgood.gleanmo.schema.utils :as schema-utils]
   [tech.jgood.gleanmo.schema.meta :as sm]
   [tick.core :as t]
   [xtdb.api :as xt]
   [taoensso.tufte :refer [defnp p]]))

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

(defn- relationship-excluded?
  "Whether one entity points at a sensitive/archived parent.
   Handles both set-valued (many-relationship) and single-valued
   (single-relationship) fields."
  [exclusion-map entity]
  (boolean
   (some (fn [[field-key excluded-ids]]
           (let [v (get entity field-key)]
             (cond
               (set? v)     (some excluded-ids v)
               (some? v)    (contains? excluded-ids v)
               :else        false)))
         exclusion-map)))

(defn- apply-relationship-exclusions
  "Phase 2: Post-filter entities whose related parent IDs intersect with exclusion sets.
   Short-circuits when exclusion-map is nil or all sets are empty."
  [exclusion-map entities]
  (if-not (seq exclusion-map)
    entities
    (remove #(relationship-excluded? exclusion-map %) entities)))

(defn- keep-doc-fn
  "Predicate for the sparse direct flags that phase-1 scans deliberately skip —
   soft-deleted, plus sensitive/archived unless the user opts into seeing them.
   All three are rare, so they cost less checked on pulled documents than as
   per-row `(not ...)` subqueries inside the scan."
  [entity-type {:keys [show-sensitive show-archived]}]
  (let [sens-key (keyword (name entity-type) "sensitive")
        arch-key (keyword (name entity-type) "archived")]
    (fn [doc]
      (and (nil? (get doc ::sm/deleted-at))
           (or show-sensitive (not (true? (get doc sens-key))))
           (or show-archived  (not (true? (get doc arch-key))))))))

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

(defnp get-entity-by-attribute-for-user
  "Get one user-owned entity of `entity-type` whose attribute equals `value`."
  [db user-id entity-type attr value]
  (-> (q db
         {:find  '(pull ?e [*])
          :where [['?e :user/id 'user-id]
                  ['?e ::sm/type 'entity-type]
                  ['?e attr 'value]]
          :in    '[user-id entity-type value]
          :limit 1}
         user-id
         entity-type
         value)
      first))

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

(defn- build-id-scan-query
  "Build an index-only query returning [?e ?sort-value] tuples — no document
   pulls. When order-key is given, entities lacking that attribute are excluded
   (matching build-entity-query's join semantics). Otherwise sorts by the
   entity's timestamp field when present, falling back to ::sm/created-at."
  [entity-type order-key]
  (let [ts-key       (keyword (name entity-type) "timestamp")
        sort-clause  (cond
                       order-key
                       [['?e order-key '?sort]]

                       (schema-has-field? entity-type "timestamp")
                       [(list 'or-join '[?e ?sort]
                              ['?e ts-key '?sort]
                              (list 'and
                                    (list 'not ['?e ts-key])
                                    ['?e ::sm/created-at '?sort]))]

                       :else
                       [['?e ::sm/created-at '?sort]])]
    ;; Deliberately no (not ...) clauses: in XTDB 1.x each one evaluates as a
    ;; per-row subquery, multiplying scan cost on big types. Deleted-at and
    ;; sensitivity flags are sparse, so they're post-filtered on pulled docs.
    {:find  '[?e ?sort]
     :where (-> ['[?e :user/id user-id]
                 ['?e ::sm/type entity-type]]
                (into sort-clause))
     :in    ['user-id]}))

(defn- build-windowed-scan-query
  "Index-only scan of `[?e ?sort ?user ?type]` tuples for entities whose sort
   key is at or after `since`. Caller filters user and type on the tuples.

   The odd-looking part — binding `:user/id` and `::sm/type` as *output*
   variables instead of as constants — is the whole point, and it is what makes
   this fast. XTDB 1.x picks a bound clause as the join driver, so
   `[?e :user/id user-id]` makes it walk every entity the user owns and apply
   the range as a row filter; the window buys nothing. Left unbound, the
   range-constrained sort attribute drives the scan, XTDB seeks straight into
   that attribute's index at `since`, and user/type become per-row lookups over
   only the rows inside the window.

   Measured on an in-memory node, 80k docs, 30k of them the user's habit-logs,
   asking for a 30-day window:

     user+type bound, no range           323ms   (what build-id-scan-query does)
     user+type bound, with range         253ms
     range drives, user as output var     21ms

   So the range predicate is not pushed down at all while a bound clause is
   present — an important thing to know before adding `[(>= ...)]` to any other
   query here and expecting it to help.

   `since` must be the same value type XTDB stores for the attribute, or the
   range comparison silently matches nothing; pass what the write path writes."
  [order-key]
  {:find  '[?e ?sort ?scan-user ?scan-type]
   :where [['?e order-key '?sort]
           '[(>= ?sort since)]
           '[?e :user/id ?scan-user]
           '[?e ::sm/type ?scan-type]]
   :in    '[since]})

(defnp windowed-scan
  "Sorted `[id sort-value]` tuples of one type inside `[since, now]`, newest
   first. Index-only — no documents are materialized."
  [db user-id entity-type order-key since]
  (->> (q db (build-windowed-scan-query order-key) since)
       (into []
             (keep (fn [[eid sort-value scan-user scan-type]]
                     (when (and (= scan-user user-id)
                                (= scan-type entity-type))
                       [eid sort-value]))))
       (sort-by second #(compare %2 %1))))

(defnp fetch-entities-by-ids
  "Batch-fetch full documents for a collection of ids, preserving order.
   Returns a vector of entity maps; missing ids are omitted."
  [db ids]
  (if (seq ids)
    (let [results (q db
                     '{:find  [(pull ?e [*])]
                       :where [[?e :xt/id ?e]]
                       :in    [[?e ...]]}
                     (vec ids))
          by-id   (into {} (map (fn [[e]] [(:xt/id e) e])) results)]
      (into [] (keep by-id) ids))
    []))

(defn- temporal-sort-field
  "Return the timestamp/beginning field used by heatmap visualizations."
  [entity-type entity-schema]
  (or (some (fn [[field-key & _]]
              (when (= "timestamp" (name field-key))
                field-key))
            (schema-utils/extract-schema-fields entity-schema))
      (some (fn [[field-key & _]]
              (when (= "beginning" (name field-key))
                field-key))
            (schema-utils/extract-schema-fields entity-schema))
      (throw (ex-info "Entity has no heatmap temporal field"
                      {:entity-type entity-type}))))

(defn- ids-with-attr
  "Return candidate ids that have attr-key, optionally requiring attr-value."
  ([db ids attr-key]
   (ids-with-attr db ids attr-key ::any-value))
  ([db ids attr-key attr-value]
   (if (seq ids)
     (let [where (cond-> [['?e attr-key]]
                   (not= attr-value ::any-value)
                   (conj ['?e attr-key attr-value]))]
       (->> (q db
               {:find  '[?e]
                :where where
                :in    '[[?e ...]]}
               (vec ids))
            (map first)
            set))
     #{})))

(defn- direct-flagged-ids
  "Return ids excluded by sparse direct flags, using index-only scans."
  [db ids entity-type {:keys [show-sensitive show-archived]}]
  (let [entity-str (name entity-type)]
    (cond-> (ids-with-attr db ids ::sm/deleted-at)
      (and (not show-sensitive)
           (schema-has-field? entity-type "sensitive"))
      (into (ids-with-attr db ids (keyword entity-str "sensitive") true))

      (and (not show-archived)
           (schema-has-field? entity-type "archived"))
      (into (ids-with-attr db ids (keyword entity-str "archived") true)))))

(defn- relation-values-for-ids
  "Return a map of entity id to minimal relationship attrs for heatmap rows."
  [db ids relationship-fields]
  (if (and (seq ids) (seq relationship-fields))
    (reduce
     (fn [acc {:keys [field-key input-type]}]
       (let [rows (q db
                     {:find  '[?e ?rel]
                      :where [['?e field-key '?rel]]
                      :in    '[[?e ...]]}
                     (vec ids))]
         (reduce
          (fn [m [eid rel-id]]
            (case input-type
              :many-relationship
              (update-in m [eid field-key] (fnil conj #{}) rel-id)

              :single-relationship
              (assoc-in m [eid field-key] rel-id)

              m))
          acc
          rows)))
     {}
     relationship-fields)
    {}))

(defn- apply-heatmap-exclusions
  "Drop sparse flagged rows and rows pointing at excluded related entities."
  [db user-id entity-type user-settings relationship-fields rows]
  (let [ids           (mapv :xt/id rows)
        flagged       (direct-flagged-ids db ids entity-type user-settings)
        relation-map  (relation-values-for-ids db ids relationship-fields)
        exclusion-map (build-exclusion-map db user-id entity-type user-settings)]
    (->> rows
         (remove (comp flagged :xt/id))
         (map #(merge % (get relation-map (:xt/id %) {})))
         (apply-relationship-exclusions exclusion-map)
         doall)))

(defnp years-with-data-for-user
  "Return visible heatmap years with distinct entity counts, newest first."
  [db user-id entity-type entity-schema & {:keys [user-settings]}]
  (let [settings      (or user-settings (get-user-settings db user-id))
        temporal-key  (temporal-sort-field entity-type entity-schema)
        rel-fields    (schema-utils/extract-relationship-fields
                       entity-schema
                       :remove-system-fields true)
        rows          (q db
                         {:find  '[?e ?sort]
                          :where [['?e :user/id 'user-id]
                                  ['?e ::sm/type entity-type]
                                  ['?e temporal-key '?sort]]
                          :in    '[user-id]}
                         user-id)
        visible-rows  (apply-heatmap-exclusions
                       db
                       user-id
                       entity-type
                       settings
                       rel-fields
                       (map (fn [[eid sort-value]]
                              {:xt/id eid, temporal-key sort-value})
                            rows))]
    (->> visible-rows
         (reduce (fn [acc row]
                   (if-let [inst (->instant (get row temporal-key))]
                     (update acc (.getYear (.atZone inst java.time.ZoneOffset/UTC))
                             (fnil conj #{})
                             (:xt/id row))
                     acc))
                 {})
         (map (fn [[year ids]] {:year year, :count (count ids)}))
         (sort-by :year >)
         vec)))

(defnp heatmap-data-for-user
  "Return minimal visible heatmap rows for an entity within an instant range.
   Uses index-only scans for ids, temporal values, relationship ids, and sparse
   direct flags; it does not pull full entity documents."
  [db user-id entity-type entity-schema range-start range-end & {:keys [user-settings]}]
  (let [settings      (or user-settings (get-user-settings db user-id))
        temporal-key  (temporal-sort-field entity-type entity-schema)
        rel-fields    (schema-utils/extract-relationship-fields
                       entity-schema
                       :remove-system-fields true)
        rows          (q db
                         {:find  '[?e ?sort]
                          :where [['?e :user/id 'user-id]
                                  ['?e ::sm/type entity-type]
                                  ['?e temporal-key '?sort]
                                  '[(>= ?sort range-start)]
                                  '[(<= ?sort range-end)]]
                          :in    '[user-id range-start range-end]}
                         user-id
                         range-start
                         range-end)]
    (apply-heatmap-exclusions
     db
     user-id
     entity-type
     settings
     rel-fields
     (map (fn [[eid sort-value]]
            {:xt/id eid, temporal-key sort-value})
          rows))))

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
   Two-phase read: (1) index-only scan of [id sort-value] tuples — no document
   pulls — sorted and paginated in Clojure, (2) batch pull of full documents
   for just the page, with relationship-exclusion filtering applied on chunks
   until the page fills. Document materialization cost scales with the page
   size instead of the user's entire history for the type."
  [db user-id entity-type &
   {:keys [filter-sensitive filter-archived filter-references
           limit offset order-key order-direction since]}]
  (let [user-settings {:show-sensitive (boolean filter-sensitive)
                       :show-archived  (boolean filter-archived)}
        ;; Build exclusion map when filter-references requested
        exclusion-map (when filter-references
                        (p {:id (keyword "exclusions" (name entity-type))}
                           (build-exclusion-map db user-id entity-type user-settings)))
        ;; Phase 1: minimal index-only scan of [id sort-value] tuples. A
        ;; `since` bound switches to the range-driven scan shape, which needs
        ;; an explicit order-key to range over.
        direction     (or order-direction default-order-direction)
        sorted-ids    (p {:id (keyword "scan" (name entity-type))}
                         (if (and since order-key)
                           (cond->> (windowed-scan db user-id entity-type
                                                   order-key since)
                             (= direction :asc) reverse
                             true               (mapv first))
                           (cond->> (sort-by second
                                             (q db
                                                (build-id-scan-query entity-type
                                                                     order-key)
                                                user-id))
                             (= direction :desc) reverse
                             true                (mapv first))))
        ;; Post-filters previously pushed into the scan as per-row (not ...)
        ;; subqueries — all sparse, so filtering pulled docs is cheaper.
        keep-doc?     (keep-doc-fn entity-type user-settings)
        ;; Phase 2: pull docs in chunks, dropping filtered ones, until the
        ;; page (offset + limit) is satisfied. Filtered docs are typically
        ;; sparse, so this usually pulls a single chunk of ~2x the limit.
        chunk-size    (if limit (max 50 (* 2 limit)) 1000)
        filtered      (->> (partition-all chunk-size sorted-ids)
                           (mapcat (fn [chunk]
                                     (->> (fetch-entities-by-ids db chunk)
                                          (filter keep-doc?)
                                          (apply-relationship-exclusions
                                           exclusion-map)))))]
    (cond->> filtered
      offset (drop offset)
      limit  (take limit)
      true   doall)))

(defn- all-ids-with-attribute
  "Every entity id carrying `attr-key`, deliberately unscoped by user or type.

   Only ever used for the 'has an end' side of a running-timer set difference,
   where the result is subtracted from an already user-scoped set — an id from
   another user or another type can only fail to match something, never leak
   one in. Dropping the two scoping clauses is what makes it cheap: a bound
   clause becomes XTDB's join driver, so the scoped form walks the user's whole
   history for the type (measured 283ms vs 139ms over 40k rows)."
  [db attr-key]
  (into #{}
        (map first)
        (q db {:find  '[?e]
               :where [['?e attr-key]]})))

(defnp active-timers-for-user
  "Fetch in-progress timer entities (beginning set, no end) for a user.

   Reads the sparse `<entity>/running` flag that `db/mutations.clj` derives at
   write time, so the scan is proportional to the number of running timers
   rather than to the user's history for the type — measured 0.18ms against
   315ms for the equivalent set difference over 40k rows. Because that clause
   is genuinely selective, XTDB intersects its two-element stream against the
   others and the `:user/id` scoping costs nothing measurable; keep it.

   The interval is re-confirmed on the pulled documents, so a stale flag can
   never surface a stopped timer as running. Stale flags are logged, not
   repaired: this takes `db` rather than `ctx`, and a GET that wrote would
   spend the rest of the request reading its own pre-write snapshot. The log is
   the useful half anyway — a phantom means some write path skipped the
   derivation. `worker/reconcile-timer-flags` does the repairing, and is the
   only thing that can see the opposite error (open interval, no flag), which
   is invisible to a query that filters on the flag."
  [db user-id entity-type beginning-key end-key & {:keys [user-settings]}]
  (let [settings      (or user-settings (get-user-settings db user-id))
        exclusion-map (build-exclusion-map db user-id entity-type settings)
        running-key   (schema-utils/entity-attr-key entity-type "running")
        ids           (into []
                            (map first)
                            (q db
                               {:find  '[?e]
                                :where [['?e :user/id 'user-id]
                                        ['?e running-key true]]
                                :in    '[user-id]}
                               user-id))
        {open true, stale false} (group-by #(and (some? (get % beginning-key))
                                                 (nil? (get % end-key)))
                                           (fetch-entities-by-ids db ids))
        keep-doc?     (keep-doc-fn entity-type settings)]
    (when (seq stale)
      (log/warn "Stale" running-key "flag on" (count stale)
                "doc(s) — a write path skipped the mutations-layer derivation:"
                (mapv :xt/id stale)))
    (->> open
         (filter keep-doc?)
         (apply-relationship-exclusions exclusion-map))))

(defnp running-flag-audit
  "Every id whose stored `running` flag disagrees with its actual interval, for
   one timer type, across all users. Backs `worker/reconcile-timer-flags`.

   `:missing` is open (beginning, no end) but unflagged — invisible to
   `active-timers-for-user`, which is why the sweep exists. `:phantom` is
   flagged but ended; the read path already filters those, this clears them.

   Deliberately the expensive set difference the flag replaced: it is the
   honest answer, and once a day off the request path is where it belongs.
   Deliberately unfiltered by deleted/sensitive/archived too — those govern
   what a user is shown, not whether an interval is open, and reconciling
   against a display filter would fight the flag back and forth forever."
  [db beginning-key end-key running-key]
  (let [ended   (all-ids-with-attribute db end-key)
        open    (into #{}
                      (remove ended)
                      (all-ids-with-attribute db beginning-key))
        flagged (into #{}
                      (map first)
                      (q db {:find  '[?e]
                             :where [['?e running-key true]]}))]
    {:missing (vec (remove flagged open)),
     :phantom (vec (remove open flagged))}))

(defnp running-timers-for-parent
  "Running timers (beginning set, no end) belonging to one parent entity.

   Unlike `active-timers-for-user` this applies no sensitivity, archived, or
   relationship-exclusion filtering. Those govern what a user is shown; this
   backs the double-submit guard, which has to see a timer the user can't —
   a hidden duplicate is still a duplicate, and filtering one out would make
   the guard silently stop working for sensitive or archived parents.

   Same scan-then-pull shape as `active-timers-for-user`, with the parent
   constraint folded into the first scan so the candidate set stays small."
  [db user-id entity-type beginning-key end-key relationship-key parent-id]
  (let [began      (into #{}
                         (map first)
                         (q db
                            {:find  '[?e]
                             :where [['?e :user/id 'user-id]
                                     ['?e ::sm/type entity-type]
                                     ['?e relationship-key 'parent-id]
                                     ['?e beginning-key]]
                             :in    '[user-id parent-id]}
                            user-id parent-id))
        ended      (all-ids-with-attribute db end-key)
        candidates (remove ended began)]
    (->> (fetch-entities-by-ids db (vec candidates))
         (remove #(get % ::sm/deleted-at)))))

(defnp recent-completed-timer-logs
  "The user's most recent completed timer logs (beginning and end both set),
   newest first by beginning, bounded by limit. Scan-then-pull: intersects two
   index-only scans ('has beginning' with sort values, 'has end') because a
   per-row (not ...) or existence subquery re-evaluates for every row of the
   type's history. Over-fetches ids because deleted/sensitive flags are only
   visible after the pull."
  [db user-id entity-type beginning-key end-key limit & {:keys [user-settings]}]
  (let [settings      (or user-settings (get-user-settings db user-id))
        exclusion-map (build-exclusion-map db user-id entity-type settings)
        end-ids       (all-ids-with-attribute db end-key)
        ids           (->> (q db
                              {:find  '[?e ?t]
                               :where [['?e :user/id 'user-id]
                                       ['?e ::sm/type entity-type]
                                       ['?e beginning-key '?t]]
                               :in    '[user-id]}
                              user-id)
                           (filter (comp end-ids first))
                           (sort-by second #(compare %2 %1))
                           (map first)
                           (take (+ limit 10)))
        keep-doc?     (keep-doc-fn entity-type settings)]
    (->> (fetch-entities-by-ids db ids)
         (filter keep-doc?)
         (apply-relationship-exclusions exclusion-map)
         (take limit)
         vec)))

(defn scan-diagnostics
  "Time sequential, uncontended index-only scans per entity type for a user.
   Returns [{:type ... :rows n :ms x}] — for the monitoring dashboard."
  [db user-id entity-types order-keys]
  (vec
   (for [etype entity-types]
     (let [order-key (get order-keys etype)
           scan-q    (build-id-scan-query (keyword etype) order-key)
           t0        (System/nanoTime)
           rows      (count (q db scan-q user-id))
           t1        (System/nanoTime)]
       {:type etype
        :rows rows
        :ms   (/ (- t1 t0) 1e6)}))))

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

(defnp distinct-field-values
  "Distinct non-blank values the user has already stored for `attr` on entities
   of `entity-type`, sorted.

   Backs the suggestion list for open-vocabulary string fields (see
   `:crud/suggest-existing`): the schema stays `:string` so any value is
   allowed, while the form can still offer what has actually been used.

   Index-only — `:find` binds the value directly with no `pull`, so the scan
   never materializes documents. The attribute is interpolated into the query
   as a literal because XTDB 1.x rejects a variable in attribute position."
  [db user-id entity-type attr]
  (when (and user-id entity-type attr)
    (->> (q db
            {:find  '[?v]
             :where [['?e :user/id 'user-id]
                     ['?e ::sm/type entity-type]
                     ['?e attr '?v]]
             :in    '[user-id]}
            user-id)
         (map first)
         (filter string?)
         (remove str/blank?)
         distinct
         sort
         vec)))

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

(defn bounded-pmap
  "Map f over coll with at most n tasks in flight, preserving order and
   conveying dynamic bindings (so tufte spans record on worker threads).
   Unlike pmap, the bound doesn't depend on the JVM's core count, which
   containers over-report — see roadmap/dashboard-performance.md."
  [n f coll]
  (let [pool (java.util.concurrent.Executors/newFixedThreadPool n)]
    (try
      (->> (.invokeAll pool
                       ^java.util.Collection
                       (mapv (fn [x] (bound-fn* (fn [] (f x)))) coll))
           (mapv (fn [^java.util.concurrent.Future fut] (.get fut))))
      (finally (.shutdown pool)))))

(defnp dashboard-recent-entities
  "Fetch a bounded set of recent entities per type for the dashboard, respecting user settings and related-entity filters."
  [db user-id {:keys [entity-types per-type-limit order-keys user-settings], :or {per-type-limit 20}}]
  (let [{:keys [show-sensitive show-archived]}
        (or user-settings (get-user-settings db user-id))]
    ;; Per-type reads are independent queries on one immutable db snapshot.
    ;; Concurrency is bounded low: the index scans are CPU-bound and wide
    ;; parallelism on a small box just queues and inflates every span, while
    ;; a small bound still overlaps the network-bound doc fetches.
    (into []
          (comp cat)
          (bounded-pmap
           3
           (fn [entity-str]
             (let [entity-kw    (keyword entity-str)
                   order-key    (get order-keys entity-str ::sm/created-at)
                   entity-schema (get schema-registry/schema entity-kw)
                   rel-fields    (schema-utils/extract-relationship-fields
                                  entity-schema
                                  :remove-system-fields true)]
               (doall
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
                 :order-direction     :desc))))
           entity-types))))

(defnp recent-activity-across-types
  "Recent entities across several types, newest first, as one globally-merged
   scan-then-pull.

   `dashboard-recent-entities` reads each type independently and pulls
   `per-type-limit` documents for every one of them — around 2,600 documents
   from the remote Neon doc store to render an 18-row timeline. Here each
   type's work stops at index tuples; they are merged and ordered once, and a
   single batch pull materializes only the documents that will actually be
   shown or counted.

   Two things have to come back: the newest `limit` entities, and everything at
   or after `full-since` (the window the caller's counts cover). Both are
   prefixes of the same descending sequence, so it is one `take` of whichever
   prefix is longer, capped at `max-pull`.

   `max-pull` is a safety valve, not a budget: only documents that actually
   exist inside the window get pulled, so a normal week costs a fraction of it.
   The default is set at the number of documents the per-type read materialized
   unconditionally (13 types x 100), which makes this strictly never the more
   expensive of the two. A week busy enough to hit the cap would undercount the
   caller's stats, so raise it rather than let that happen quietly.

   `since` bounds the scan itself and is the reason it is cheap — see
   `build-windowed-scan-query` for why the shape looks the way it does. Types
   with nothing in the window contribute nothing, so callers that must not come
   back empty-handed should check the result and fall back to an unbounded
   read.

   Future-dated rows are dropped: they are not 'recent', and left in they would
   crowd the merged head with scheduled calendar events."
  [db user-id {:keys [entity-types order-keys since full-since limit max-pull
                      user-settings]
               :or   {limit 60, max-pull 1300}}]
  (let [settings (or user-settings (get-user-settings db user-id))
        now      (t/now)
        ordered  (->> (bounded-pmap
                       3
                       (fn [entity-str]
                         (let [entity-kw (keyword entity-str)
                               order-key (get order-keys entity-str
                                              ::sm/created-at)]
                           (p {:id (keyword "window-scan" entity-str)}
                              (windowed-scan db user-id entity-kw
                                             order-key since))))
                       entity-types)
                      (into [] cat)
                      (keep (fn [[eid sort-value]]
                              (when-let [inst (->instant sort-value)]
                                (when-not (t/> inst now)
                                  [eid inst]))))
                      (sort-by second #(compare %2 %1)))
        wanted   (min max-pull
                      (max limit
                           (if full-since
                             (count (take-while #(t/>= (second %) full-since)
                                                ordered))
                             0)))
        docs     (p {:id ::recent-activity-pull}
                    (fetch-entities-by-ids db (mapv first (take wanted ordered))))
        ;; Exclusion maps cost a query per relationship field, so build them
        ;; only for the types that actually survived into the pulled page.
        exclusions (into {}
                         (map (fn [entity-type]
                                [entity-type
                                 (build-exclusion-map db user-id entity-type
                                                      settings)]))
                         (into #{} (keep ::sm/type) docs))
        keep-doc?  (memoize #(keep-doc-fn % settings))]
    (into []
          (filter (fn [doc]
                    (when-let [entity-type (::sm/type doc)]
                      (and ((keep-doc? entity-type) doc)
                           (not (relationship-excluded?
                                 (get exclusions entity-type) doc))))))
          docs)))

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

(defn count-users
  "Count all user documents."
  [db]
  (nth (q db
          '{:find  (count user)
            :where [[user :user/email]]})
       0
       0))

(defn entity-history-desc
  "Full entity history for a document, newest first, with docs."
  [db doc-id]
  (xt/entity-history db doc-id :desc {:with-docs? true}))

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

(defnp sets-for-session
  "Exercise sets belonging to one session, oldest first. Equality-bound on
   session-id so cost tracks the session's size, not the user's history.
   Deleted-at is sparse, so it's post-filtered rather than scanned."
  [db user-id session-id]
  (->> (q db
          '{:find  [(pull ?e [*])]
            :where [[?e :user/id user-id]
                    [?e ::sm/type :exercise-set]
                    [?e :exercise-set/session-id session-id]]
            :in    [user-id session-id]}
          user-id session-id)
       (map first)
       (remove ::sm/deleted-at)
       (sort-by :exercise-set/beginning)
       vec))

(defnp sets-for-sessions
  "Exercise sets belonging to the given sessions, oldest first.
   Equality-bound to a small caller-supplied session collection so recent
   session summaries do not scan the user's full exercise history."
  [db user-id session-ids]
  (if (seq session-ids)
    (->> (q db
            '{:find  [(pull ?e [*])]
              :where [[?e :user/id user-id]
                      [?e ::sm/type :exercise-set]
                      [?e :exercise-set/session-id ?sid]]
              :in    [user-id [?sid ...]]}
            user-id (vec session-ids))
         (map first)
         (remove ::sm/deleted-at)
         (sort-by :exercise-set/beginning)
         vec)
    []))

(defnp lines-for-sets
  "Exercise lines belonging to the given sets, in creation order."
  [db user-id set-ids]
  (if (seq set-ids)
    (->> (q db
            '{:find  [(pull ?e [*])]
              :where [[?e :user/id user-id]
                      [?e ::sm/type :exercise-line]
                      [?e :exercise-line/set-id ?sid]]
              :in    [user-id [?sid ...]]}
            user-id (vec set-ids))
         (map first)
         (remove ::sm/deleted-at)
         (sort-by ::sm/created-at)
         vec)
    []))

(defnp recent-lines-for-user
  "The user's most recent exercise lines, newest first, bounded by limit.
   Scan-then-pull: an index-only [id created-at] scan is sorted and truncated
   before any documents are pulled, so cost stays flat as history grows.

   `since` additionally bounds the scan, which matters more here than anywhere
   else: exercise-line is the densest type in the database — a row per exercise
   per set — and the unbounded scan walks all of it to keep the newest handful.
   A caller that passes `since` has to read an empty result as 'nothing in the
   window', not 'nothing at all'."
  [db user-id limit & {:keys [since]}]
  (let [ids (if since
              (->> (windowed-scan db user-id :exercise-line ::sm/created-at since)
                   (map first)
                   (take limit))
              (->> (q db
                      '{:find  [?e ?t]
                        :where [[?e :user/id user-id]
                                [?e ::sm/type :exercise-line]
                                [?e ::sm/created-at ?t]]
                        :in    [user-id]}
                      user-id)
                   (sort-by second #(compare %2 %1))
                   (map first)
                   (take limit)))]
    (->> (fetch-entities-by-ids db ids)
         (remove ::sm/deleted-at)
         vec)))

(defnp recent-sessions-for-user
  "The user's most recent exercise sessions, newest first, bounded by limit.
   Scan-then-pull on the beginning timestamp, same shape as
   recent-lines-for-user."
  [db user-id limit]
  (let [ids (->> (q db
                    '{:find  [?e ?t]
                      :where [[?e :user/id user-id]
                              [?e ::sm/type :exercise-session]
                              [?e :exercise-session/beginning ?t]]
                      :in    [user-id]}
                    user-id)
                 (sort-by second #(compare %2 %1))
                 (map first)
                 (take limit))]
    (->> (fetch-entities-by-ids db ids)
         (remove ::sm/deleted-at)
         vec)))

(defnp attempts-for-boulder-session
  "Boulder attempts belonging to one session, oldest first. Equality-bound on
   session-id so cost tracks the session's size, not the user's history."
  [db user-id session-id]
  (->> (q db
          '{:find  [(pull ?e [*])]
            :where [[?e :user/id user-id]
                    [?e ::sm/type :boulder-attempt]
                    [?e :boulder-attempt/session-id session-id]]
            :in    [user-id session-id]}
          user-id session-id)
       (map first)
       (remove ::sm/deleted-at)
       (sort-by :boulder-attempt/beginning)
       vec))

(defnp boulder-problems-for-user
  "All of the user's boulder problems, newest first. Problem count stays small
   (a few hundred), so a full pull is fine; callers filter archived."
  [db user-id]
  (->> (q db
          '{:find  [(pull ?e [*])]
            :where [[?e :user/id user-id]
                    [?e ::sm/type :boulder-problem]]
            :in    [user-id]}
          user-id)
       (map first)
       (remove ::sm/deleted-at)
       (sort-by ::sm/created-at #(compare %2 %1))
       vec))

(defnp recent-boulder-sessions-for-user
  "The user's most recent boulder sessions, newest first, bounded by limit.
   Scan-then-pull on the beginning timestamp. Over-fetches ids because
   deleted-at is only visible after the pull — taking exactly limit ids
   would silently shrink the result when recent rows are soft-deleted."
  [db user-id limit]
  (let [ids (->> (q db
                    '{:find  [?e ?t]
                      :where [[?e :user/id user-id]
                              [?e ::sm/type :boulder-session]
                              [?e :boulder-session/beginning ?t]]
                      :in    [user-id]}
                    user-id)
                 (sort-by second #(compare %2 %1))
                 (map first)
                 (take (+ limit 10)))]
    (->> (fetch-entities-by-ids db ids)
         (remove ::sm/deleted-at)
         (take limit)
         vec)))

(defnp recent-boulder-attempts-for-user
  "The user's most recent boulder attempts, newest first, bounded by limit.
   Scan-then-pull, used to prefill the gym screen with what was climbed last."
  [db user-id limit]
  (let [ids (->> (q db
                    '{:find  [?e ?t]
                      :where [[?e :user/id user-id]
                              [?e ::sm/type :boulder-attempt]
                              [?e :boulder-attempt/beginning ?t]]
                      :in    [user-id]}
                    user-id)
                 (sort-by second #(compare %2 %1))
                 (map first)
                 (take (+ limit 10)))]
    (->> (fetch-entities-by-ids db ids)
         (remove ::sm/deleted-at)
         (take limit)
         vec)))

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
