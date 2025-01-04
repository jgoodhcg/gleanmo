(ns tech.jgood.gleanmo.crud
  (:require
   [clojure.pprint :as p]
   [com.biffweb :as biff]
   [tech.jgood.gleanmo.middleware :as mid]
   [potpuri.core :as pot]
   [tech.jgood.gleanmo.ui :as ui]))

(defn gen-new-form-route [entity-name-str]
  `(defn ~'new-form [ctx#]
     (ui/page
      {}
      [:div ~(str "new form for: " entity-name-str)]
      (biff/form
       {:action ~(str "/app/crud/" entity-name-str)}
       [:input {:type "text" :name "test-input" :autocomplete "off"}]
       [:button {:type "submit"} "Create"]))))

(defn gen-create-route [entity-name-str]
  `(defn ~'create! [ctx#]
     (let [params# (:params ctx#)]
       (p/pprint {:params params#})
       #_(biff/submit-tx ctx# [~entity-name])
       {:status  303
        :headers {"Location" ~(str "/app/crud/" entity-name-str)}})))

(defn gen-view-route [entity-name-str]
  `(defn ~'view [ctx#]
     (ui/page {} [:div ~(str "view*: " entity-name-str)])))

(defmacro defcrud [entity-key schema]
  (let [entity-schema   (get schema entity-key)
        entity-name-str (name entity-key)]
    `(do
       ~(gen-new-form-route entity-name-str)
       ~(gen-create-route entity-name-str)
       ~(gen-view-route entity-name-str)
       (def ~'crud-module
         {:routes (concat
                   ["/app/crud" {:middleware [mid/wrap-signed-in]}
                    [~(str "/new/" entity-name-str) {:get ~'new-form}]
                    [~(str "/" entity-name-str)     {:get ~'view :post ~'create!}]])}))))
