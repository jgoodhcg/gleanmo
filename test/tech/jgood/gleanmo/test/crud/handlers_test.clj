(ns tech.jgood.gleanmo.test.crud.handlers-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [tech.jgood.gleanmo.app.shared :as shared]
   [tech.jgood.gleanmo.crud.handlers :as handlers]
   [tech.jgood.gleanmo.db.mutations :as mutations]
   [tech.jgood.gleanmo.db.queries :as db-queries]
   [xtdb.api :as xt]))

;; Test schema to be used across tests
(def test-schema
  [:habit
   [:habit/id :uuid]
   [:habit/label :string]
   [:habit/description {:optional true} :string]
   [:habit/completed :boolean]
   [:habit/optional-flag {:optional true} :boolean]
   [:habit/priority :int]
   [:habit/user :user/id]
   [:habit/tags [:set :tag/id]]
   [:habit/time-zone {:optional true} :string]])

(deftest form->schema-test
  (testing "form->schema function"
    (let [;; Create context (typically contains db and session info)
          ctx {}]

      (testing "handles basic field types correctly"
        (let [form-fields {"habit/label" "Morning Routine",
                           "habit/priority" "5",
                           "habit/completed" "on",
                           "habit/user" "00000000-0000-0000-0000-000000000000"}
              result      (handlers/form->schema form-fields test-schema ctx)]

          (is (= "Morning Routine" (:habit/label result)))
          (is (= 5 (:habit/priority result)))
          (is (true? (:habit/completed result)))
          (is (= #uuid "00000000-0000-0000-0000-000000000000"
                 (:habit/user result)))))

      (testing
       "handles missing non-optional boolean fields by setting them to false"
        (let [form-fields {"habit/label" "Evening Routine"}
              result      (handlers/form->schema form-fields test-schema ctx)]

          (is (= "Evening Routine" (:habit/label result)))
          (is (false? (:habit/completed result)))))

      (testing "skips empty values for optional fields"
        (let [form-fields {"habit/label"         "New Habit",
                           "habit/description"   "",
                           "habit/optional-flag" ""}
              result      (handlers/form->schema form-fields test-schema ctx)]

          (is (= "New Habit" (:habit/label result)))
          (is (not (contains? result :habit/description)))
          (is (not (contains? result :habit/optional-flag)))))

      (testing "handles relationship fields"
        (let [user-id     "00000000-0000-0000-0000-000000000000"
              tag-ids     ["11111111-1111-1111-1111-111111111111"
                           "22222222-2222-2222-2222-222222222222"]
              form-fields {"habit/label" "Exercise Routine",
                           "habit/user"  user-id,
                           "habit/tags"  tag-ids}
              result      (handlers/form->schema form-fields test-schema ctx)]

          (is (= "Exercise Routine" (:habit/label result)))
          (is (= #uuid "00000000-0000-0000-0000-000000000000"
                 (:habit/user result)))
          (is (= #{#uuid "11111111-1111-1111-1111-111111111111"
                   #uuid "22222222-2222-2222-2222-222222222222"}
                 (:habit/tags result)))))

      (testing "removes anti-forgery token"
        (let [form-fields {"habit/label"         "Reading",
                           :__anti-forgery-token "some-token"}
              result      (handlers/form->schema form-fields test-schema ctx)]

          (is (= "Reading" (:habit/label result)))
          (is (not (contains? result :__anti-forgery-token)))))

      (testing "handles fields with conversion errors"
        (let [form-fields {"habit/label"    "Invalid Habit",
                           "habit/priority" "not-a-number"}]

          ;; Should throw exception for invalid int conversion
          (is (thrown?
               clojure.lang.ExceptionInfo
               (handlers/form->schema form-fields test-schema ctx))))))))

