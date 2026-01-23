(ns tech.jgood.gleanmo.test.crud.schema-utils-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [tech.jgood.gleanmo.schema.utils :as schema-utils]))

(deftest parse-field-test
  (testing "parse-field function"
    (testing "parses field without options map"
      (let [field-entry [:user/name :string]
            result      (schema-utils/parse-field field-entry)]
        (is (= :user/name (:field-key result)))
        (is (= {} (:opts result)))
        (is (= :string (:type result)))))

    (testing "parses field with options map"
      (let [field-entry [:user/email {:optional true} :string]
            result      (schema-utils/parse-field field-entry)]
        (is (= :user/email (:field-key result)))
        (is (= {:optional true} (:opts result)))
        (is (= :string (:type result)))))

    (testing "parses field with complex type"
      (let [field-entry [:user/role [:enum :admin :user :guest]]
            result      (schema-utils/parse-field field-entry)]
        (is (= :user/role (:field-key result)))
        (is (= {} (:opts result)))
        (is (= [:enum :admin :user :guest] (:type result)))))

    (testing "parses field with options and complex type"
      (let [field-entry [:user/preferences {:optional true} [:set :string]]
            result      (schema-utils/parse-field field-entry)]
        (is (= :user/preferences (:field-key result)))
        (is (= {:optional true} (:opts result)))
        (is (= [:set :string] (:type result)))))))

(deftest extract-schema-fields-test
  (testing "extract-schema-fields function"
    (testing "extracts fields from schema without options"
      (let [schema [:user
                    [:user/id :uuid]
                    [:user/name :string]
                    [:user/email :string]]
            result (schema-utils/extract-schema-fields schema)]
        (is (= [[:user/id :uuid]
                [:user/name :string]
                [:user/email :string]]
               result))))

    (testing "extracts fields from schema with options"
      (let [schema [:user
                    {:description "User schema"}
                    [:user/id :uuid]
                    [:user/name :string]
                    [:user/email :string]]
            result (schema-utils/extract-schema-fields schema)]
        (is (= [[:user/id :uuid]
                [:user/name :string]
                [:user/email :string]]
               result))))))

(deftest determine-input-type-test
  (testing "determine-input-type function"
    (testing "handles enum types"
      #_{:clj-kondo/ignore [:shadowed-var]}
      (let [type [:enum :admin :user :guest]
            result (schema-utils/determine-input-type type)]
        (is (= :enum (:input-type result)))
        (is (= [:admin :user :guest] (:enum-options result)))
        (is (nil? (:related-entity-str result)))))

    (testing "handles many-relationship types"
      #_{:clj-kondo/ignore [:shadowed-var]}
      (let [type [:set :habit/id]
            result (schema-utils/determine-input-type type)]
        (is (= :many-relationship (:input-type result)))
        (is (nil? (:enum-options result)))
        (is (= "habit" (:related-entity-str result)))))

    (testing "handles single-relationship types"
      #_{:clj-kondo/ignore [:shadowed-var]}
      (let [type :user/id
            result (schema-utils/determine-input-type type)]
        (is (= :single-relationship (:input-type result)))
        (is (nil? (:enum-options result)))
        (is (= "user" (:related-entity-str result)))))

    (testing "handles primitive types"
      #_{:clj-kondo/ignore [:shadowed-var]}
      (let [type :string
            result (schema-utils/determine-input-type type)]
        (is (= :string (:input-type result)))
        (is (nil? (:enum-options result)))
        (is (nil? (:related-entity-str result)))))))

(deftest add-descriptors-test
  (testing "add-descriptors function"
    (testing "adds descriptors for primitive type field"
      (let [field {:field-key :user/name :type :string :opts {}}
            result (schema-utils/add-descriptors field)]
        (is (= :user/name (:field-key result)))
        (is (= :string (:input-type result)))
        (is (nil? (:enum-options result)))
        (is (nil? (:related-entity-str result)))))

    (testing "adds descriptors for enum field"
      (let [field {:field-key :user/role :type [:enum :admin :user] :opts {}}
            result (schema-utils/add-descriptors field)]
        (is (= :user/role (:field-key result)))
        (is (= :enum (:input-type result)))
        (is (= [:admin :user] (:enum-options result)))
        (is (nil? (:related-entity-str result)))))

    (testing "adds descriptors for relationship field"
      (let [field {:field-key :habit/user :type :user/id :opts {}}
            result (schema-utils/add-descriptors field)]
        (is (= :habit/user (:field-key result)))
        (is (= :single-relationship (:input-type result)))
        (is (nil? (:enum-options result)))
        (is (= "user" (:related-entity-str result)))))))

