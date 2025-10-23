(ns tech.jgood.gleanmo.test.postgres.codec-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [clojure.walk :as walk]
   [tech.jgood.gleanmo.postgres.codec :as codec]
   [tech.jgood.gleanmo.schema.meta :as sm])
  (:import
   [java.time Instant LocalDate LocalDateTime]
   [java.util UUID]))

(def ^:private uuid-1 (UUID/fromString "11111111-1111-1111-1111-111111111111"))
(def ^:private uuid-2 (UUID/fromString "22222222-2222-2222-2222-222222222222"))
(def ^:private uuid-3 (UUID/fromString "33333333-3333-3333-3333-333333333333"))

(deftest entity-roundtrip-test
  (let [instant     (Instant/parse "2024-10-01T12:34:56Z")
        local-date  (LocalDate/parse "2024-09-30")
        local-dtime (LocalDateTime/parse "2024-10-01T08:15:30")
        entity      {:xt/id            uuid-1
                     ::sm/type         :cruddy
                     :cruddy/label     "Codec Test"
                     :cruddy/enum      :cruddy.status/active
                     :cruddy/set       #{uuid-2 uuid-3}
                     :cruddy/vector    [1 2 {:nested/set #{uuid-2}}]
                     :cruddy/map       {:habit/id uuid-2
                                        :habit/inst instant
                                        :habit/date local-date}
                     :cruddy/instant   instant
                     :cruddy/local-dt  local-dtime
                     :cruddy/local-date local-date
                     :cruddy/nil       nil}
        encoded     (codec/entity->json-doc entity)
        decoded     (codec/json-doc->entity encoded)]
    (testing "round-tripping through JSON codec preserves data"
      (is (= entity decoded)))
    (testing "encoded keys are plain strings"
      (is (every? string? (keys encoded))))
    (testing "encoded document contains only JSON primitives"
      (let [bad-values (atom [])
            bad-keys   (atom [])]
        (walk/postwalk
         (fn [v]
           (when (map? v)
             (doseq [k (keys v)]
               (when-not (string? k)
                 (swap! bad-keys conj k))))
           (when (or (keyword? v)
                     (uuid? v)
                     (inst? v)
                     (set? v))
             (swap! bad-values conj v))
           v)
         encoded)
        (is (empty? @bad-keys) "All map keys should be strings")
        (is (empty? @bad-values) "Encoded doc should not contain Clojure-only types")))))

(deftest decode-unknown-tag-test
  (is (thrown? clojure.lang.ExceptionInfo
               (codec/json-doc->entity
                {"field" {"__gleanmo$type" "mystery"
                          "__gleanmo$value" "oops"}}))))
