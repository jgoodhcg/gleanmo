(ns tech.jgood.gleanmo.viz.routes
  (:require
   [cheshire.core :as json]
   [clojure.string :as str]
   [tech.jgood.gleanmo.app.shared :refer [side-bar]]
   [tech.jgood.gleanmo.db.queries :as queries]
   [tech.jgood.gleanmo.db.relation-labels :as rel]
   [tech.jgood.gleanmo.schema.utils :as schema-utils]
   [tech.jgood.gleanmo.ui :as ui]
   [taoensso.tufte :refer [defnp p]]))

(defn detect-temporal-pattern
  "Detect temporal patterns in a Malli schema."
  [schema]
  (let [field-map (->> schema
                       (filter vector?)
                       #_{:clj-kondo/ignore [:shadowed-var]}
                       (map (fn [[field-name opts type]]
                              (if (map? opts)
                                [field-name type]
                                [field-name opts])))
                       (into {}))]
    (cond
      ;; Check for entity-specific interval patterns (beginning + end)
      (and (some #(str/ends-with? (str %) "/beginning") (keys field-map))
           (some #(str/ends-with? (str %) "/end") (keys field-map)))
      (let [beginning-field (first (filter #(str/ends-with? (str %) "/beginning") (keys field-map)))
            end-field (first (filter #(str/ends-with? (str %) "/end") (keys field-map)))]
        {:pattern :interval
         :beginning-field beginning-field
         :end-field end-field})

      ;; Check for point events (timestamp fields)
      (some #(str/ends-with? (str %) "/timestamp") (keys field-map))
      (let [timestamp-field (first (filter #(str/ends-with? (str %) "/timestamp") (keys field-map)))]
        {:pattern :point
         :timestamp-field timestamp-field})

      ;; No temporal pattern detected
      :else nil)))

(defn temporal-field
  "Return the temporal field used by a detected temporal pattern."
  [temporal-pattern]
  (or (:timestamp-field temporal-pattern)
      (:beginning-field temporal-pattern)))

(defn date-str
  "Return the yyyy-MM-dd UTC date string for an instant-like value."
  [instant]
  (some-> instant str (subs 0 10)))

(defn build-grouped-chart-data
  "Build ECharts heatmap data from minimal heatmap rows."
  [temporal-pattern data ctx entity-schema entity-str]
  (let [field-to-use        (temporal-field temporal-pattern)
        relationship-fields (schema-utils/extract-relationship-fields
                             entity-schema
                             :remove-system-fields true)]
    (->> data
         (group-by #(date-str (get % field-to-use)))
         (map (fn [[day items]]
                (let [grouped-labels (reduce
                                      (fn [acc item]
                                        (merge-with concat
                                                    acc
                                                    (rel/resolve-relationship-labels
                                                     ctx
                                                     item
                                                     relationship-fields)))
                                      {}
                                      items)
                      unique-labels  (reduce-kv (fn [acc k v]
                                                  (assoc acc k (vec (distinct v))))
                                                {}
                                                grouped-labels)]
                  [day (count items) unique-labels entity-str])))
         (remove (comp nil? first))
         vec)))

(defn generate-mobile-calendar-config
  "Generate ECharts calendar heatmap configuration optimized for mobile (vertical layout)."
  [year chart-data]
  (let [year-range (str year)]
    {:backgroundColor "transparent"
     :title {:text (str "Activity Calendar - " year-range)
             :left "center"
             :textStyle {:color "#c9d1d9"
                         :fontSize 16
                         :fontWeight "bold"}}
     :tooltip {:backgroundColor "rgba(22, 27, 34, 0.95)"
               :borderColor "#30363d"
               :textStyle {:color "#c9d1d9"}}
     :calendar {:range year-range
                :cellSize [12, 12]  ; Smaller cells for mobile
                :orient "vertical"   ; KEY: Vertical orientation for mobile
                :left 50             ; More space for month labels
                :right 20
                :top 60
                :itemStyle {:color "#161b22"      ; Dark surface for empty cells
                            :borderWidth 1
                            :borderColor "#30363d"}
                :dayLabel {:show true
                           :color "#8b949e"
                           :fontSize 9     ; Smaller font for mobile
                           :margin 8}
                :monthLabel {:show true
                             :color "#c9d1d9"
                             :fontSize 11   ; Slightly larger for better readability
                             :fontWeight "bold"
                             :margin 10     ; Add margin for spacing
                             :align "left"} ; Ensure left alignment
                :yearLabel {:show false}}
     :visualMap {:show false}
     :series [{:type "heatmap"
               :coordinateSystem "calendar"
               :data chart-data
               :itemStyle {:borderRadius 2}
               :emphasis {:itemStyle {:borderColor "#ffd400"  ; Gold highlight
                                      :borderWidth 2}}}]}))

(defn generate-calendar-heatmap-config
  "Generate ECharts calendar heatmap configuration with entity details."
  [year chart-data]
  (let [year-range (str year)]
    {:backgroundColor "transparent"
     :title {:text (str "Activity Calendar - " year-range)
             :left "center"
             :textStyle {:color "#c9d1d9"
                         :fontSize 18
                         :fontWeight "bold"}}
     :tooltip {:backgroundColor "rgba(22, 27, 34, 0.95)"
               :borderColor "#30363d"
               :textStyle {:color "#c9d1d9"}}
     :calendar {:range year-range
                :cellSize [18, 18]
                :itemStyle {:color "#161b22"      ; Dark surface for empty cells
                            :borderWidth 1
                            :borderColor "#30363d"}
                :dayLabel {:show true
                           :color "#8b949e"
                           :fontSize 11}
                :monthLabel {:show true
                             :color "#c9d1d9"
                             :fontSize 12
                             :fontWeight "bold"}
                :yearLabel {:show false}}
     :visualMap {:show false}
     :series [{:type "heatmap"
               :coordinateSystem "calendar"
               :data chart-data
               :itemStyle {:borderRadius 2}
               :emphasis {:itemStyle {:borderColor "#ffd400"  ; Gold highlight
                                      :borderWidth 2}}}]}))

(defn render-responsive-chart-section
  "Render a responsive chart section that adapts to screen size."
  [base-chart-id desktop-title mobile-title desktop-config mobile-config]
  [:div
   ;; Desktop version
   [:div.hidden.md:block
    (when desktop-title
      [:h2.text-xl.font-bold.mb-4 desktop-title])
    [:div {:id (str base-chart-id "-desktop")
           :style {:height "400px" :width "100%"}
           :data-chart-data (str base-chart-id "-desktop-data")}]
    [:div {:id (str base-chart-id "-desktop-data") :class "hidden"}
     (json/generate-string desktop-config)]]

   ;; Mobile version
   [:div.block.md:hidden
    (when mobile-title
      [:h2.text-xl.font-bold.mb-4 mobile-title])
    [:div {:id (str base-chart-id "-mobile")
           :style {:height "600px" :width "100%"}  ; Taller for vertical calendar
           :data-chart-data (str base-chart-id "-mobile-data")}]
    [:div {:id (str base-chart-id "-mobile-data") :class "hidden"}
     (json/generate-string mobile-config)]]])

(defn year-range
  "Return inclusive UTC instants for a calendar year."
  [year]
  (let [start (java.time.Instant/parse (str year "-01-01T00:00:00Z"))
        end   (-> (java.time.Instant/parse (str (inc year) "-01-01T00:00:00Z"))
                  (.minusNanos 1))]
    [start end]))

(defn year-card-height
  "Return an inline height for a year skeleton based on row count."
  [count]
  (str (min 340 (max 180 (+ 180 (* 4 (int (Math/sqrt (max 1 count))))))) "px"))

(def heatmap-layout-version
  "Cache-busting version for lazy-loaded heatmap year fragments."
  "background-v1")

(defn year-skeleton-card
  "Render a lazy-loaded year-card placeholder."
  [entity-str {:keys [year count]} trigger]
  [:section.year-card.space-y-3
   {:hx-get     (str "/app/viz/" entity-str "/year/" year
                     "?layout=" heatmap-layout-version)
    :hx-trigger trigger
    :hx-target  "this"
    :hx-swap    "outerHTML"}
   [:div.flex.items-baseline.justify-between.gap-4
    [:h2.text-xl.font-bold (str year)]
    [:span.text-sm.text-gray-400 (str count " entries")]]
   [:div.animate-pulse.bg-dark-surface.opacity-60
    {:style {:height (year-card-height count)}}]])

(defn render-year-card
  "Render a populated heatmap year card."
  [year count desktop-config mobile-config]
  [:section.year-card.space-y-3
   [:div.flex.items-baseline.justify-between.gap-4
    [:h2.text-xl.font-bold (str year)]
    [:span.text-sm.text-gray-400 (str count " entries")]]
   (render-responsive-chart-section
    (str "activity-chart-" year)
    nil
    nil
    desktop-config
    mobile-config)])

(defn render-year-list
  "Render eager recent year cards and hidden earlier year cards."
  [entity-str years]
  (let [recent-years  (take 3 years)
        earlier-years (drop 3 years)
        earlier-count (reduce + (map :count earlier-years))]
    [:div.space-y-10
     (for [year-info recent-years]
       (year-skeleton-card entity-str year-info "load"))
     (when (seq earlier-years)
       [:details.space-y-5
        [:summary.cursor-pointer.font-semibold
         (str "Show " (count earlier-years) " earlier years - "
              earlier-count " entries")]
        [:div.mt-5.space-y-10
         (for [year-info earlier-years]
           (year-skeleton-card entity-str year-info "intersect once"))]])]))

(defn no-data-panel
  "Render the empty state for an entity with no heatmap data."
  [entity-str]
  [:div.py-6
   [:p.text-gray-300 (str "No " entity-str " activity found.")]])

(defn viz-year-fragment
  "Build the heatmap fragment for one entity year."
  [ctx entity-key entity-schema entity-str year]
  (let [temporal-pattern (detect-temporal-pattern entity-schema)
        [start end]      (year-range year)
        ctx              (assoc ctx ::rel/rel-cache (atom {}))
        user-id          (-> ctx :session :uid)
        settings         (queries/resolve-user-settings ctx user-id)
        entity-data      (p {:id (keyword "viz-year-query" entity-str)}
                            (queries/heatmap-data-for-user
                             (:biff/db ctx)
                             user-id
                             entity-key
                             entity-schema
                             start
                             end
                             :user-settings settings))
        _                (rel/prewarm-relation-cache! ctx entity-data)
        chart-data       (build-grouped-chart-data
                          temporal-pattern
                          entity-data
                          ctx
                          entity-schema
                          entity-str)
        desktop-config   (generate-calendar-heatmap-config year chart-data)
        mobile-config    (generate-mobile-calendar-config year chart-data)]
    (render-year-card year (count entity-data) desktop-config mobile-config)))

(defnp viz-year-page
  "Generate one lazy-loaded heatmap year fragment."
  [ctx entity-key entity-schema entity-str]
  (let [year          (Integer/parseInt (get-in ctx [:path-params :year]))
        current-year  (.getValue (java.time.Year/now))
        body          (ui/fragment
                       ctx
                       (viz-year-fragment ctx entity-key entity-schema entity-str year))
        etag          (str "W/\"viz-" entity-str "-" year "-" (hash body) "\"")
        cache-headers (if (< year current-year)
                        {"content-type"  "text/html"
                         "Cache-Control" "private, max-age=31536000"
                         "ETag"          etag}
                        {"content-type"  "text/html"
                         "Cache-Control" "private, no-store"})]
    (if (and (< year current-year)
             (= etag (get-in ctx [:headers "if-none-match"])))
      {:status 304, :headers cache-headers, :body ""}
      {:status 200, :headers cache-headers, :body body})))

(defnp viz-page
  "Generate visualization page for an entity."
  [ctx entity-key entity-schema entity-str _plural-str]
  (let [temporal-pattern (detect-temporal-pattern entity-schema)]
    (if temporal-pattern
      (let [user-id  (-> ctx :session :uid)
            settings (queries/resolve-user-settings ctx user-id)
            years    (p {:id (keyword "viz-years-query" entity-str)}
                        (queries/years-with-data-for-user
                         (:biff/db ctx)
                         user-id
                         entity-key
                         entity-schema
                         :user-settings settings))]
        (ui/page
         (assoc ctx ::ui/echarts true)
         (side-bar
          ctx
          [:div.container.mx-auto.p-6
           [:h1.text-3xl.font-bold.mb-6
            (str "Visualizations - " (str/capitalize entity-str))]
           [:p.mb-6.text-gray-600
            (str "Temporal pattern: " (name (:pattern temporal-pattern)))]

           (if (seq years)
             (render-year-list entity-str years)
             (no-data-panel entity-str))])))

      ;; No temporal pattern detected
      (ui/page
       ctx
       [:div.container.mx-auto.p-6
        [:h1.text-3xl.font-bold.mb-6 "No Visualizations Available"]
        [:p (str "No temporal patterns detected in " entity-str " schema.")]]))))

(defn gen-routes
  "Generate visualization routes for an entity, similar to CRUD gen-routes."
  [{:keys [entity-key entity-schema entity-str plural-str]}]
  ["/viz" {}
   [(str "/" entity-str)
    {:get (fn [ctx]
            (viz-page ctx entity-key entity-schema entity-str plural-str))}]
   [(str "/" entity-str "/year/:year")
    {:get (fn [ctx]
            (viz-year-page ctx entity-key entity-schema entity-str))}]])
