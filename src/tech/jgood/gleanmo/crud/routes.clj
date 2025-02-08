(ns tech.jgood.gleanmo.crud.routes
  (:require
   [potpuri.core :as pot]
   [tech.jgood.gleanmo.crud.form :as form]))

(defn gen-routes [{:keys [entity-key schema plural-str]}]
  (let [schema          (entity-key schema)
        entity-name-str (name entity-key)
        args            (pot/map-of entity-key
                                    schema
                                    plural-str
                                    entity-name-str)]
    ["/crud" {}
     ;; new is preppended because the trie based router can't distinguish between
     ;; /entity/new and /entity/:id
     ;; this could be fixed with a linear based router but I think this is a fine REST convention to break from
     [(str "/new/" entity-name-str) {:get (partial form/new-form args)}]]))