(deftest ns-keyword->input-name-test
  (testing "ns-keyword->input-name function"
    (testing "converts namespaced keyword to input name"
      (is (= "user/name" (schema-utils/ns-keyword->input-name :user/name)))
      (is (= "habit/completed-at" (schema-utils/ns-keyword->input-name :habit/completed-at))))))

(deftest add-input-name-label-test
  (testing "add-input-name-label function"
    (testing "adds input name and label for required field"
      (let [field {:field-key :user/first-name :opts {}}
            result (schema-utils/add-input-name-label field)]
        (is (= "user/first-name" (:input-name result)))
        (is (= "First Name" (:input-label result)))
        (is (true? (:input-required result)))))

    (testing "adds input name and label for optional field"
      (let [field {:field-key :user/middle-name :opts {:optional true}}
            result (schema-utils/add-input-name-label field)]
        (is (= "user/middle-name" (:input-name result)))
        (is (= "Middle Name" (:input-label result)))
        (is (false? (:input-required result)))))

    (testing "uses special label for meditation-log/type-id"
      (let [field {:field-key :meditation-log/type-id :opts {}}
            result (schema-utils/add-input-name-label field)]
        (is (= "meditation-log/type-id" (:input-name result)))
        (is (= "Meditation Type" (:input-label result)))
        (is (true? (:input-required result)))))))

(deftest entity-schema-test
  (testing "entity-schema helper"
    (is (vector? (schema-utils/entity-schema :project-log)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Unknown entity schema"
                          (schema-utils/entity-schema :unknown)))))

(deftest ensure-interval-fields-test
  (testing "ensure-interval-fields validates required keys"
    (let [schema (schema-utils/entity-schema :project-log)]
      (is (= #{:beginning-field :end-field}
             (-> (schema-utils/ensure-interval-fields {:entity-schema schema
                                                       :entity-str "project-log"})
                 keys set)))))

  (testing "missing beginning throws"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"beginning"
                          (schema-utils/ensure-interval-fields
                           {:entity-schema [:test
                                            [:test/end :instant]]
                            :entity-str "test"}))))

  (testing "missing end throws"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"end"
                          (schema-utils/ensure-interval-fields
                           {:entity-schema [:test
                                            [:test/beginning :instant]]
                            :entity-str "test"})))))

(deftest prepare-field-test
  (testing "prepare-field function"
    (testing "prepares field with all transformations"
      (let [field-entry [:user/email {:optional true} :string]
            result (schema-utils/prepare-field field-entry)]
        (is (= :user/email (:field-key result)))
        (is (= {:optional true} (:opts result)))
        (is (= :string (:type result)))
        (is (= :string (:input-type result)))
        (is (= "user/email" (:input-name result)))
        (is (= "Email" (:input-label result)))
        (is (false? (:input-required result)))))

    (testing "prepares relationship field"
      (let [field-entry [:habit/user :user/id]
            result (schema-utils/prepare-field field-entry)]
        (is (= :habit/user (:field-key result)))
        (is (= :user/id (:type result)))
        (is (= :single-relationship (:input-type result)))
        (is (= "user" (:related-entity-str result)))
        (is (= "habit/user" (:input-name result)))
        (is (= "User" (:input-label result)))
        (is (true? (:input-required result)))))

    (testing "preserves crud metadata in options"
      (let [field-entry [:test-field {:crud/priority 1 :crud/label "Custom Label"} :string]
            result (schema-utils/prepare-field field-entry)]
        (is (= :test-field (:field-key result)))
        (is (= {:crud/priority 1 :crud/label "Custom Label"} (:opts result)))
        (is (= 1 (:crud/priority (:opts result))))
        (is (= "Custom Label" (:crud/label (:opts result))))
        (is (= :string (:type result)))
        (is (= "test-field" (:input-name result)))
        (is (= "Custom Label" (:input-label result))) ; Should use crud/label override
        (is (true? (:input-required result)))))))

