(ns tech.jgood.gleanmo.app.macro-test
  (:require [tech.jgood.gleanmo.schema :refer [schema]]
            [tech.jgood.gleanmo.crud :refer [defcrud]]))

(defcrud :macro-test schema)
