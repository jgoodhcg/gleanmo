(ns tech.jgood.gleanmo.test.crud.views-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [clojure.core :refer [nth]]
   [tech.jgood.gleanmo.crud.views :as crud-views]
   [tech.jgood.gleanmo.schema.meditation-schema :as meditation-schema]
   [tech.jgood.gleanmo.schema.habit-schema :as habit-schema]
   [tech.jgood.gleanmo.schema.project-schema :as project-schema]))

(deftest get-field-priority-test
  (testing "get-field-priority function"
    (testing "returns priority when specified"
      (let [field {:field-key :test-field :opts {:crud/priority 1}}]
        (is (= 1 (crud-views/get-field-priority field)))))
    
    (testing "returns 99 when no priority specified"
      (let [field {:field-key :test-field :opts {}}]
        (is (= 99 (crud-views/get-field-priority field)))))
    
    (testing "returns 99 when opts is nil"
      (let [field {:field-key :test-field :opts nil}]
        (is (= 99 (crud-views/get-field-priority field)))))))

(deftest sort-by-priority-then-arbitrary-test
  (testing "sort-by-priority-then-arbitrary function"
    (testing "sorts fields by priority correctly"
      (let [fields [{:field-key :low-priority :opts {:crud/priority 3}}
                   {:field-key :high-priority :opts {:crud/priority 1}}
                   {:field-key :medium-priority :opts {:crud/priority 2}}
                   {:field-key :no-priority :opts {}}]
            result (crud-views/sort-by-priority-then-arbitrary fields)]
        (is (= :high-priority (:field-key (first result))))
        (is (= :medium-priority (:field-key (second result))))
        (is (= :low-priority (:field-key (nth result 2))))
        (is (= :no-priority (:field-key (nth result 3))))))
    
    (testing "handles fields with same priority"
      (let [fields [{:field-key :field-a :opts {:crud/priority 1}}
                   {:field-key :field-b :opts {:crud/priority 1}}
                   {:field-key :no-priority :opts {}}]
            result (crud-views/sort-by-priority-then-arbitrary fields)]
        ;; Fields with same priority should maintain relative order
        (is (= #{:field-a :field-b} (set (map :field-key (take 2 result)))))
        (is (= :no-priority (:field-key (last result))))))))

(deftest get-display-fields-test
  (testing "get-display-fields function"
    (testing "extracts and prepares fields from meditation-log schema"
      (let [display-fields (crud-views/get-display-fields meditation-schema/meditation-log)]
        ;; Should have fields (excluding system fields)
        (is (< 0 (count display-fields)))
        
        ;; Check that priority metadata is preserved
        (let [beginning-field (some #(when (= (:field-key %) :meditation-log/beginning) %) display-fields)
              end-field (some #(when (= (:field-key %) :meditation-log/end) %) display-fields)
              type-id-field (some #(when (= (:field-key %) :meditation-log/type-id) %) display-fields)]
          
          (is (some? beginning-field) "Should have beginning field")
          (is (some? end-field) "Should have end field") 
          (is (some? type-id-field) "Should have type-id field")
          
          ;; Check priority values
          (when beginning-field
            (is (= 1 (:crud/priority (:opts beginning-field))) "Beginning should have priority 1"))
          (when end-field
            (is (= 2 (:crud/priority (:opts end-field))) "End should have priority 2"))
           (when type-id-field
             (is (= 3 (:crud/priority (:opts type-id-field))) "Type-id should have priority 3")
             (is (= "Meditation" (:crud/label (:opts type-id-field))) "Type-id should have custom label")))))))

(deftest priority-metadata-integration-test
  (testing "Priority metadata integration with real schemas"
    (testing "habit-log schema priority sorting"
      (let [display-fields (crud-views/get-display-fields habit-schema/habit-log)
            sorted-fields (crud-views/sort-by-priority-then-arbitrary display-fields)
            priority-fields (take-while #(< (crud-views/get-field-priority %) 99) sorted-fields)]
        
        ;; Should have priority fields
        (is (> (count priority-fields) 0) "Should have fields with explicit priority")
        
        ;; Check that priority 1 fields come first
        (let [priority-1-fields (filter #(= 1 (crud-views/get-field-priority %)) priority-fields)]
          (is (> (count priority-1-fields) 0) "Should have priority 1 fields")
          
          ;; Verify specific priority 1 fields
          (let [timestamp-field (first (filter #(= (:field-key %) :habit-log/timestamp) priority-1-fields))
                habit-ids-field (first (filter #(= (:field-key %) :habit-log/habit-ids) priority-1-fields))]
            (is (nil? timestamp-field) "Timestamp priority now 2 (schema)")
            (is (some? habit-ids-field) "Habit-ids should be priority 1")
            (is (= "Habits" (:input-label habit-ids-field)) "Habit-ids should have custom label")))
        
        ;; Check priority 2 fields
        (let [priority-2-fields (filter #(= 2 (crud-views/get-field-priority %)) sorted-fields)]
          (when (> (count priority-2-fields) 0)
            (let [timestamp-field (first (filter #(= (:field-key %) :habit-log/timestamp) priority-2-fields))
                  notes-field     (first (filter #(= (:field-key %) :habit-log/notes) priority-2-fields))]
              (is (some? timestamp-field) "Timestamp should be priority 2")
              (is (nil? notes-field) "Notes priority now 3 (schema)"))))

        ;; Check priority 3 fields
        (let [priority-3-fields (filter #(= 3 (crud-views/get-field-priority %)) sorted-fields)]
          (when (> (count priority-3-fields) 0)
            (let [notes-field (first (filter #(= (:field-key %) :habit-log/notes) priority-3-fields))]
              (is (some? notes-field) "Notes should be priority 3")
              (is (= "Notes" (:input-label notes-field)) "Notes should have custom label"))))))
    
    (testing "meditation-log schema priority sorting"
      (let [display-fields (crud-views/get-display-fields meditation-schema/meditation-log)
            sorted-fields (crud-views/sort-by-priority-then-arbitrary display-fields)]
        
        ;; Should have beginning, end, and type-id fields with priorities 1, 2, 3
        (let [beginning-field (first (filter #(= (:field-key %) :meditation-log/beginning) sorted-fields))
              end-field (first (filter #(= (:field-key %) :meditation-log/end) sorted-fields))
              type-id-field (first (filter #(= (:field-key %) :meditation-log/type-id) sorted-fields))]
          
          (is (some? beginning-field) "Should have beginning field")
          (is (some? end-field) "Should have end field")
          (is (some? type-id-field) "Should have type-id field")
          
          ;; Verify priority order in the sorted result
          (let [field-order (map :field-key sorted-fields)
                beginning-idx (.indexOf field-order :meditation-log/beginning)
                end-idx (.indexOf field-order :meditation-log/end)
                type-id-idx (.indexOf field-order :meditation-log/type-id)]
            
            (is (< beginning-idx end-idx) "Beginning (priority 1) should come before end (priority 2)")
            (is (< end-idx type-id-idx) "End (priority 2) should come before type-id (priority 3)")
            
            ;; Verify priority values using the get-field-priority function
            (is (= 1 (crud-views/get-field-priority beginning-field)) "Beginning should have priority 1")
            (is (= 2 (crud-views/get-field-priority end-field)) "End should have priority 2")
            (is (= 3 (crud-views/get-field-priority type-id-field)) "Type-id should have priority 3")
            (is (= "Meditation" (:input-label type-id-field)) "Type-id should have custom label")))))
    
    (testing "fields without priority appear after priority fields"
      (let [display-fields (crud-views/get-display-fields project-schema/project-log)
            sorted-fields (crud-views/sort-by-priority-then-arbitrary display-fields)
            priority-fields (filter #(< (crud-views/get-field-priority %) 99) sorted-fields)
            non-priority-fields (filter #(>= (crud-views/get-field-priority %) 99) sorted-fields)]
        
        ;; Should have both priority and non-priority fields
        (is (> (count priority-fields) 0) "Should have priority fields")
        (is (> (count non-priority-fields) 0) "Should have non-priority fields")
        
        ;; All priority fields should come before non-priority fields
        (let [priority-indices (map #(.indexOf (map :field-key sorted-fields) (:field-key %)) priority-fields)
              non-priority-indices (map #(.indexOf (map :field-key sorted-fields) (:field-key %)) non-priority-fields)]
          (is (every? #(< % (apply max non-priority-indices)) priority-indices) 
              "All priority fields should come before non-priority fields"))))))
