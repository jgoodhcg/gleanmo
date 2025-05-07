(ns tech.jgood.gleanmo.test.crud.views.formatting-test
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [tech.jgood.gleanmo.app.shared :as shared]
   [tech.jgood.gleanmo.crud.views.formatting :as fmt]
   [tech.jgood.gleanmo.db.queries :as db]
   [tech.jgood.gleanmo.schema.meta :as sm]
   [xtdb.api :as xt]))

;; Mock functions for testing
(defn test-fixtures
  [f]
  (with-redefs [shared/format-date-time-local (constantly "2023-05-15 14:30")
                shared/get-user-time-zone     (constantly "UTC")]
    (f)))

(use-fixtures :each test-fixtures)

(deftest format-cell-value-string-test
  (testing "format-cell-value for :string type"
    (testing "handles nil or blank value"
      (is (= [:span.text-gray-400 "—"] (fmt/format-cell-value :string nil {})))
      (is (= [:span.text-gray-400 "—"] (fmt/format-cell-value :string "" {}))))

    (testing "displays short string without truncation"
      (let [result (fmt/format-cell-value :string "Short text" {})]
        (is (= :div.max-w-xs.truncate (first result)))
        (is (= "" (get-in result [1 :title])))
        (is (= "Short text" (last result)))))

    (testing "truncates long string with ellipsis"
      (let [long-text (apply str (repeat 60 "a"))
            result    (fmt/format-cell-value :string long-text {})]
        (is (= :div.max-w-xs.truncate (first result)))
        (is (= long-text (get-in result [1 :title])))
        (is (= (str (apply str (take 47 long-text)) "...") (last result)))))))

(deftest format-cell-value-boolean-test
  (testing "format-cell-value for :boolean type"
    (testing "displays green checkmark for true"
      (let [result (fmt/format-cell-value :boolean true {})]
        (is (= [:span.text-green-600 "✓"] result))))

    (testing "displays red X for false"
      (let [result (fmt/format-cell-value :boolean false {})]
        (is (= [:span.text-red-600 "✗"] result))))))

(deftest format-cell-value-number-test
  (testing "format-cell-value for :number type"
    (testing "handles nil value"
      (is (= [:span.text-gray-400 "—"] (fmt/format-cell-value :number nil {}))))

    (testing "displays number as string"
      (is (= [:span "42"] (fmt/format-cell-value :number 42 {})))
      (is (= [:span "3.14"] (fmt/format-cell-value :number 3.14 {}))))))

(deftest format-cell-value-float-test
  (testing "format-cell-value for :float type"
    (testing "handles nil value"
      (is (= [:span.text-gray-400 "—"] (fmt/format-cell-value :float nil {}))))

    (testing "formats float with two decimal places"
      ;; Cast to float because convert-field-value parses to float
      (is (= [:span "42.00"] (fmt/format-cell-value :float (float 42) {})))
      (is (= [:span "3.14"] (fmt/format-cell-value :float (float 3.14) {})))
      (is (= [:span "0.33"] (fmt/format-cell-value :float (float 1/3) {}))))))

(deftest format-cell-value-int-test
  (testing "format-cell-value for :int type"
    (testing "handles nil value"
      (is (= [:span.text-gray-400 "—"] (fmt/format-cell-value :int nil {}))))

    (testing "displays integer as string"
      (is (= [:span "42"] (fmt/format-cell-value :int 42 {})))
      (is (= [:span "0"] (fmt/format-cell-value :int 0 {}))))))

(deftest format-cell-value-instant-test
  (testing "format-cell-value for :instant type"
    (testing "handles nil value"
      (is (= [:span.text-gray-400 "—"]
             (fmt/format-cell-value :instant nil {}))))

    (testing "formats date-time using shared formatter"
      (let [instant (java.time.Instant/now)
            result  (fmt/format-cell-value :instant instant {})]
        (is (= [:span "2023-05-15 14:30"] result))))))

