(ns tech.jgood.gleanmo.db.queries
  (:require
   [clojure.tools.logging :as log]
   [com.biffweb :as    biff
    :refer [q]]
   [potpuri.core :as pot]
   [tech.jgood.gleanmo.schema.utils :as schema-utils]
   [tech.jgood.gleanmo.schema.meta :as sm]
   [tick.core :as t]
   [xtdb.api :as xt]
   [taoensso.tufte :refer [defnp defnp-]]))

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

(defn get-user-settings
  "Get all user settings in a single query. Returns a map with email and boolean settings."
  [db user-id]
  (when user-id
    (if-let [user (get-entity-by-id db user-id)]
      {:email          (:user/email user),
       :show-sensitive (boolean (:user/show-sensitive user)),
       :show-archived  (boolean (:user/show-archived user))}
      {:email          nil,
       :show-sensitive false,
       :show-archived  false})))

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

(defnp- should-remove-related-entity
  "Check if an entity has related entities that should be removed based on sensitivity or archive settings.
   Returns true if any related entity matches removal criteria."
  [entity relationship-fields db remove-sensitive remove-archived]
  (boolean
   (some
    (fn [{:keys [field-key input-type related-entity-str]}]
      (let [rel-sensitive-key (keyword related-entity-str "sensitive")
            rel-archived-key  (keyword related-entity-str "archived")
            related-ids       (if (= input-type :many-relationship)
                                (get entity field-key)
                                #{(get entity field-key)})]
        (when (seq related-ids)
            ;; Query for related entities
          (let [related-entities (mapv
                                  (fn [id]
                                    (first (q db
                                              {:find  '(pull ?e [*]),
                                               :where [['?e :xt/id id]],
                                               :in    '[id]}
                                              id)))
                                  (vec related-ids))]
              ;; Check if any related entity matches our removal criteria
            (some (fn [entity]
                    (or (and remove-sensitive (get entity rel-sensitive-key))
                        (and remove-archived (get entity rel-archived-key))))
                  related-entities)))))
    relationship-fields)))

(def ^:private default-order-direction :desc)

(defn- build-entity-query
  [user-id entity-type order-key order-direction limit offset]
  (let [order-direction (or order-direction default-order-direction)
        order-var       '?order-value
        base-where      ['[?e :user/id user-id]
                         ['?e ::sm/type entity-type]
                         '(not [?e ::sm/deleted-at])]
        where-clauses   (if order-key
                          (conj base-where (vec ['?e order-key order-var]))
                          base-where)
        find-elements   (if order-key
                          '[(pull ?e [*]) ?order-value]
                          '[(pull ?e [*])])]
    (cond-> {:find  find-elements
             :where where-clauses
             :in    ['user-id]}
      order-key (assoc :order-by [[order-var order-direction]])
      limit     (assoc :limit limit)
      offset    (assoc :offset offset))))

(defnp all-entities-for-user
  "Get all entities of a specific type that belong to a user.
   Optionally includes or removes entities based on sensitivity, archive status, and related entities."
  [db user-id entity-type &
   {:keys [filter-sensitive filter-archived filter-references
           relationship-fields limit offset order-key order-direction]}]
  (let [entity-type-str (name entity-type)
        sensitive-key   (keyword entity-type-str "sensitive")
        archived-key    (keyword entity-type-str "archived")
        time-stamp-key  (keyword entity-type-str "timestamp")
        filter-entities (fn [entities]
                          (let [basic-filtered (cond->> entities
                                                   (not filter-sensitive) (remove #(get % sensitive-key))
                                                   (not filter-archived)  (remove #(get % archived-key)))]
                            (if (and filter-references relationship-fields)
                              (let [remove-sensitive (not filter-sensitive)
                                    remove-archived  (not filter-archived)]
                                (remove #(should-remove-related-entity
                                          %
                                          relationship-fields
                                          db
                                          remove-sensitive
                                          remove-archived)
                                        basic-filtered))
                              basic-filtered)))
        target-count    (when limit (+ (or offset 0) limit))
        batch-size      (let [candidate (max 32 (or limit 0))]
                          (if (pos? candidate) candidate 32))
        filtered-results (loop [acc        []
                                raw-offset 0]
                            (let [query-map   (build-entity-query
                                               user-id
                                               entity-type
                                               order-key
                                               order-direction
                                               batch-size
                                               raw-offset)
                                  raw-results (q db query-map user-id)
                                  fetched     (count raw-results)
                                  entities    (map first raw-results)
                                  filtered    (filter-entities entities)
                                  new-acc     (into acc filtered)
                                  enough?     (and target-count
                                                   (>= (count new-acc) target-count))
                                  exhausted?  (< fetched batch-size)]
                              (if (or exhausted? enough?)
                                new-acc
                                (recur new-acc (+ raw-offset fetched)))))
        ordered-results (cond->> filtered-results
                           (nil? order-key)
                           (sort-by #(or (time-stamp-key %) (::sm/created-at %)))
                           (nil? order-key)
                           reverse)
        offset'         (max 0 (or offset 0))
        after-offset    (if (zero? offset')
                          ordered-results
                          (drop offset' ordered-results))]

    (cond->> after-offset
      limit (take limit))))

(defnp all-for-user-query
  "Get all entities for a user with include/exclude options from user settings.
   This function is a higher-level wrapper around all-entities-for-user that handles
   user settings for including sensitive entities, archived entities, and related entity filtering."
  [{:keys [entity-type-str schema filter-references limit offset order-key order-direction]}
   {:keys [biff/db session params]}]
  (let [user-id             (:uid session)
        ;; Get user's preferences from settings, with secure defaults
        {:keys [show-sensitive show-archived]} (get-user-settings db user-id)
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

(defn get-user-authz
  "Get user authorization info (super-user status) for a user ID."
  [db user-id]
  (when user-id
    (when-let [user (get-entity-by-id db user-id)]
      {:super-user (boolean (:authz/super-user user))})))

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
