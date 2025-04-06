(ns tech.jgood.gleanmo.schema.cruddy
  (:require [tech.jgood.gleanmo.schema.meta :as sm]))

(def cruddy
  (-> [:map {:closed true}
       [:xt/id :uuid]
       [::sm/type [:enum :cruddy]]
       [::sm/deleted-at {:optional true} :boolean]
       [::sm/created-at :instant]
       [:user/id :user/id]
       [:cruddy/label :string]
       [:cruddy/notes {:optional true} :string]
       [:cruddy/other-text {:optional true} :string]
       [:cruddy/num :number]
       [:cruddy/bool :boolean]
       [:cruddy/integer :int]
       [:cruddy/single-relation :habit/id]
       [:cruddy/another-single-relation :location/id]
       [:cruddy/set-relation [:set :habit/id]]
       [:cruddy/enum [:enum :a :b :c]]
       [:cruddy/time-zone :string]
       [:cruddy/timestamp :instant]
       [:cruddy/other-timestamp {:optional true} :instant]
       [:cruddy/float {:optional true} :float]]
      (concat sm/legacy-meta)
      vec))
