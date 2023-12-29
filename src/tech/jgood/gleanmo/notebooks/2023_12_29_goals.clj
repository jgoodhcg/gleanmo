(ns tech.jgood.gleanmo.notebooks.2023-12-29-goals
 (:require [nextjournal.clerk :as clerk]))

;; ## Geoshape example with requesting data
(clerk/vl {:width 700 :height 400 :data {:url "https://vega.github.io/vega-datasets/data/us-10m.json"
                                         :format {:type "topojson" :feature "counties"}}
           :transform [{:lookup "id" :from {:data {:url "https://vega.github.io/vega-datasets/data/unemployment.tsv"}
                                            :key "id" :fields ["rate"]}}]
           :projection {:type "albersUsa"} :mark "geoshape" :encoding {:color {:field "rate" :type "quantitative"}}})
