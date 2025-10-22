(ns debug-meditation-fields
  (:require
   [tech.jgood.gleanmo.crud.views :as crud-views]
   [tech.jgood.gleanmo.schema.meditation-schema :as meditation-schema]))

(println "=== Debug Meditation-Log Fields ===")

;; Test get-display-fields with meditation-log schema
(println "\n1. Calling get-display-fields with meditation-log schema:")
(def display-fields (crud-views/get-display-fields meditation-schema/meditation-log))
(println "Number of display fields:" (count display-fields))

;; Look for our priority fields
(println "\n2. Looking for priority fields:")
(def beginning-field (some #(when (= (:field-key %) :meditation-log/beginning) %) display-fields))
(def end-field (some #(when (= (:field-key %) :meditation-log/end) %) display-fields))
(def type-id-field (some #(when (= (:field-key %) :meditation-log/type-id) %) display-fields))

(println "beginning-field:" beginning-field)
(println "end-field:" end-field)
(println "type-id-field:" type-id-field)

;; Check priority values
(println "\n3. Checking priority values:")
(when beginning-field
  (println "beginning priority:" (:crud/priority (:opts beginning-field))))
(when end-field
  (println "end priority:" (:crud/priority (:opts end-field))))
(when type-id-field
  (println "type-id priority:" (:crud/priority (:opts type-id-field)))
  (println "type-id label:" (:crud/label (:opts type-id-field))))

;; Test priority sorting
(println "\n4. Testing priority sorting:")
(def sorted-fields (crud-views/sort-by-priority-then-arbitrary display-fields))
(println "Sorted field order:")
(doseq [field sorted-fields]
  (let [priority (crud-views/get-field-priority field)]
    (println "  " (:field-key field) "(priority:" priority ")")))

(println "\n=== End Debug ===")