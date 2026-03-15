(ns tech.jgood.gleanmo.app.book
  (:require
   [tech.jgood.gleanmo.crud.routes :as crud]
   [tech.jgood.gleanmo.schema :refer [schema]]))

(def crud-routes
  (crud/gen-routes {:entity-key :book,
                    :entity-str "book",
                    :plural-str "books",
                    :schema     schema}))
