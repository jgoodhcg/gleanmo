(ns tech.jgood.gleanmo.test
  (:require
   [clojure.test :refer [deftest is]]
   [tech.jgood.gleanmo.test.db.mutations-test]
   [tech.jgood.gleanmo.test.db.queries-test]
   [tech.jgood.gleanmo.test.crud.schema-utils-test]
   [tech.jgood.gleanmo.test.crud.forms.converters-test]
   [tech.jgood.gleanmo.test.crud.handlers-test]))

(deftest example-test
  (is (= 2 (+ 1 1))))



