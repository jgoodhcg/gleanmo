(ns tech.jgood.gleanmo.test.crud.forms-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [tech.jgood.gleanmo.crud.forms :as forms]))

(deftest prepare-form-fields-test
  (testing "prepare-form-fields function"
    (testing "extracts and prepares fields from schema"
      (let [schema [:user
                    [:xt/id :uuid] ; system field, should be removed
                    [:user/id :uuid] ; user ID field, should be removed
                    [:user/name :string] ; regular field, should be kept
                    [:user/email {:optional true} :string] ; optional field,
                                                           ; should be kept
                    [:user/role [:enum :admin :user]] ; enum field, should
                                                      ; be kept
                    [:tech.jgood.gleanmo.schema/created-at :inst]] ; schema
                                                                   ; field,
                                                                   ; should be
                                                                   ; removed
            result (forms/prepare-form-fields schema)]

        ;; Should have 3 fields (system fields removed)
        (is (= 3 (count result)))

        ;; Check that user/name field is processed correctly
        (let [name-field (first (filter #(= :user/name (:field-key %)) result))]
          (is (some? name-field))
          (is (= "user/name" (:input-name name-field)))
          (is (= "Name" (:input-label name-field)))
          (is (true? (:input-required name-field)))
          (is (= :string (:input-type name-field))))

        ;; Check that user/email field is processed correctly with options
        (let [email-field (first (filter #(= :user/email (:field-key %))
                                         result))]
          (is (some? email-field))
          (is (= "user/email" (:input-name email-field)))
          (is (= "Email" (:input-label email-field)))
          (is (false? (:input-required email-field))) ; Optional field
          (is (= :string (:input-type email-field))))

        ;; Check that user/role enum field is processed correctly
        (let [role-field (first (filter #(= :user/role (:field-key %)) result))]
          (is (some? role-field))
          (is (= "user/role" (:input-name role-field)))
          (is (= "Role" (:input-label role-field)))
          (is (true? (:input-required role-field)))
          (is (= :enum (:input-type role-field)))
          (is (= [:admin :user] (:enum-options role-field))))

        ;; Verify system fields are not present
        (is (nil? (first (filter #(= :xt/id (:field-key %)) result))))
        (is (nil? (first (filter #(= :user/id (:field-key %)) result))))
        (is (nil? (first (filter #(= :tech.jgood.gleanmo.schema/created-at
                                     (:field-key %))
                                 result))))))

    (testing "handles empty schema"
      (let [schema [:empty]
            result (forms/prepare-form-fields schema)]
        (is (empty? result))))

    (testing "handles schema with only system fields"
      (let [schema [:system-only
                    [:xt/id :uuid]
                    [:user/id :uuid]
                    [:tech.jgood.gleanmo.schema/created-at :inst]]
            result (forms/prepare-form-fields schema)]
        (is (empty? result))))

    (testing "includes relationship fields"
      (let [schema [:habit
                    [:habit/title :string]
                    [:habit/user :user/id] ; single relationship
                    [:habit/tags [:set :tag/id]]] ; many relationship
            result (forms/prepare-form-fields schema)]

        ;; Should have 3 fields
        (is (= 3 (count result)))

        ;; Check single relationship field
        (let [user-field (first (filter #(= :habit/user (:field-key %))
                                        result))]
          (is (some? user-field))
          (is (= :single-relationship (:input-type user-field)))
          (is (= "user" (:related-entity-str user-field))))

        ;; Check many relationship field
        (let [tags-field (first (filter #(= :habit/tags (:field-key %))
                                        result))]
          (is (some? tags-field))
          (is (= :many-relationship (:input-type tags-field)))
          (is (= "tag" (:related-entity-str tags-field))))))))
