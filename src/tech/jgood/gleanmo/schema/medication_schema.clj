(ns tech.jgood.gleanmo.schema.medication-schema
  (:require
   [tech.jgood.gleanmo.schema.meta :as sm]))

(def medication
  (-> [:map {:closed true}
       [:xt/id :medication/id]
       [::sm/type [:enum :medication]]
       [::sm/deleted-at {:optional true} :instant]
       [::sm/created-at :instant]
       [:user/id :user/id]
       [:medication/label {:crud/priority 1} :string]
       [:medication/notes {:optional true, :crud/priority 2} :string]]
      (concat sm/legacy-meta)
      vec))

(def medication-log
  (-> [:map {:closed true}
       [:xt/id :medication-log/id]
       [::sm/type [:enum :medication-log]]
       [::sm/deleted-at {:optional true} :instant]
       [::sm/created-at :instant]
       [:user/id :user/id]
       [:medication-log/timestamp :instant]
       [:medication-log/medication-id
        {:crud/priority 1, :crud/label "Medication"} :medication/id]
       [:medication-log/dosage {:crud/priority 2} :float]
       [:medication-log/unit
        {:crud/priority 3}
        [:enum :mg
         :mcg
         :g
         :ml
         :capsule
         :tablet
         :pill
         :drop
         :sprays
         :units
         :glob
         :patch
         :puff
         :other]]
       [:medication-log/notes {:optional true} :string]
       [:medication-log/injection-site {:optional true}
        [:enum :left-thigh :right-thigh :left-lower-belly :right-lower-belly]]
       ;; Airtable metadata for traceability
       [:airtable/id {:optional true} :string]
       [:airtable/created-time {:optional true} :instant]
       [:airtable/ported-at {:optional true} :instant]]
      (concat sm/legacy-meta)
      vec))
