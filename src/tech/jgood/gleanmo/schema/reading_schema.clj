(ns tech.jgood.gleanmo.schema.reading-schema
  (:require
   [tech.jgood.gleanmo.schema.meta :as sm]))

(def book
  (-> [:map {:closed true}
       [:xt/id :book/id]
       [::sm/type [:enum :book]]
       [::sm/deleted-at {:optional true} :instant]
       [::sm/created-at :instant]
       [:user/id :user/id]
       [:book/title {:crud/priority 1} :string]
       [:book/author {:optional true, :crud/priority 2} :string]
       [:book/formats {:optional true, :crud/priority 3}
        [:set [:enum :audiobook :paperback :hardcover]]]
       [:book/published {:optional true, :crud/priority 4} :local-date]
       [:book/from {:optional true, :crud/priority 5}
        [:enum :library-of-america :amazon :audible
         :barnes-and-nobles-woodland-mall :the-gallery-bookstore-chicago
         :curious-book-shop-east-lansing :argos-comics-and-used-books-grand-rapids
         :kurzgesagt-shop :grpl-friends-of-the-library-sale
         :black-dog-books-and-records-grand-rapids :schuler-books :other]]
       [:book/notes {:optional true, :crud/priority 6} :string]
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
       [:reading-log/location {:optional true, :crud/priority 2}
        [:enum :dog-park :bed :stressless-wing-chair :car :chair :gym
         :kaitis-bed :couch :other :porch :beach :desk-gaming :deck :hammock]]
       [:reading-log/format {:optional true, :crud/priority 3}
        [:enum :audiobook :paperback :hardcover]]
       [:reading-log/finished? {:optional true} :boolean]
       [:reading-log/notes {:optional true, :crud/priority 4} :string]
       [:airtable/id {:optional true} :string]
       [:airtable/created-time {:optional true} :instant]
       [:airtable/ported-at {:optional true} :instant]]
      vec))
