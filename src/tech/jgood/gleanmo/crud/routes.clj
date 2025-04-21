(ns tech.jgood.gleanmo.crud.routes
  (:require [potpuri.core :as pot]
            [tech.jgood.gleanmo.crud.forms :as forms]
            [tech.jgood.gleanmo.crud.handlers :as handlers]
            [tech.jgood.gleanmo.crud.views :as views]))

(defn gen-routes
  [{:keys [entity-key schema plural-str entity-str]}]
  (let [schema (entity-key schema)
        args   (pot/map-of entity-key schema plural-str entity-str)]
    ["/crud" {}
     ;; Form routes - grouped under /forms
     ["/forms" {}
      [(str "/" entity-str "/new") {:get (partial forms/new-form args)}]
      [(str "/" entity-str "/edit/:id") {:get (partial forms/edit-form args)}]]
     ;; Data routes
     [(str "/" entity-str)
      {:get  (partial views/list-entities args)
       :post (partial handlers/create-entity! args)}]
     [(str "/" entity-str "/:id")
      {:post (partial handlers/update-entity! args)}]
     ;; Delete route
     [(str "/" entity-str "/:id/delete")
      {:post (partial handlers/delete-entity! args)}]]))