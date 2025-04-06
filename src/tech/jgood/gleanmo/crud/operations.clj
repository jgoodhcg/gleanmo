(ns tech.jgood.gleanmo.crud.operations
  (:require
   [clojure.pprint :refer [pprint]]
   [potpuri.core :as pot]
   [clojure.string :as str]
   [com.biffweb :as biff :refer [q]]
   [tech.jgood.gleanmo.app.shared :refer [param-true?]]
   [tech.jgood.gleanmo.crud.schema-utils :as schema-utils]
   [tech.jgood.gleanmo.schema.meta :as sm]
   [tick.core :as t]))

(defn- has-filtered-related-entity?
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
      (-> result first))))

(defn all-for-user-query
  [{:keys [entity-type-str schema filter-references]}
   {:keys [biff/db session params]}]
  (let [user-id             (:uid session)
        sensitive           (some-> params
                                    :sensitive
                                    param-true?)
        archived            (some-> params
                                    :archived
                                    param-true?)
        entity-type         (keyword entity-type-str)
        time-stamp-key      (keyword entity-type-str "timestamp")
        sensitive-key       (keyword entity-type-str "sensitive")
        archived-key        (keyword entity-type-str "archived")

        ;; Get relationship fields from schema, removing system fields like
        ;; :user/id
        relationship-fields (when (and schema filter-references)
                              (schema-utils/extract-relationship-fields
                               schema
                               :remove-system-fields
                               true))

        ;; Query for entities
        raw-results         (q db
                               {:find  '(pull ?e [*]),
                                :where ['[?e :user/id user-id]
                                        ['?e ::sm/type entity-type]
                                        '[?user :xt/id user-id]
                                        '(not [?e ::sm/deleted-at])],
                                :in    ['user-id]}
                               user-id)
        entities            raw-results

        ;; Filter by related entity attributes
        filtered-entities   (if (and filter-references relationship-fields)
                              (let [;; Get related entity types
                                    related-types     (->>
                                                       relationship-fields
                                                       (map
                                                        :related-entity-str)
                                                       (remove nil?)
                                                       set)
                                    filter-sensitive? (not sensitive)
                                    filter-archived?  (not archived)]
                                ;; Filter out entities with sensitive or
                                ;; archived related entities based on
                                ;; current sensitivity and archive settings
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
      :always         (sort-by #(or (time-stamp-key %) (::sm/created-at %)))
      :always         (reverse)
      (not sensitive) (remove sensitive-key)
      (not archived)  (remove archived-key))))
