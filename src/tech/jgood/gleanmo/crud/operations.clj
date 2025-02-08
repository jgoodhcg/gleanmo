(ns tech.jgood.gleanmo.crud.operations
  (:require
   [com.biffweb :as biff :refer [q]]
   [tech.jgood.gleanmo.app.shared :refer [param-true?]]
   [tech.jgood.gleanmo.schema.meta :as sm]))

(defn all-for-user-query [{:keys [entity-type-str]}
                          {:keys [biff/db session params]}]
  (let [sensitive      (some-> params :sensitive param-true?)
        archived       (some-> params :archived param-true?)
        entity-type    (keyword entity-type-str)
        time-stamp-key (keyword entity-type-str "timestamp")
        sensitive-key  (keyword entity-type-str "sensitive")
        archived-key   (keyword entity-type-str "archived")
        raw-results    (q db {:find  '(pull ?e [*])
                              :where ['[?e :user/id user-id]
                                      ['?e ::sm/type entity-type]
                                      '[?user :xt/id user-id]
                                      '(not [?e ::sm/deleted-at])]
                              :in    ['user-id]}
                          (:uid session))]
    (cond->> raw-results
      :always         (sort-by #(or (time-stamp-key %) (::sm/created-at %)))
      :always         (reverse)
      (not sensitive) (remove sensitive-key)
      (not archived)  (remove archived-key))))