(deftest format-cell-value-single-relationship-test
  (testing "format-cell-value for :single-relationship type"
    (let [mock-db {:mock true}
          uuid    #uuid "11111111-1111-1111-1111-111111111111"]

      (testing "handles nil value"
        (is (= [:span.text-gray-400 "—"]
               (fmt/format-cell-value :single-relationship
                                      nil
                                      {:biff/db mock-db}))))

      (testing "displays label from entity when available"
        (with-redefs [db/get-entity-by-id (constantly {:xt/id      uuid,
                                                      ::sm/type   :user,
                                                      :user/label "John Doe"})]
          (let [result (fmt/format-cell-value :single-relationship
                                              uuid
                                              {:biff/db mock-db})]
            (is (= [:span.text-blue-600 "John Doe"] result)))))

      (testing "displays ID truncated when no label available"
        (with-redefs [db/get-entity-by-id (constantly {:xt/id uuid
                                                      ::sm/type :foo})]
          (let [result (fmt/format-cell-value :single-relationship
                                              uuid
                                              {:biff/db mock-db})]
            (is (= [:span.text-blue-600 "11111111..."] result))))))))

(deftest format-cell-value-many-relationship-test
  (testing "format-cell-value for :many-relationship type"
    (let [mock-db {:mock true}
          uuid1   #uuid "11111111-1111-1111-1111-111111111111"
          uuid2   #uuid "22222222-2222-2222-2222-222222222222"
          uuid3   #uuid "33333333-3333-3333-3333-333333333333"]

      (testing "handles nil value"
        (is (= [:span.text-gray-400 "—"]
               (fmt/format-cell-value :many-relationship
                                      nil
                                      {:biff/db mock-db}))))

      (testing "handles empty set"
        (is
         (= [:span.text-gray-400 "Empty set"]
            (fmt/format-cell-value :many-relationship [] {:biff/db mock-db}))))

      (testing "displays labels with comma separation"
        (let [entities  {uuid1 {:xt/id     uuid1,
                                ::sm/type  :tag,
                                :tag/label "Tag One"},
                         uuid2 {:xt/id     uuid2,
                                ::sm/type  :tag,
                                :tag/label "Tag Two"}}
              entity-fn (fn [_ id] (get entities id))]
          (with-redefs [db/get-entity-by-id entity-fn]
            (let [result (fmt/format-cell-value :many-relationship
                                                [uuid1 uuid2]
                                                {:biff/db mock-db})]
              (is (= :div.text-xs.text-gray-500.mt-1.truncate (first result)))
              (is (= "Tag One, Tag Two" (get-in result [1 :title])))
              (is (= "Tag One, Tag Two" (last result)))))))

      (testing "limits display to two items with ellipsis for more"
        (let [entities  {uuid1 {:xt/id     uuid1,
                                ::sm/type  :tag,
                                :tag/label "Tag One"},
                         uuid2 {:xt/id     uuid2,
                                ::sm/type  :tag,
                                :tag/label "Tag Two"},
                         uuid3 {:xt/id     uuid3,
                                ::sm/type  :tag,
                                :tag/label "Tag Three"}}
              entity-fn (fn [_ id] (get entities id))]
          (with-redefs [db/get-entity-by-id entity-fn]
            (let [result (fmt/format-cell-value :many-relationship
                                                [uuid1 uuid2 uuid3]
                                                {:biff/db mock-db})]
              (is (= :div.text-xs.text-gray-500.mt-1.truncate (first result)))
              (is (= "Tag One, Tag Two, Tag Three" (get-in result [1 :title])))
              (is (= "Tag One, Tag Two ..." (last result))))))))))

(deftest format-cell-value-enum-test
  (testing "format-cell-value for :enum type"
    (testing "handles nil value"
      (is (= [:span.text-gray-400 "—"] (fmt/format-cell-value :enum nil {}))))

    (testing "displays enum value in badge style"
      (let [result (fmt/format-cell-value :enum :admin {})]
        (is
         (=
          :span.bg-purple-100.text-purple-800.text-xs.font-medium.px-2.py-0.5.rounded-full
          (first result)))
        (is (= "admin" (last result)))))))

(deftest format-cell-value-default-test
  (testing "format-cell-value default method"
    (testing "handles nil value"
      (is (= [:span.text-gray-400 "—"]
             (fmt/format-cell-value :unknown nil {}))))

    (testing "displays value as string"
      (let [result (fmt/format-cell-value :unknown "Some value" {})]
        (is (= :span.text-gray-600 (first result)))
        (is (= "Some value" (last result)))))))
