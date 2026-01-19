(ns tech.jgood.gleanmo.test
  (:require
   [clojure.test :refer [deftest is]]
   [tech.jgood.gleanmo.test.app.calendar-test]
   [tech.jgood.gleanmo.test.app.shared-test]
   [tech.jgood.gleanmo.test.app.task-test]
   [tech.jgood.gleanmo.test.app.user-test]
   [tech.jgood.gleanmo.test.crud.forms-test]
   [tech.jgood.gleanmo.test.crud.forms.converters-test]
   [tech.jgood.gleanmo.test.crud.forms.inputs-test]
   [tech.jgood.gleanmo.test.crud.handlers-test]
   [tech.jgood.gleanmo.test.crud.schema-utils-test]
   [tech.jgood.gleanmo.test.crud.views-test]
   [tech.jgood.gleanmo.test.crud.views.formatting-test]
   [tech.jgood.gleanmo.test.db.mutations-test]
   [tech.jgood.gleanmo.test.db.queries-test]
   [tech.jgood.gleanmo.test.timer.routes-test]))

(deftest example-test
  (is (= 2 (+ 1 1))))