(deftest should-remove-system-or-user-field?-test
  (testing "should-remove-system-or-user-field? function"
    ;; First, check if the function exists and works properly
    (let [fn-result (schema-utils/should-remove-system-or-user-field? {:field-key :xt/id, :opts {}})]
      (is (not (nil? fn-result)) "Function should return a boolean value, not nil"))

    (testing "identifies xt/id field"
      (is (= true (schema-utils/should-remove-system-or-user-field? {:field-key :xt/id, :opts {}}))))

    (testing "identifies user/id field"
      (is (= true (schema-utils/should-remove-system-or-user-field? {:field-key :user/id, :opts {}}))))

    (testing "identifies schema namespace fields"
      (is (= true (schema-utils/should-remove-system-or-user-field?
                   {:field-key :tech.jgood.gleanmo.schema/created-at, :opts {}}))))

    (testing "identifies schema.meta namespace fields"
      (is (= true (schema-utils/should-remove-system-or-user-field?
                   {:field-key :tech.jgood.gleanmo.schema.meta/version, :opts {}}))))

    (testing "keeps regular fields"
      (is (= false (schema-utils/should-remove-system-or-user-field? {:field-key :user/name, :opts {}})))
      (is (= false (schema-utils/should-remove-system-or-user-field? {:field-key :habit/title, :opts {}}))))

    (testing "identifies airtable namespace fields"
      (is (= true (schema-utils/should-remove-system-or-user-field?
                   {:field-key :airtable/id, :opts {}})))
      (is (= true (schema-utils/should-remove-system-or-user-field?
                   {:field-key :airtable/last-modified, :opts {}}))))

    (testing "identifies fields with hide option"
      (is (= true (schema-utils/should-remove-system-or-user-field?
                   {:field-key :habit/secret-field, :opts {:hide true}})))
      (is (= false (schema-utils/should-remove-system-or-user-field?
                    {:field-key :habit/visible-field, :opts {:hide false}})))
      (is (= false (schema-utils/should-remove-system-or-user-field?
                    {:field-key :habit/normal-field, :opts {}}))))))

