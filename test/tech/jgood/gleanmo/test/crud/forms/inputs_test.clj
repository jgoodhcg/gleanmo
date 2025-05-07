(ns tech.jgood.gleanmo.test.crud.forms.inputs-test
  (:require
   [clojure.pprint :refer [pprint]]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [potpuri.core :as pot]
   [tech.jgood.gleanmo.app.shared :as shared]
   [tech.jgood.gleanmo.crud.forms.inputs :as inputs]
   [tech.jgood.gleanmo.db.queries :as db-queries]
   [tech.jgood.gleanmo.schema :as schema]))

;; Test utility to extract specific attributes from Hiccup forms
(defn get-attr
  [hiccup attr]
  (when (and (vector? hiccup) (map? (second hiccup)))
    (get (second hiccup) attr)))

(defn find-element
  "Find an element with the specified tag in a Hiccup structure.
   Searches recursively through nested elements."
  [hiccup tag]
  (cond
    ;; Direct match at current level (vector)
    (and (vector? hiccup)
         (= tag
            (-> hiccup
                first
                str
                (str/split #"\.")
                first
                (str/split #":")
                last
                keyword)))
    hiccup

    ;; Not a vector or sequence, can't contain tags
    (not (or (vector? hiccup) (seq? hiccup)))
    nil

    ;; If it's a list, recursively search through all elements in the list
    (seq? hiccup)
    (some #(find-element % tag) hiccup)

    ;; For vectors that are not a match, search its children
    :else
    (when (vector? hiccup)
        ;; Look at each child of the vector
      (some #(when (or (vector? %) (seq? %))
               (find-element % tag))
            hiccup))))

;; Set up default mocks for all tests
(defn test-fixtures
  [f]
  (with-redefs [db-queries/all-for-user-query (constantly [])
                shared/get-user-time-zone     (constantly "UTC")]
    (f)))

(use-fixtures :each test-fixtures)

(deftest render-string-test
  (testing "render string field"
    (let [ctx   {:biff/db {}}
          field {:input-name     "user/name",
                 :input-label    "Name",
                 :input-required true}]

      (testing "renders basic text field"
        (let [result (inputs/render (assoc field :input-type :string) ctx)
              input  (find-element result :textarea)]
          (is (vector? result))
          (is (= :div (first result)))
          (is (some? input))
          (is (= "user/name" (get-attr input :name)))
          (is (true? (get-attr input :required)))))

      (testing "includes value when provided"
        (let [result (inputs/render (assoc field
                                           :input-type :string
                                           :value      "Test User")
                                    ctx)
              input  (find-element result :textarea)]
          (is (= "Test User" (get-attr input :value)))))

      (testing "renders label field with input"
        (let [label-field (assoc field :input-name "habit/label")
              result      (inputs/render (assoc label-field :input-type :string)
                                         ctx)
              textarea    (find-element result :input)]
          (is (some? textarea))
          (is (= "habit/label" (get-attr textarea :name)))))

      (testing "renders time zone field with select"
        (let [tz-field (assoc field :input-name "user/time-zone")
              result   (inputs/render (assoc tz-field :input-type :string) ctx)
              select   (find-element result :select)
              option   (find-element select :option)]
          (is (some? select))
          (is (= "user/time-zone" (get-attr select :name)))
          ;; Find an option element within the select
          (is (some? option)))))))

(deftest render-boolean-test
  (testing "render boolean field"
    (let [ctx   {:biff/db {}}
          field {:input-name  "habit/completed",
                 :input-label "Completed",
                 :input-type  :boolean}]

      (testing "renders checkbox"
        (let [result (inputs/render field ctx)
              input  (find-element result :input)]
          (is (vector? result))
          (is (= :div.flex.items-center (first result)))
          (is (some? input))
          (is (= "checkbox" (get-attr input :type)))
          (is (= "habit/completed" (get-attr input :name)))))

      (testing "checked when value is true"
        (let [result (inputs/render (assoc field :value true) ctx)
              input  (find-element result :input)]
          (is (= "checked" (get-attr input :checked)))))

      (testing "not checked when value is false"
        (let [result (inputs/render (assoc field :value false) ctx)
              input  (find-element result :input)]
          (is (nil? (get-attr input :checked))))))))

(deftest render-number-test
  (testing "render number field"
    (let [ctx   {:biff/db {}}
          field {:input-name     "habit/priority",
                 :input-label    "Priority",
                 :input-required true,
                 :input-type     :number}]

      (testing "renders number input"
        (let [result (inputs/render field ctx)
              input  (find-element result :input)]
          (is (vector? result))
          (is (= :div (first result)))
          (is (some? input))
          (is (= "number" (get-attr input :type)))
          (is (= "any" (get-attr input :step)))
          (is (= "habit/priority" (get-attr input :name)))
          (is (true? (get-attr input :required)))))

      (testing "includes value when provided"
        (let [result (inputs/render (assoc field :value 5) ctx)
              input  (find-element result :input)]
          (is (= 5 (get-attr input :value))))))))

(deftest render-int-test
  (testing "render int field"
    (let [ctx   {:biff/db {}}
          field {:input-name     "habit/priority",
                 :input-label    "Priority",
                 :input-required true,
                 :input-type     :int}]

      (testing "renders number input with step=1"
        (let [result (inputs/render field ctx)
              input  (find-element result :input)]
          (is (vector? result))
          (is (= :div (first result)))
          (is (some? input))
          (is (= "number" (get-attr input :type)))
          (is (= "1" (get-attr input :step)))
          (is (= "habit/priority" (get-attr input :name)))
          (is (true? (get-attr input :required))))))))

(deftest render-float-test
  (testing "render float field"
    (let [ctx   {:biff/db {}}
          field {:input-name     "habit/rating",
                 :input-label    "Rating",
                 :input-required true,
                 :input-type     :float}]

      (testing "renders number input with step=0.001"
        (let [result (inputs/render field ctx)
              input  (find-element result :input)]
          (is (vector? result))
          (is (= :div (first result)))
          (is (some? input))
          (is (= "number" (get-attr input :type)))
          (is (= "0.001" (get-attr input :step)))
          (is (= "habit/rating" (get-attr input :name)))
          (is (true? (get-attr input :required))))))))

(deftest render-instant-test
  (testing "render instant field"
    (let [ctx   {:biff/db {}}
          field {:input-name     "habit/due-date",
                 :input-label    "Due Date",
                 :input-required true,
                 :input-type     :instant}]

      (testing "renders datetime-local input"
        (with-redefs [shared/get-user-time-zone     (constantly "UTC")
                      shared/format-date-time-local (constantly
                                                     "2023-05-15T14:30")]
          (let [result (inputs/render field ctx)
                input  (find-element result :input)]
            (is (vector? result))
            (is (= :div (first result)))
            (is (some? input))
            (is (= "datetime-local" (get-attr input :type)))
            (is (= "habit/due-date" (get-attr input :name)))
            (is (= "2023-05-15T14:30" (get-attr input :value)))
            (is (true? (get-attr input :required)))))))))

(deftest render-single-relationship-test
  (testing "render single-relationship field"
    (let [ctx   {:biff/db {}}
          field {:input-name         "habit/user",
                 :input-label        "User",
                 :input-required     true,
                 :input-type         :single-relationship,
                 :related-entity-str "user"}
          user1 {:xt/id      #uuid "11111111-1111-1111-1111-111111111111",
                 :user/label "User One"}
          user2 {:xt/id      #uuid "22222222-2222-2222-2222-222222222222",
                 :user/label "User Two"}]

      (testing "renders select with options"
        (with-redefs [db-queries/all-for-user-query (constantly [user1 user2])
                      schema/schema {:user [:user]}]
          (let [result  (inputs/render field ctx)
                select  (find-element result :select)
                ;; assume options vec is third element after keyword and
                ;; map of element attributes
                options (-> select
                            (nth 2))]
            (is (vector? result))
            (is (= :div (first result)))
            (is (some? select))
            (is (= "habit/user" (get-attr select :name)))
            (is (true? (get-attr select :required)))
            (is (= 2 (count options)))))))))

(deftest render-many-relationship-test
  (testing "render many-relationship field"
    (let [ctx   {:biff/db {}}
          field {:input-name         "habit/tags",
                 :input-label        "Tags",
                 :input-required     false,
                 :input-type         :many-relationship,
                 :related-entity-str "tag"}
          tag1  {:xt/id     #uuid "11111111-1111-1111-1111-111111111111",
                 :tag/label "Tag One"}
          tag2  {:xt/id     #uuid "22222222-2222-2222-2222-222222222222",
                 :tag/label "Tag Two"}]

      (testing "renders multi-select with options"
        (with-redefs [db-queries/all-for-user-query (constantly [tag1 tag2])
                      schema/schema {:tag [:tag]}]
          (let [result  (inputs/render field ctx)
                select  (find-element result :select)
                ;; assume options vec is third element after keyword and
                ;; map of element attributes
                options (-> select
                            (nth 2))]
            (is (vector? result))
            (is (= :div (first result)))
            (is (some? select))
            (is (= "habit/tags" (get-attr select :name)))
            (is (true? (get-attr select :multiple)))
            (is (= 2 (count options)))))))))

(deftest render-enum-test
  (testing "render enum field"
    (let [ctx   {:biff/db {}}
          field {:input-name     "user/role",
                 :input-label    "Role",
                 :input-required true,
                 :input-type     :enum,
                 :enum-options   [:admin :user :guest]}]

      (testing "renders select with enum options"
        (let [result  (inputs/render field ctx)
              select  (find-element result :select)
              ;; assume options vec is third element after keyword and map
              options (-> select
                          (nth 2))]
          (is (vector? result))
          (is (= :div (first result)))
          (is (some? select))
          (is (= "user/role" (get-attr select :name)))
          (is (true? (get-attr select :required)))
          (is (= 3 (count options)))))

      (testing "selects current value when provided"
        (let [result       (inputs/render (assoc field :value :admin) ctx)
              select       (find-element result :select)
              admin-option (->> (nth select 2)
                                (filter #(and (= :option (first %))
                                              (= "admin" (get-attr % :value))))
                                first)]
          (pprint (pot/map-of admin-option))
          (is (some? admin-option))
          (is (true? (get-attr admin-option :selected))))))))

(deftest render-default-test
  (testing "render default for unknown type"
    (let [ctx   {:biff/db {}}
          field {:input-name  "user/unknown",
                 :input-label "Unknown",
                 :input-type  :unknown-type}]

      (testing "renders fallback message"
        (let [result (inputs/render field ctx)]
          (is (vector? result))
          (is (= :div (first result)))
          (is (string? (second result)))
          (is (re-find #"Unsupported field type" (second result))))))))
