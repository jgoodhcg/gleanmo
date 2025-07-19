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
       [:medication/label :string]
       [:medication/notes {:optional true} :string]]
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
       [:medication-log/medication-id :medication/id]
       [:medication-log/dosage :float]
       [:medication-log/unit
        [:enum :mg :g :glob :sprays :mcg :capsule]]
       [:medication-log/notes {:optional true} :string]
       [:medication-log/injection-site {:optional true}
        [:enum :left-thigh :right-thigh :left-lower-belly :right-lower-belly]]]
      (concat sm/legacy-meta)
      vec))
