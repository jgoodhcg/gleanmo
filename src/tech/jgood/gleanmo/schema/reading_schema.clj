(ns tech.jgood.gleanmo.schema.reading-schema
  (:require
   [tech.jgood.gleanmo.schema.meta :as sm]))

(def book-source
  (-> [:map {:closed true}
       [:xt/id :book-source/id]
       [::sm/type [:enum :book-source]]
       [::sm/deleted-at {:optional true} :instant]
       [::sm/created-at :instant]
       [:user/id :user/id]
       [:book-source/label {:crud/priority 1} :string]
       [:book-source/notes {:optional true, :crud/priority 2} :string]
       [:airtable/ported-at {:optional true} :instant]]
      vec))

(def book
  (-> [:map {:closed true}
       [:xt/id :book/id]
       [::sm/type [:enum :book]]
       [::sm/deleted-at {:optional true} :instant]
       [::sm/created-at :instant]
       [:user/id :user/id]
       [:book/label {:optional true, :crud/priority 1} :string]
       [:book/title {:crud/priority 2} :string]
       [:book/author {:optional true, :crud/priority 3} :string]
       [:book/formats {:optional true, :crud/priority 4}
        [:set [:enum :audiobook :paperback :hardcover]]]
       [:book/published {:optional true, :crud/priority 5} :local-date]
       [:book/book-source-ids {:optional true, :crud/priority 6, :crud/label "Sources"}
        [:set :book-source/id]]
       [:book/notes {:optional true, :crud/priority 7} :string]
       [:airtable/id {:optional true} :string]
       [:airtable/created-time {:optional true} :instant]
       [:airtable/ported-at {:optional true} :instant]]
      vec))

(def reading-log
  (-> [:map {:closed true
             :timer/primary-rel :reading-log/book-id}
       [:xt/id :reading-log/id]
       [::sm/type [:enum :reading-log]]
       [::sm/deleted-at {:optional true} :instant]
       [::sm/created-at :instant]
       [:user/id :user/id]
       [:reading-log/book-id {:crud/priority 1, :crud/label "Book"} :book/id]
       [:reading-log/beginning :instant]
       [:reading-log/end {:optional true} :instant]
       [:reading-log/time-zone :string]
       [:reading-log/location-id {:optional true, :crud/priority 2, :crud/label "Location"}
        :location/id]
       [:reading-log/format {:optional true, :crud/priority 3}
        [:enum :audiobook :paperback :hardcover]]
       [:reading-log/finished? {:optional true} :boolean]
       [:reading-log/notes {:optional true, :crud/priority 4} :string]
       [:airtable/id {:optional true} :string]
       [:airtable/created-time {:optional true} :instant]
       [:airtable/ported-at {:optional true} :instant]
       [:airtable/original-location {:optional true} :string]]
      vec))
