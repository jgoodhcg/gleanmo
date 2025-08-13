(ns tech.jgood.gleanmo.schema.bm-schema
  (:require
   [tech.jgood.gleanmo.schema.meta :as sm]))

(def bm-log
  (-> [:map {:closed true}
       [:xt/id :uuid]
       [::sm/type [:enum :bm-log]]
       [::sm/deleted-at {:optional true} :instant]
       [::sm/created-at :instant]
       [:user/id :uuid]
       [:bm-log/timestamp :instant]
       [:bm-log/bristol
        [:enum :b1-hard-clumps :b2-lumpy-log :b3-cracked-log
         :b4-smooth-log :b5-soft-blobs :b6-mushy-ragged :b7-liquid :n-a]]
       [:bm-log/pace
        [:enum :quick :typical :long :n-a]]
       [:bm-log/color
        [:enum :brown :yellow :green :black :red :grey :n-a]]
       [:bm-log/blood
        [:enum :none :trace :visible :lots :n-a]]
       [:bm-log/mucus [:or :boolean [:enum :n-a]]]
       [:bm-log/urgency
        [:enum :none :mild :moderate :severe :n-a]]
       [:bm-log/incontinence [:or :boolean [:enum :n-a]]]
       [:bm-log/straining [:or :boolean [:enum :n-a]]]
       [:bm-log/odor
        [:enum :normal :foul :metallic :sweet :sour :n-a]]
       [:bm-log/size
        [:enum :small :medium :large :n-a]]
       [:bm-log/notes {:optional true} :string]
       [:bm-log/anxiety
        [:enum :none :mild :moderate :severe :n-a]]
       [:bm-log/feeling-of-completeness
        [:enum :complete :incomplete :unsure :n-a]]
       [:bm-log/ease-of-passage
        [:enum :easy :normal :difficult :very-difficult :n-a]]
       ;; Airtable import keys
       [:bm-log/airtable-id {:optional true} :string]
       [:bm-log/airtable-created-time {:optional true} :instant]]
      (concat sm/legacy-meta)
      vec))


