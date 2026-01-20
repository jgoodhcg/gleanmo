(ns tech.jgood.gleanmo.test.crud.forms.converters-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [tech.jgood.gleanmo.crud.forms.converters :as converters]
   [tech.jgood.gleanmo.app.shared :as shared]
   [tick.core :as t])
  (:import
   [java.time ZoneId LocalDateTime ZonedDateTime]))

;; Helper for testing specific exceptions
(defn thrown-ex-info-with-type?
  [^clojure.lang.ExceptionInfo ex type]
  (= type (:type (ex-data ex))))

(deftest convert-field-value-test
  (testing "convert-field-value"

    (testing "string conversion"
      (is (= "test" (converters/convert-field-value :string "test" nil)))
      (is (= "" (converters/convert-field-value :string "" nil))))

    (testing "int conversion"
      (is (= 123 (converters/convert-field-value :int "123" nil)))
      (is (= -42 (converters/convert-field-value :int "-42" nil)))
      (is (= 0 (converters/convert-field-value :int "0" nil)))

      (testing "invalid int throws exception with proper data"
        (try
          (converters/convert-field-value :int "abc" nil)
          (is false "Should have thrown exception")
          (catch clojure.lang.ExceptionInfo e
            (is (= :int (:type (ex-data e))))
            (is (= "abc" (:value (ex-data e))))))))

    (testing "float conversion"
      (is (= 123.0 (converters/convert-field-value :float "123" nil)))
      (is (= (float 123.45) (converters/convert-field-value :float "123.45" nil)))
      (is (= (float -42.5) (converters/convert-field-value :float "-42.5" nil)))

      (testing "invalid float throws exception with proper data"
        (try
          (converters/convert-field-value :float "not-a-float" nil)
          (is false "Should have thrown exception")
          (catch clojure.lang.ExceptionInfo e
            (is (= :float (:type (ex-data e))))
            (is (= "not-a-float" (:value (ex-data e))))))))

    (testing "number conversion"
      (is (= 123.0 (converters/convert-field-value :number "123" nil)))
      (is (= 123.456 (converters/convert-field-value :number "123.456" nil)))
      (is (= -42.5 (converters/convert-field-value :number "-42.5" nil)))

      (testing "invalid number throws exception with proper data"
        (try
          (converters/convert-field-value :number "not-a-number" nil)
          (is false "Should have thrown exception")
          (catch clojure.lang.ExceptionInfo e
            (is (= :number (:type (ex-data e))))
            (is (= "not-a-number" (:value (ex-data e))))))))

    (testing "boolean conversion"
      (is (true? (converters/convert-field-value :boolean "on" nil)))
      (is (true? (converters/convert-field-value :boolean "true" nil)))
      (is (true? (converters/convert-field-value :boolean true nil)))
      (is (false? (converters/convert-field-value :boolean "off" nil)))
      (is (false? (converters/convert-field-value :boolean "false" nil)))
      (is (false? (converters/convert-field-value :boolean false nil)))
      (is (false? (converters/convert-field-value :boolean nil nil)))
      (is (false? (converters/convert-field-value :boolean "" nil))))

    (testing "enum conversion"
      (is (= :admin (converters/convert-field-value :enum "admin" nil)))
      (is (= :user (converters/convert-field-value :enum "user" nil)))
      (is (nil? (converters/convert-field-value :enum "" nil)))
      (is (nil? (converters/convert-field-value :enum nil nil))))

    (testing "single-relationship conversion (UUID)"
      (let [uuid-str "00000000-0000-0000-0000-000000000000"
            uuid (java.util.UUID/fromString uuid-str)]
        (is (= uuid (converters/convert-field-value :single-relationship uuid-str nil))))

      (testing "invalid UUID throws exception with proper data"
        (try
          (converters/convert-field-value :single-relationship "not-a-uuid" nil)
          (is false "Should have thrown exception")
          (catch clojure.lang.ExceptionInfo e
            (is (= :single-relationship (:type (ex-data e))))
            (is (= "not-a-uuid" (:value (ex-data e))))))))

    (testing "many-relationship conversion (set of UUIDs)"
      (let [uuid-str1 "00000000-0000-0000-0000-000000000000"
            uuid-str2 "11111111-1111-1111-1111-111111111111"
            uuid1 (java.util.UUID/fromString uuid-str1)
            uuid2 (java.util.UUID/fromString uuid-str2)]

        (testing "with a single string value"
          (is (= #{uuid1} (converters/convert-field-value :many-relationship uuid-str1 nil))))

        (testing "with a collection of values"
          (is (= #{uuid1 uuid2} (converters/convert-field-value :many-relationship [uuid-str1 uuid-str2] nil))))

        (testing "with empty string"
          (is (nil? (converters/convert-field-value :many-relationship "" nil))))

        (testing "invalid UUID in collection throws exception"
          (try
            (converters/convert-field-value :many-relationship ["not-a-uuid" uuid-str1] nil)
            (is false "Should have thrown exception")
            (catch clojure.lang.ExceptionInfo e
              (is (= :many-relationship (:type (ex-data e))))
              (is (= "not-a-uuid" (:value (ex-data e))))))

          (try
            (converters/convert-field-value :many-relationship "not-a-uuid" nil)
            (is false "Should have thrown exception")
            (catch clojure.lang.ExceptionInfo e
              (is (= :many-relationship (:type (ex-data e))))
              (is (= "not-a-uuid" (:value (ex-data e))))))))))

  (testing "instant conversion"
    (let [utc-zone-id (ZoneId/of "UTC")
          est-zone-id (ZoneId/of "America/New_York")
          datetime-str "2023-05-15T14:30"
          expected-utc-instant (-> (LocalDateTime/parse datetime-str)
                                   (ZonedDateTime/of utc-zone-id)
                                   (t/instant))]

        ;; Mock both shared/get-user-time-zone and shared/str->instant! to avoid DB dependencies
      (with-redefs [shared/get-user-time-zone (constantly "UTC")
                    shared/str->instant! (fn [dt-str zone-id]
                                           (if (= dt-str "not-a-date")
                                             (throw (IllegalArgumentException. "Failed to parse date"))
                                             (-> (LocalDateTime/parse dt-str)
                                                 (ZonedDateTime/of zone-id)
                                                 (t/instant))))]

        (testing "with valid datetime string"
          (is (= expected-utc-instant (converters/convert-field-value :instant datetime-str nil))))

        (testing "with empty string"
          (is (nil? (converters/convert-field-value :instant "" nil))))

        (testing "with nil value"
          (is (nil? (converters/convert-field-value :instant nil nil))))

        (testing "with invalid format throws exception with proper data"
          (try
            (converters/convert-field-value :instant "not-a-date" nil)
            (is false "Should have thrown exception")
            (catch clojure.lang.ExceptionInfo e
              (is (= :instant (:type (ex-data e))))
              (is (= "not-a-date" (:value (ex-data e))))))))))

  (testing "with different timezone"
    (let [est-zone-id (ZoneId/of "America/New_York")
          datetime-str "2023-05-15T14:30"
          expected-est-instant (-> (LocalDateTime/parse datetime-str)
                                   (ZonedDateTime/of est-zone-id)
                                   (t/instant))]

      (with-redefs [shared/get-user-time-zone (constantly "America/New_York")
                    shared/str->instant! (fn [dt-str zone-id]
                                           (-> (LocalDateTime/parse dt-str)
                                               (ZonedDateTime/of zone-id)
                                               (t/instant)))]
        (is (= expected-est-instant (converters/convert-field-value :instant datetime-str nil))))))

  (testing "unknown type throws exception"
    (try
      (converters/convert-field-value :unknown-type "value" nil)
      (is false "Should have thrown exception")
      (catch clojure.lang.ExceptionInfo e
        (is (= :unknown-type (:type (ex-data e))))
        (is (= "value" (:value (ex-data e))))))))
