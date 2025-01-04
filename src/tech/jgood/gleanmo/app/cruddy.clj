(ns tech.jgood.gleanmo.app.cruddy
  (:require
   [tech.jgood.gleanmo.crud :as crud]
   [tech.jgood.gleanmo.schema :refer [schema]]))

(def routes (crud/gen-routes :cruddy schema))