(deftest create-entity!-test
  (testing "create-entity! function"
    (let [created-entity-id (atom nil)
          updated-entity-id (atom nil)
          updated-entity-data (atom nil)

          ;; Test data
          user-id     #uuid "12345678-1234-5678-1234-567812345678"
          entity-args {:schema     test-schema,
                       :entity-key :habit,
                       :entity-str "habit"}
          params      {"habit/label"     "Daily Meditation",
                       "habit/priority"  "3",
                       "habit/completed" "true"}
          ctx         {:session {:uid user-id},
                       :biff/db {},
                       :params  params}]

      ;; Setup mocks
      (with-redefs [handlers/form->schema (fn [_ _ _]
                                            {:habit/label "Daily Meditation",
                                             :habit/priority 3,
                                             :habit/completed true})
                    mutations/create-entity!
                    (fn [_ {:keys [_ _]}]
                      (reset! created-entity-id
                              #uuid "98765432-9876-5432-9876-543298765432")
                      @created-entity-id)
                    mutations/update-entity!
                    (fn [_ {:keys [_ entity-id data]}]
                      (reset! updated-entity-id entity-id)
                      (reset! updated-entity-data data)
                      entity-id)
                    shared/get-user-time-zone (constantly "UTC")
                    db-queries/get-entity-by-id (fn [_ id]
                                                 {:xt/id id, :user/email "test@example.com"})]

        (testing "creates entity with user ID and returns redirect"
          (let [result (handlers/create-entity! entity-args ctx)]

            ;; Verify entity was created
            (is (some? @created-entity-id))

            ;; Verify redirect response
            (is (= 303 (:status result)))
            (is (= "/app/crud/form/habit/new"
                   (get-in result [:headers "location"])))))

        (testing "handles time zone updates"
          (let [params-with-tz (assoc params
                                      "habit/time-zone" "America/New_York")
                ctx-with-tz    (assoc ctx :params params-with-tz)
                result         (handlers/create-entity! entity-args
                                                        ctx-with-tz)]

            ;; Verify user time zone was updated
            (is (= user-id @updated-entity-id))
            (is (= {:user/time-zone "America/New_York"} @updated-entity-data))

            ;; Verify redirect response
            (is (= 303 (:status result)))
            (is (= "/app/crud/form/habit/new"
                   (get-in result [:headers "location"])))))))))

(deftest update-entity!-test
  (testing "update-entity! function"
    (let [entity-update-calls (atom [])
          user-update-calls (atom [])
          form-data         (atom nil)

          ;; Test data
          user-id           #uuid "12345678-1234-5678-1234-567812345678"
          entity-id         #uuid "98765432-9876-5432-9876-543298765432"
          entity-args       {:schema     test-schema,
                             :entity-key :habit,
                             :entity-str "habit"}
          params            {"habit/label"     "Updated Meditation",
                             "habit/priority"  "5",
                             "habit/completed" "true"}
          path-params       {:id (str entity-id)}
          ctx               {:session     {:uid user-id},
                             :biff/db     {},
                             :params      params,
                             :path-params path-params}

          ;; Mock entity state
          current-entity    {:xt/id           entity-id,
                             :habit/label     "Daily Meditation",
                             :habit/priority  3,
                             :habit/completed true,
                             :habit/optional-flag true}]

      ;; Setup mocks
      (with-redefs [handlers/form->schema (fn [params _ _]
                                            (reset! form-data params)
                                            {:habit/label "Updated Meditation",
                                             :habit/priority 5,
                                             :habit/completed true})
                    mutations/update-entity!
                    (fn [_ {:keys [entity-key entity-id data]}]
                      (if (= entity-key :habit)
                        (swap! entity-update-calls conj
                               {:entity-id entity-id, :data data})
                        (swap! user-update-calls conj
                               {:entity-id entity-id, :data data}))
                      entity-id)
                    shared/get-user-time-zone (constantly "UTC")
                    db-queries/get-entity-by-id (fn [_ id]
                                                  {:xt/id id, :user/email "test@example.com"})
                    db-queries/get-entity-for-user (fn [_ id _ _]
                                                     (if (= id entity-id)
                                                       current-entity
                                                       nil))]

        (testing "updates entity and returns redirect response"
          (let [result (handlers/update-entity! entity-args ctx)]

            ;; Verify entity was updated
            (is (= 1 (count @entity-update-calls)))
            (let [update-call (first @entity-update-calls)]
              (is (= entity-id (:entity-id update-call)))
              (is (map? (:data update-call))))

            ;; Verify redirect response
            (is (= 303 (:status result)))
            (is (= (str "/app/crud/form/habit/edit/" entity-id)
                   (get-in result [:headers "location"])))))

        (testing "handles optional boolean fields that were unchecked"
          (reset! entity-update-calls [])
          (reset! form-data nil)

          ;; Params without the optional flag (should be set to false)
          (let [params-without-flag {"habit/label"     "Updated Meditation",
                                     "habit/priority"  "5",
                                     "habit/completed" "true"}
                ctx-without-flag (assoc ctx :params params-without-flag)
                result (handlers/update-entity! entity-args ctx-without-flag)]

            ;; Check that the optional flag was explicitly set to false in
            ;; processed params
            (is (= "false" (get @form-data "habit/optional-flag")))

            ;; Verify entity was updated
            (is (= 1 (count @entity-update-calls)))))

        (testing "handles time zone updates"
          (reset! entity-update-calls [])
          (reset! user-update-calls [])

          (let [params-with-tz (assoc params
                                      "habit/time-zone" "America/New_York")
                ctx-with-tz    (assoc ctx :params params-with-tz)
                result         (handlers/update-entity! entity-args
                                                        ctx-with-tz)]

            ;; Verify entity was updated
            (is (= 1 (count @entity-update-calls)))

            ;; Verify user time zone was updated
            (is (= 1 (count @user-update-calls)))
            (let [user-update (first @user-update-calls)]
              (is (= user-id (:entity-id user-update)))
              (is (= {:user/time-zone "America/New_York"} (:data user-update))))

            ;; Verify redirect response
            (is (= 303 (:status result)))
            (is (= (str "/app/crud/form/habit/edit/" entity-id)
                   (get-in result [:headers "location"])))))))))

(deftest delete-entity!-test
  (testing "delete-entity! function"
    (let [deleted-entity-id (atom nil)

          ;; Test data
          user-id           #uuid "12345678-1234-5678-1234-567812345678"
          entity-id         #uuid "98765432-9876-5432-9876-543298765432"
          entity-args       {:entity-key :habit,
                             :entity-str "habit"}
          path-params       {:id (str entity-id)}
          ctx               {:session     {:uid user-id},
                             :biff/db     {},
                             :path-params path-params}

          ;; Entity data for test
          existing-entity   {:xt/id       entity-id,
                             :habit/label "Habit to Delete",
                             :user/id     user-id}]

      ;; Setup mocks
      (with-redefs [db-queries/get-entity-for-user
                    (fn [_ entity-id user-id _]
                        ;; Return entity only if it exists and belongs to
                        ;; user
                      (when (and (= entity-id
                                    #uuid
                                     "98765432-9876-5432-9876-543298765432")
                                 (= user-id
                                    #uuid
                                     "12345678-1234-5678-1234-567812345678"))
                        existing-entity))

                    mutations/soft-delete-entity!
                    (fn [_ {:keys [entity-id]}]
                      (reset! deleted-entity-id entity-id)
                      entity-id)]

        (testing "soft-deletes entity when it exists and belongs to user"
          (let [result (handlers/delete-entity! entity-args ctx)]

            ;; Verify entity was soft-deleted
            (is (= entity-id @deleted-entity-id))

            ;; Verify redirect response
            (is (= 303 (:status result)))
            (is (= "/app/crud/habit" (get-in result [:headers "location"])))))

        (testing "handles non-existent entity gracefully"
          (reset! deleted-entity-id nil)

          ;; Use a different entity ID that doesn't exist
          (let [non-existent-id #uuid "00000000-0000-0000-0000-000000000000"
                ctx-non-existent
                (assoc-in ctx [:path-params :id] (str non-existent-id))
                result (handlers/delete-entity! entity-args ctx-non-existent)]

            ;; Verify no deletion was attempted
            (is (nil? @deleted-entity-id))

            ;; Verify redirect response is still the same
            (is (= 303 (:status result)))
            (is (= "/app/crud/habit" (get-in result [:headers "location"])))))

        (testing "handles entity belonging to different user"
          (reset! deleted-entity-id nil)

          ;; Use a different user ID
          (let [different-user-id #uuid "22222222-2222-2222-2222-222222222222"
                ctx-wrong-user (assoc-in ctx [:session :uid] different-user-id)
                result         (handlers/delete-entity! entity-args
                                                        ctx-wrong-user)]

            ;; Verify no deletion was attempted
            (is (nil? @deleted-entity-id))

            ;; Verify redirect response is still the same
            (is (= 303 (:status result)))
            (is (= "/app/crud/habit"
                   (get-in result [:headers "location"])))))))))
