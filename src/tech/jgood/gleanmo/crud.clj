(ns tech.jgood.gleanmo.crud
  (:require
   [clojure.pprint :as p]
   [com.biffweb :as biff]
   [tech.jgood.gleanmo.middleware :as mid]
   [potpuri.core :as pot]
   [tech.jgood.gleanmo.ui :as ui]))

#_(defmacro defcrud [entity-name schema]
    (let [ns-name (symbol (str "tech.jgood.gleanmo.app." entity-name))]
      (list 'do
            (list 'create-ns (list 'quote ns-name))
            (list 'doseq [['fn-name 'fn-body]
                          {''new-form (list 'fn ['ctx]
                                            [:div (list 'str "New form for " (str entity-name))])}]
                  (list 'intern (list 'quote ns-name) 'fn-name 'fn-body))
            (list 'intern (list 'quote ns-name) ''routes
                  [(str "/" entity-name)
                   ["/new" {:get (symbol (str ns-name "/new-form"))}]]))))

(defmacro defcrud [entity-name schema]
  (let [entity-key      (keyword entity-name)
        entity-schema   (get schema entity-key)
        entity-name-str (name entity-name)]

    `(do
       (defn ~'new-form [ctx#]
         (let []
           (ui/page
            {}
            [:div ~(str "new form for: " entity-name-str)]
            (biff/form
             {:action ~(str "/app/crud/" entity-name-str)}
             [:input {:type "text" :name "test-input" :autocomplete "off"}]
             [:button {:type "submit"} "Create"]))))

       (defn ~'create! [ctx#]
         (let [params# (:params ctx#)]
           (p/pprint {:params params#})
           #_(biff/submit-tx ctx# [entity#])
           {:status  303
            :headers {"Location" ~(str "/app/crud/" entity-name-str)}}))

       (defn ~'view [ctx#] (ui/page {} [:div ~(str "view: " entity-name-str)]))

       (def ~'crud-module
         {:routes (concat ["/app/crud" {:middleware [mid/wrap-signed-in]}
                           [~(str "/new/" entity-name-str) {:get ~'new-form}]
                           [~(str "/" entity-name-str)     {:get ~'view :post ~'create!}]])}))))