(deftest extract-relationship-fields-test
  (testing "extract-relationship-fields function"
    (let [schema [:habit
                  [:xt/id :uuid]
                  [:habit/title :string]
                  [:habit/user :user/id]
                  [:habit/tags [:set :tag/id]]
                  [:tech.jgood.gleanmo.schema/created-at :inst]]]

      (testing "extracts single and many relationships"
        (let [result (schema-utils/extract-relationship-fields schema)]
          (is (= 2 (count result)))
          (is (= :habit/user (:field-key (first result))))
          (is (= :single-relationship (:input-type (first result))))
          (is (= :habit/tags (:field-key (second result))))
          (is (= :many-relationship (:input-type (second result))))))

      (testing "can filter system fields"
        (let [schema-with-system [:habit
                                  [:xt/id :uuid]
                                  [:habit/user :user/id]
                                  [:tech.jgood.gleanmo.schema.meta/entity-type :user/id]]
              result (schema-utils/extract-relationship-fields schema-with-system :remove-system-fields true)]
          (is (= 1 (count result)))
          (is (= :habit/user (:field-key (first result))))
          (is (= :single-relationship (:input-type (first result))))))))

  (deftest get-field-info-test
    (testing "get-field-info function"
      (let [schema [:user
                    [:user/id :uuid]
                    [:user/name :string]
                    [:user/email {:optional true} :string]
                    [:user/role [:enum :admin :user]]]]

        (testing "retrieves field info without options"
          (let [result (schema-utils/get-field-info schema :user/name)]
            (is (= :string (:type result)))
            (is (= {} (:opts result)))))

        (testing "retrieves field info with options"
          (let [result (schema-utils/get-field-info schema :user/email)]
            (is (= :string (:type result)))
            (is (= {:optional true} (:opts result)))))

        (testing "retrieves complex type field info"
          (let [result (schema-utils/get-field-info schema :user/role)]
            (is (= [:enum :admin :user] (:type result)))
            (is (= {} (:opts result)))))

        (testing "returns nil for non-existent fields"
          (is (nil? (schema-utils/get-field-info schema :user/non-existent)))))))

  (deftest crud-priority-metadata-test
    (testing "CRUD priority metadata handling"
      (testing "prepare-field preserves crud priority metadata"
        (let [field-entry [:test-field {:crud/priority 1} :string]
              result (schema-utils/prepare-field field-entry)]
          (is (= 1 (:crud/priority (:opts result))))
          (is (= :test-field (:field-key result)))
          (is (= :string (:type result)))))

      (testing "prepare-field preserves crud label metadata"
        (let [field-entry [:test-field {:crud/label "Custom Label"} :string]
              result (schema-utils/prepare-field field-entry)]
          (is (= "Custom Label" (:crud/label (:opts result))))
          (is (= :test-field (:field-key result)))
          (is (= :string (:type result)))))

      (testing "prepare-field preserves both crud priority and label metadata"
        (let [field-entry [:test-field {:crud/priority 2 :crud/label "Important Field"} :string]
              result (schema-utils/prepare-field field-entry)]
          (is (= 2 (:crud/priority (:opts result))))
          (is (= "Important Field" (:crud/label (:opts result))))
          (is (= :test-field (:field-key result)))
          (is (= :string (:type result)))))

      (testing "prepare-field handles fields without crud metadata"
        (let [field-entry [:test-field {:optional true} :string]
              result (schema-utils/prepare-field field-entry)]
          (is (nil? (:crud/priority (:opts result))))
          (is (nil? (:crud/label (:opts result))))
          (is (= {:optional true} (:opts result)))
          (is (= :test-field (:field-key result)))
          (is (= :string (:type result)))))

      (testing "real schema example - habit schema priority metadata"
        (let [habit-schema (schema-utils/entity-schema :habit)
              fields (map schema-utils/prepare-field
                          (schema-utils/extract-schema-fields habit-schema))
              label-field (some #(= (:field-key %) :habit/label) fields)]
          (is (not (nil? label-field)) "Habit schema should have a label field")
          (is (= 1 (:crud/priority (:opts label-field))) "Habit label should have priority 1")
          (is (= "Habit" (:crud/label (:opts label-field))) "Habit label should have custom label")))

      (testing "real schema example - habit-log schema priority metadata"
        (let [habit-log-schema (schema-utils/entity-schema :habit-log)
              fields (map schema-utils/prepare-field
                          (schema-utils/extract-schema-fields habit-log-schema))
              timestamp-field (some #(= (:field-key %) :habit-log/timestamp) fields)
              habit-ids-field (some #(= (:field-key %) :habit-log/habit-ids) fields)
              notes-field (some #(= (:field-key %) :habit-log/notes) fields)]
          (is (not (nil? timestamp-field)) "Habit-log schema should have timestamp field")
          (is (nil? (:crud/priority (:opts timestamp-field))) "Timestamp should not have priority")

          (is (not (nil? habit-ids-field)) "Habit-log schema should have habit-ids field")
          (is (= 1 (:crud/priority (:opts habit-ids-field))) "Habit-ids should have priority 1")
          (is (= "Habits" (:crud/label (:opts habit-ids-field))) "Habit-ids should have custom label")

          (is (not (nil? notes-field)) "Habit-log schema should have notes field")
          (is (= 2 (:crud/priority (:opts notes-field))) "Notes should have priority 2")
          (is (= "Notes" (:crud/label (:opts notes-field))) "Notes should have custom label")))

      (testing "priority metadata extraction for sorting"
        (let [test-fields [[:field-a {:crud/priority 3} :string]
                           [:field-b {:crud/priority 1} :string]
                           [:field-c {:crud/priority 2} :string]
                           [:field-d {} :string]  ; no priority
                           [:field-e {:optional true} :string]]  ; no priority
              prepared-fields (map schema-utils/prepare-field test-fields)
              priorities (map #(get-in % [:opts :crud/priority]) prepared-fields)]
          (is (= [3 1 2 nil nil] priorities) "Should extract priority values correctly")

        ;; Test that fields without priority return nil (not 99)
          (let [field-without-priority (some #(nil? (get-in % [:opts :crud/priority])) prepared-fields)]
            (is (not (nil? field-without-priority)) "Should have fields without priority metadata")))))))
