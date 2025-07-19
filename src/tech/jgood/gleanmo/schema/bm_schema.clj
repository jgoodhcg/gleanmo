(ns tech.jgood.gleanmo.schema.bm-schema
  (:require
   [tech.jgood.gleanmo.schema.meta :as sm]))

(def bm-log
  (-> [:map {:closed true}
       [:xt/id :bm-log/id]
       [::sm/type [:enum :bm-log]]
       [::sm/deleted-at {:optional true} :instant]
       [::sm/created-at :instant]
       [:user/id :user/id]
       [:bm-log/timestamp :instant]
       [:bm-log/bristol
        [:enum :b1-hard-clumps :b2-lumpy-log :b3-cracked-log 
               :b4-smooth-log :b5-soft-blobs :b6-mushy-ragged :b7-liquid]]
       [:bm-log/pace
        [:enum :quick :typical :long]]
       [:bm-log/color
        [:enum :brown :yellow :green :black :red :grey]]
       [:bm-log/blood
        [:enum :none :trace :visible :lots]]
       [:bm-log/mucus :boolean]
       [:bm-log/urgency
        [:enum :none :mild :moderate :severe]]
       [:bm-log/incontinence :boolean]
       [:bm-log/straining :boolean]
       [:bm-log/odor
        [:enum :normal :foul :metallic :sweet :sour]]
       [:bm-log/size
        [:enum :small :medium :large]]
       [:bm-log/notes {:optional true} :string]
       [:bm-log/anxiety
        [:enum :none :mild :moderate :severe]]
       [:bm-log/feeling-of-completeness
        [:enum :complete :incomplete :unsure]]
       [:bm-log/ease-of-passage
        [:enum :easy :normal :difficult :very-difficult]]]
      (concat sm/legacy-meta)
      vec))
