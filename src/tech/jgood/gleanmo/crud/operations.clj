(ns tech.jgood.gleanmo.crud.operations
  (:require
   [clojure.pprint :refer [pprint]]
   [clojure.string :as str]
   [com.biffweb :as biff :refer [q]]
   [tech.jgood.gleanmo.app.shared :refer [param-true?]]
   [tech.jgood.gleanmo.crud.schema-utils :as schema-utils]
   [tech.jgood.gleanmo.schema.meta :as sm]))

(defn all-for-user-query [{:keys [entity-type-str schema filter-references]}
                          {:keys [biff/db session params]}]
  (let [user-id        (:uid session)
        sensitive      (some-> params :sensitive param-true?)
        archived       (some-> params :archived param-true?)
        entity-type    (keyword entity-type-str)
        time-stamp-key (keyword entity-type-str "timestamp")
        sensitive-key  (keyword entity-type-str "sensitive")
        archived-key   (keyword entity-type-str "archived")
        
        ;; Get relationship fields from schema, removing system fields like :user/id
        relationship-fields (when (and schema filter-references)
                              (schema-utils/extract-relationship-fields
                               schema
                               :remove-system-fields true))
        
        ;; Debug log
        _ (when (and relationship-fields filter-references)
            (pprint {:entity-type entity-type-str
                     :relationship-fields (map :field-key relationship-fields)
                     :full-fields relationship-fields}))
        
        ;; Query for entities
        raw-results    (q db {:find  '(pull ?e [*])
                              :where ['[?e :user/id user-id]
                                      ['?e ::sm/type entity-type]
                                      '[?user :xt/id user-id]
                                      '(not [?e ::sm/deleted-at])]
                              :in    ['user-id]}
                          user-id)
        entities       raw-results]
    
    ;; Basic filtering for sensitivity and archiving
    (cond->> entities
      :always         (sort-by #(or (time-stamp-key %) (::sm/created-at %)))
      :always         (reverse)
      (not sensitive) (remove sensitive-key)
      (not archived)  (remove archived-key))))
