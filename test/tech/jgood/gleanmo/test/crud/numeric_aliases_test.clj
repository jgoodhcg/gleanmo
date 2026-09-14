(ns tech.jgood.gleanmo.test.crud.numeric-aliases-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [com.biffweb :refer [test-xtdb-node]]
   [malli.core :as malli]
   [tech.jgood.gleanmo :as main]
   [tech.jgood.gleanmo.db.mutations :as mutations]
   [tech.jgood.gleanmo.schema :as schema-registry]
   [xtdb.api :as xt]
   [tech.jgood.gleanmo.crud.forms.converters :as converters]
   [tech.jgood.gleanmo.crud.forms.inputs :as inputs]
   [tech.jgood.gleanmo.crud.handlers :as handlers]
   [tech.jgood.gleanmo.crud.views.formatting :as fmt]
   [tech.jgood.gleanmo.duration :as duration]
   [tech.jgood.gleanmo.schema.utils :as schema-utils]))

(deftest alias-validation-test
  (testing "positive-int rejects zero, negatives, and fractions"
    (is (malli/validate :positive-int 1 main/malli-opts))
    (is (not (malli/validate :positive-int 0 main/malli-opts)))
    (is (not (malli/validate :positive-int -3 main/malli-opts)))
    (is (not (malli/validate :positive-int 1.5 main/malli-opts))))
  (testing "nonnegative-int accepts zero and rejects negatives and fractions"
    (is (malli/validate :nonnegative-int 0 main/malli-opts))
    (is (malli/validate :nonnegative-int 12 main/malli-opts))
    (is (not (malli/validate :nonnegative-int -1 main/malli-opts)))
    (is (not (malli/validate :nonnegative-int 0.5 main/malli-opts))))
  (testing "existing documents without the new fields still validate"
    (is (malli/validate :book
                        {:xt/id                               (random-uuid)
                         :tech.jgood.gleanmo.schema.meta/type :book
                         :tech.jgood.gleanmo.schema.meta/created-at
                         (java.time.Instant/now)
                         :user/id                             (random-uuid)
                         :book/title                          "Old book"
                         :book/formats                        #{:paperback}}
                        main/malli-opts))))

(deftest input-type-test
  (testing "aliases keep their identity and minimum"
    (is (= {:input-type :positive-int, :input-min 1}
           (select-keys (schema-utils/determine-input-type :positive-int)
                        [:input-type :input-min])))
    (is (= 0 (:input-min (schema-utils/determine-input-type :nonnegative-int)))))
  (testing "plain ints, enums, and relationship sets are unchanged"
    (is (= :int (:input-type (schema-utils/determine-input-type :int))))
    (is (= :enum (:input-type (schema-utils/determine-input-type [:enum :a :b]))))
    (is (= {:input-type :many-relationship, :related-entity-str "book"}
           (select-keys (schema-utils/determine-input-type [:set :book/id])
                        [:input-type :related-entity-str]))))
  (testing ":crud/duration-format :hms selects the duration input"
    (is (= :hms-duration
           (:input-type (schema-utils/input-type-info
                         :positive-int {:crud/duration-format :hms}))))
    (is (= :number
           (:input-type (schema-utils/input-type-info
                         :number {:crud/duration-format :hms}))))))

(deftest hms-test
  (testing "whole seconds and H:MM:SS round-trip, including hours above 23"
    (is (= 5400 (duration/parse-hms "5400")))
    (is (= 5400 (duration/parse-hms "1:30:00")))
    (is (= "1:30:00" (duration/format-hms 5400)))
    (is (= "36:00:05" (duration/format-hms (duration/parse-hms "36:00:05"))))
    (is (= 0 (duration/parse-hms "0:00:00"))))
  (testing "malformed and fractional input is rejected"
    (doseq [bad ["1:5:00" "1:60:00" "1:00:60" "1.5" "-5" "abc" "1:00"]]
      (is (nil? (duration/parse-hms bad)) bad))))

(deftest convert-test
  (is (= 0 (converters/convert-field-value :nonnegative-int "0" nil)))
  (is (= 7 (converters/convert-field-value :positive-int "7" nil)))
  (is (= 3723 (converters/convert-field-value :hms-duration "1:02:03" nil)))
  (is (thrown? clojure.lang.ExceptionInfo
               (converters/convert-field-value :hms-duration "1:99:00" nil)))
  (is (thrown? clojure.lang.ExceptionInfo
               (converters/convert-field-value :positive-int "2.5" nil))))

(deftest render-and-format-test
  (testing "alias inputs reuse the integer input with a minimum"
    (let [input (get-in (inputs/render {:input-type     :positive-int
                                        :input-min      1
                                        :input-name     "book/total-pages"
                                        :input-label    "Total pages"
                                        :input-required false
                                        :value          300}
                                       {})
                        [2 1 1])]
      (is (= "1" (:min input)))
      (is (= "1" (:step input)))
      (is (= "300" (:data-original-value input)))))
  (testing "duration inputs show and remember H:MM:SS"
    (let [input (get-in (inputs/render {:input-type     :hms-duration
                                        :input-name     "book/audiobook-duration-seconds"
                                        :input-label    "Audiobook duration"
                                        :input-required false
                                        :value          25200}
                                       {})
                        [2 1 1])]
      (is (= "7:00:00" (:value input)))
      (is (= "7:00:00" (:data-original-value input)))))
  (testing "list formatting"
    (is (= [:span "0"] (fmt/format-cell-value :nonnegative-int 0 {})))
    (is (= [:span.text-gray-400 "—"] (fmt/format-cell-value :positive-int nil {})))
    (is (= [:span.tabular-nums "7:00:00"]
           (fmt/format-cell-value :hms-duration 25200 {})))))

(def ^:private test-schema
  [:map {:closed true}
   [:xt/id :book/id]
   [:book/title :string]
   [:book/total-pages {:optional true} :positive-int]
   [:book/audiobook-duration-seconds
    {:optional true, :crud/duration-format :hms} :positive-int]
   [:book/notes {:optional true} :string]])

(deftest form-conversion-test
  (testing "blank optional aliases stay absent and zero is preserved"
    (let [result (handlers/form->schema {"book/title"       "T"
                                         "book/total-pages" ""
                                         "book/audiobook-duration-seconds"
                                         "2:00:00"}
                                        test-schema
                                        {})]
      (is (not (contains? result :book/total-pages)))
      (is (= 7200 (:book/audiobook-duration-seconds result))))))

(deftest update-handler-clears-blank-positions-test
  (with-open [node (test-xtdb-node [])]
    (let [ctx     {:biff.xtdb/node node, :biff/malli-opts #'main/malli-opts}
          user    (random-uuid)
          book    (mutations/create-entity! (assoc ctx :biff/db (xt/db node))
                                            {:entity-key :book
                                             :data {:user/id user :book/title "B"}})
          log-id  (mutations/create-entity!
                   (assoc ctx :biff/db (xt/db node))
                   {:entity-key :reading-log
                    :data {:user/id user :reading-log/book-id book
                           :reading-log/beginning (java.time.Instant/parse "2026-07-01T10:00:00Z")
                           :reading-log/time-zone "UTC"
                           :reading-log/start-page 0 :reading-log/end-page 42}})]
      (handlers/update-entity!
       {:schema     (:reading-log schema-registry/schema)
        :entity-key :reading-log
        :entity-str "reading-log"}
       (assoc ctx
              :biff/db     (xt/db node)
              :session     {:uid user}
              :path-params {:id (str log-id)}
              :headers     {}
              :params      {"reading-log/book-id"    (str book)
                            "reading-log/end-page"   ""
                            "reading-log/start-page" "0"}))
      (let [stored (xt/entity (xt/db node) log-id)]
        (is (not (contains? stored :reading-log/end-page)))
        (is (= 0 (:reading-log/start-page stored)) "zero survives the edit")))))

(deftest cleared-fields-test
  (let [stored {:book/title "T", :book/total-pages 300,
                :book/audiobook-duration-seconds 60, :book/notes "n"}]
    (testing "an explicit blank clears a stored alias value"
      (is (= {:book/total-pages :db/dissoc}
             (handlers/cleared-fields test-schema
                                      {"book/total-pages" ""
                                       "book/audiobook-duration-seconds" "0:01:00"
                                       "book/notes" ""}
                                      stored))))
    (testing "fields absent from the form are untouched"
      (is (= {} (handlers/cleared-fields test-schema {"book/title" "T"} stored))))
    (testing "keyword param keys are recognized"
      (is (= {:book/audiobook-duration-seconds :db/dissoc}
             (handlers/cleared-fields test-schema
                                      {:book/audiobook-duration-seconds ""}
                                      stored))))))
