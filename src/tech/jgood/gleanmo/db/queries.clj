(ns tech.jgood.gleanmo.db.queries
  (:require
   [com.biffweb :as biff :refer [q]]
   [tech.jgood.gleanmo.schema.meta :as sm]))

(defn get-entity-by-id
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

(defn get-entity-for-user
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

(defn- has-filtered-related-entity?
  "Check if an entity has related entities that match sensitivity or archive filters."
  [entity relationship-fields db filter-sensitive? filter-archived?]
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
              ;; Check if any related entity matches our filter criteria
            (some (fn [entity]
                    (or (and filter-sensitive? (get entity rel-sensitive-key))
                        (and filter-archived? (get entity rel-archived-key))))
                  related-entities)))))
    relationship-fields)))

(defn all-entities-for-user
  "Get all entities of a specific type that belong to a user.
   Optionally filter by sensitivity, archive status, and related entities."
  [db user-id entity-type &
   {:keys [filter-sensitive filter-archived filter-references
           relationship-fields]}]
  (let [entity-type-str   (name entity-type)
        sensitive-key     (keyword entity-type-str "sensitive")
        archived-key      (keyword entity-type-str "archived")
        time-stamp-key    (keyword entity-type-str "timestamp")
        ;; Query for entities
        raw-results       (q db
                             {:find  '(pull ?e [*]),
                              :where ['[?e :user/id user-id]
                                      ['?e ::sm/type entity-type]
                                      '[?user :xt/id user-id]
                                      '(not [?e ::sm/deleted-at])],
                              :in    ['user-id]}
                             user-id)
        entities          raw-results

        ;; Filter by related entity attributes
        filtered-entities (if (and filter-references relationship-fields)
                            (let [;; Get related entity types
                                  related-types     (->> relationship-fields
                                                         (map
                                                          :related-entity-str)
                                                         (remove nil?)
                                                         set)
                                  filter-sensitive? (not filter-sensitive)
                                  filter-archived?  (not filter-archived)]
                              ;; Filter out entities with sensitive or
                              ;; archived related entities based on current
                              ;; settings
                              (->> entities
                                   (remove #(has-filtered-related-entity?
                                             %
                                             relationship-fields
                                             db
                                             filter-sensitive?
                                             filter-archived?))))
                            entities)]

    ;; Basic filtering for sensitivity and archiving
    (cond->> filtered-entities
      :always (sort-by #(or (time-stamp-key %) (::sm/created-at %)))
      :always (reverse)
      (not filter-sensitive) (remove sensitive-key)
      (not filter-archived) (remove archived-key))))
