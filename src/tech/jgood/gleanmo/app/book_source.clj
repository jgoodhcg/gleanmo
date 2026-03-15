(ns tech.jgood.gleanmo.app.book-source
  (:require
   [tech.jgood.gleanmo.crud.routes :as crud]
   [tech.jgood.gleanmo.schema :refer [schema]]))

(def crud-routes
  (crud/gen-routes {:entity-key :book-source,
                    :entity-str "book-source",
                    :plural-str "book sources",
                    :schema     schema}))
