(ns tech.jgood.gleanmo.crud.routes
  (:require [tech.jgood.gleanmo.crud.forms :as forms]
            [tech.jgood.gleanmo.crud.handlers :as handlers]
            [tech.jgood.gleanmo.crud.views :as views]))

(defn gen-routes
  [{:keys [entity-key schema plural-str entity-str]}]
  (let [schema-map schema
        entity-schema (entity-key schema-map)
        args   {:entity-key entity-key
                :schema     entity-schema
                :schema-map schema-map
                :plural-str plural-str
                :entity-str entity-str}]
    ["/crud" {}
     ;; Form routes - grouped under /form
     ["/form" {}
      [(str "/" entity-str "/new") {:get (partial forms/new-form args)}]
      [(str "/" entity-str "/edit/:id") {:get (partial forms/edit-form args)}]]
     ;; Data routes - using query param for view type
     [(str "/" entity-str)
      {:get  (partial views/list-entities args)
       :post (partial handlers/create-entity! args)}]
     [(str "/" entity-str "/:id")
      {:post (partial handlers/update-entity! args)}]
     ;; Delete route
     [(str "/" entity-str "/:id/delete")
      {:post (partial handlers/delete-entity! args)}]]))
