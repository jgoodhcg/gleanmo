(ns tech.jgood.gleanmo.schema.bouldering-schema
  (:require
   [tech.jgood.gleanmo.schema.meta :as sm]))

(def rpe-enum
  [:enum :easy :moderate :hard :limit])

(def grade-enum
  [:enum :v0 :v1 :v2 :v3 :v4 :v5 :v6 :v7 :v8 :v9 :v10 :v11 :v12 :v13 :project])

(def boulder-session
  (-> [:map {:closed true}
       [:xt/id :boulder-session/id]
       [::sm/type [:enum :boulder-session]]
       [::sm/deleted-at {:optional true} :instant]
       [::sm/created-at :instant]
       [:user/id :user/id]
       [:boulder-session/label {:optional true} :string]
       [:boulder-session/beginning :instant]
       [:boulder-session/end {:optional true} :instant]
       [:boulder-session/gym {:crud/priority 1} :string]
       [:boulder-session/rpe {:optional true :crud/priority 2} rpe-enum]
       [:boulder-session/notes {:optional true :crud/priority 3} :string]
       [:airtable/id {:optional true} :string]
       [:airtable/created-time {:optional true} :instant]
       [:airtable/ported-at {:optional true} :instant]]
      (concat sm/legacy-meta)
      vec))

(def boulder-attempt
  (-> [:map {:closed true}
       [:xt/id :boulder-attempt/id]
       [::sm/type [:enum :boulder-attempt]]
       [::sm/deleted-at {:optional true} :instant]
       [::sm/created-at :instant]
       [:user/id :user/id]
       [:boulder-attempt/session-id
        {:crud/priority 1 :crud/label "Session" :crud/inline-create true}
        :boulder-session/id]
       [:boulder-attempt/grade {:crud/priority 2} grade-enum]
       [:boulder-attempt/send {:crud/priority 3} :boolean]
       [:boulder-attempt/attempts {:optional true :crud/priority 4} :int]
       [:boulder-attempt/color {:optional true} :string]
       [:boulder-attempt/problem-id {:optional true} :string]
       [:boulder-attempt/notes {:optional true} :string]
       [:airtable/id {:optional true} :string]
       [:airtable/created-time {:optional true} :instant]
       [:airtable/ported-at {:optional true} :instant]]
      (concat sm/legacy-meta)
      vec))
