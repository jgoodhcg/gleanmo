(ns tech.jgood.gleanmo.crud.routes
  (:require
   [potpuri.core :as pot]
   [tech.jgood.gleanmo.crud.forms :as forms]
   [tech.jgood.gleanmo.crud.views :as views]))

(defn gen-routes [{:keys [entity-key schema plural-str entity-str]}]
  (let [schema          (entity-key schema)
        args            (pot/map-of entity-key
                                    schema
                                    plural-str
                                    entity-str)]
    ["/crud" {}
     ;; new is preppended because the trie based router can't distinguish between
     ;; /entity/new and /entity/:id
     ;; this could be fixed with a linear based router but I think this is a fine REST convention to break from
     [(str "/new/" entity-str) {:get (partial forms/new-form args)}]
     [(str "/" entity-str) {:get (partial views/list-entities args)
                           :post (partial forms/create-entity! args)}]]))
