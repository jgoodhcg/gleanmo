(ns tech.jgood.gleanmo.app.cruddy
  (:require
   [cheshire.core :as json]
   [tech.jgood.gleanmo.app.shared :refer [side-bar]]
   [tech.jgood.gleanmo.crud.routes :as crud]
   [tech.jgood.gleanmo.schema :refer [schema]]
   [tech.jgood.gleanmo.ui :as ui]))

(def crud-routes
  (crud/gen-routes {:entity-key :cruddy
                    :entity-str "cruddy"
                    :plural-str "cruddies"
                    :schema     schema}))

(defn hello-world-chart
  [ctx]
  (let [chart-options {:title {:text "Hello World Chart"}
                       :xAxis {:type "category"
                               :data ["Mon" "Tue" "Wed" "Thu" "Fri" "Sat" "Sun"]}
                       :yAxis {:type "value"}
                       :series [{:data [120 200 150 80 70 110 130]
                                :type "bar"}]}]
    (ui/page
     (assoc ctx ::ui/echarts true)
     [:div
      [:h1 "ECharts Test"]
      [:p "ECharts library should be loaded"]
      [:div#chart {:style {:height "400px" :border "1px solid #ccc"} :data-chart-data "chart-options"}]
      [:div#chart-options.hidden
       (json/generate-string chart-options {:pretty true})]])))

(def viz-routes
  ["/viz" {}
   ["/hello-chart" {:get hello-world-chart}]])