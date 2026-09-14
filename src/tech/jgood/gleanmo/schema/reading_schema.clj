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
       [:book/author {:optional true :crud/suggest-existing true, :crud/priority 3} :string]
       [:book/formats {:optional true, :crud/priority 4}
        [:set [:enum :audiobook :paperback :hardcover :ebook]]]
       ;; Edition totals the reading-position charts compare against. Each is
       ;; independent: the user manages edition differences, and nothing
       ;; converts between pages, chapters, and audio.
       [:book/total-pages
        {:optional true, :crud/priority 5, :crud/label "Total pages"}
        :positive-int]
       [:book/total-chapters
        {:optional true, :crud/priority 6, :crud/label "Total chapters"}
        :positive-int]
       [:book/audiobook-duration-seconds
        {:optional true, :crud/priority 7, :crud/label "Audiobook duration",
         :crud/duration-format :hms}
        :positive-int]
       [:book/published {:optional true, :crud/priority 8} :local-date]
       [:book/book-source-ids {:optional true, :crud/priority 9, :crud/label "Sources"}
        [:set :book-source/id]]
       [:book/notes {:optional true, :crud/priority 10} :string]
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
       [:reading-log/book-id
        {:crud/priority 1, :crud/label "Book", :crud/inline-create true}
        :book/id]
       [:reading-log/beginning :instant]
       [:reading-log/end {:optional true} :instant]
       ;; Sparse open-interval flag derived in db/mutations.clj — see
       ;; :exercise-session/running.
       [:reading-log/running {:optional true, :hide true} :boolean]
       [:reading-log/time-zone :string]
       [:reading-log/location-id
        {:optional true, :crud/priority 2, :crud/label "Location",
         :crud/inline-create true}
        :location/id]
       [:reading-log/format {:optional true, :crud/priority 3}
        [:enum :audiobook :paperback :hardcover :ebook]]
       ;; Reading positions. Every endpoint is independently optional and
       ;; unconstrained by format, book totals, or each other: an ending
       ;; position may fall below its start (rereading, corrections), and a
       ;; single log may record several measures. Zero is a real position.
       [:reading-log/start-page
        {:optional true, :crud/priority 8, :crud/label "Starting page"}
        :nonnegative-int]
       [:reading-log/end-page
        {:optional true, :crud/priority 4, :crud/label "Ending page"}
        :nonnegative-int]
       [:reading-log/start-chapter
        {:optional true, :crud/priority 9, :crud/label "Starting chapter"}
        :nonnegative-int]
       [:reading-log/end-chapter
        {:optional true, :crud/priority 5, :crud/label "Ending chapter"}
        :nonnegative-int]
       [:reading-log/start-audio-position-seconds
        {:optional true, :crud/priority 10, :crud/label "Starting audio position",
         :crud/duration-format :hms}
        :nonnegative-int]
       [:reading-log/end-audio-position-seconds
        {:optional true, :crud/priority 6, :crud/label "Ending audio position",
         :crud/duration-format :hms}
        :nonnegative-int]
       [:reading-log/finished? {:optional true} :boolean]
       [:reading-log/notes {:optional true, :crud/priority 7} :string]
       [:airtable/id {:optional true} :string]
       [:airtable/created-time {:optional true} :instant]
       [:airtable/ported-at {:optional true} :instant]
       [:airtable/original-location {:optional true} :string]]
      vec))
