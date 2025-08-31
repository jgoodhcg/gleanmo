(ns tech.jgood.gleanmo.viz.routes
  (:require
   [cheshire.core :as json]
   [clojure.string :as str]
   [tech.jgood.gleanmo.app.shared :refer [side-bar]]
   [tech.jgood.gleanmo.schema.utils :as schema-utils]
   [tech.jgood.gleanmo.db.queries :as queries]
   [tech.jgood.gleanmo.schema.meta :as sm]
   [tech.jgood.gleanmo.ui :as ui]))

(defn detect-temporal-pattern
  "Detect temporal patterns in a Malli schema."
  [schema]
  (let [field-map (->> schema
                       (filter vector?)
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

(defn resolve-entity-label
  "Generic function to resolve an entity ID to its display label.
   Uses the same logic as CRUD formatting."
  [db entity-id]
  (when entity-id
    (let [entity (queries/get-entity-by-id db entity-id)
          entity-type (or (some-> entity
                                  ::sm/type
                                  name)
                          (some-> entity
                                  keys
                                  first
                                  namespace))
          label-key (when entity-type (keyword entity-type "label"))]
      (if (and label-key (contains? entity label-key))
        (get entity label-key)
        (str (subs (str entity-id) 0 8) "...")))))

(defn resolve-relationship-labels
  "Generic function to resolve relationship fields to display labels.
   Returns a map grouped by relationship type for better tooltip organization."
  [entity relationship-fields db]
  (->> relationship-fields
       (reduce (fn [acc {:keys [field-key input-type related-entity-str]}]
                 (let [field-value (get entity field-key)]
                   (if field-value
                     (let [labels (case input-type
                                    :single-relationship
                                    [(resolve-entity-label db field-value)]
                                    
                                    :many-relationship
                                    (map #(resolve-entity-label db %) field-value)
                                    
                                    [])
                           ;; Just capitalize for now, we'll handle pluralization after aggregation
                           group-name (clojure.string/capitalize related-entity-str)]
                       (if (seq labels)
                         (update acc group-name (fn [existing] (concat (or existing []) labels)))
                         acc))
                     acc)))
               {})))

(defn resolve-relationship-labels-flat
  "Flat version for backward compatibility - returns just the labels."
  [entity relationship-fields db]
  (->> (resolve-relationship-labels entity relationship-fields db)
       vals
       (mapcat identity)
       distinct))

(defn get-relevant-year-range
  "Determine the most relevant year based on data.
   Returns the year with the most recent activity."
  [data temporal-pattern]
  (if (empty? data)
    ;; No data - show current year
    (str (java.time.Year/now))
    
    (let [current-year (java.time.Year/now)
          prev-year (.minusYears current-year 1)
          
          ;; Extract years from data
          timestamp-field (:timestamp-field temporal-pattern)
          beginning-field (:beginning-field temporal-pattern)
          field-to-use (if timestamp-field timestamp-field beginning-field)
          
          data-years (->> data
                          (map #(get % field-to-use))
                          (map #(-> % str (.substring 0 4)))
                          (map #(Integer/parseInt %))
                          distinct
                          sort)
          
          min-data-year (first data-years)
          max-data-year (last data-years)]
      
      ;; Strategy: Show the year with the most recent data
      ;; This gives better UX than trying to show multiple years in one chart
      (str max-data-year))))

(defn generate-mobile-calendar-config
  "Generate ECharts calendar heatmap configuration optimized for mobile (vertical layout)."
  [temporal-pattern data ctx entity-key entity-schema entity-str]
  (let [year-range (get-relevant-year-range data temporal-pattern)
        timestamp-field (:timestamp-field temporal-pattern)
        beginning-field (:beginning-field temporal-pattern)
        
        ;; Get relationship fields from the entity schema to resolve labels generically
        relationship-fields (schema-utils/extract-relationship-fields 
                             entity-schema
                             :remove-system-fields true)
        db (:biff/db ctx)
        
        ;; Group data by date and collect entity details generically
        grouped-data (case (:pattern temporal-pattern)
                       :point
                       (->> data
                            (group-by (fn [item]
                                        (-> (get item timestamp-field) str (.substring 0 10))))
                            (map (fn [[date-str items]]
                                   (let [grouped-labels (reduce (fn [acc item]
                                                                  (let [item-labels (resolve-relationship-labels item relationship-fields db)]
                                                                    (merge-with concat acc item-labels)))
                                                                {}
                                                                items)
                                         ;; Remove duplicates within each group  
                                         unique-grouped-labels (reduce-kv (fn [acc k v]
                                                                            (assoc acc k (distinct v)))
                                                                          {}
                                                                          grouped-labels)]
                                     [date-str (count items) unique-grouped-labels]))))
                       
                       :interval
                       (->> data
                            (group-by (fn [item]
                                        (-> (get item beginning-field) str (.substring 0 10))))
                            (map (fn [[date-str items]]
                                   (let [grouped-labels (reduce (fn [acc item]
                                                                  (let [item-labels (resolve-relationship-labels item relationship-fields db)]
                                                                    (merge-with concat acc item-labels)))
                                                                {}
                                                                items)
                                         ;; Remove duplicates within each group  
                                         unique-grouped-labels (reduce-kv (fn [acc k v]
                                                                            (assoc acc k (distinct v)))
                                                                          {}
                                                                          grouped-labels)]
                                     [date-str (count items) unique-grouped-labels])))))
        
        ;; Create chart data with embedded details for tooltip
        chart-data (map (fn [[date-str count entity-labels]] 
                          [date-str count entity-labels entity-str]) grouped-data)]
    
    {:backgroundColor "#0d1117"  ; Dark background
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
  [temporal-pattern data ctx entity-key entity-schema entity-str]
  (let [year-range (get-relevant-year-range data temporal-pattern)
        timestamp-field (:timestamp-field temporal-pattern)
        beginning-field (:beginning-field temporal-pattern)
        
        ;; Get relationship fields from the entity schema to resolve labels generically
        relationship-fields (schema-utils/extract-relationship-fields 
                             entity-schema
                             :remove-system-fields true)
        db (:biff/db ctx)
        
        ;; Group data by date and collect entity details generically
        grouped-data (case (:pattern temporal-pattern)
                       :point
                       (->> data
                            (group-by (fn [item]
                                        (-> (get item timestamp-field) str (.substring 0 10))))
                            (map (fn [[date-str items]]
                                   (let [grouped-labels (reduce (fn [acc item]
                                                                  (let [item-labels (resolve-relationship-labels item relationship-fields db)]
                                                                    (merge-with concat acc item-labels)))
                                                                {}
                                                                items)
                                         ;; Remove duplicates within each group  
                                         unique-grouped-labels (reduce-kv (fn [acc k v]
                                                                            (assoc acc k (distinct v)))
                                                                          {}
                                                                          grouped-labels)]
                                     [date-str (count items) unique-grouped-labels]))))
                       
                       :interval
                       (->> data
                            (group-by (fn [item]
                                        (-> (get item beginning-field) str (.substring 0 10))))
                            (map (fn [[date-str items]]
                                   (let [grouped-labels (reduce (fn [acc item]
                                                                  (let [item-labels (resolve-relationship-labels item relationship-fields db)]
                                                                    (merge-with concat acc item-labels)))
                                                                {}
                                                                items)
                                         ;; Remove duplicates within each group  
                                         unique-grouped-labels (reduce-kv (fn [acc k v]
                                                                            (assoc acc k (distinct v)))
                                                                          {}
                                                                          grouped-labels)]
                                     [date-str (count items) unique-grouped-labels])))))
        
        ;; Create chart data with embedded details for tooltip
        chart-data (map (fn [[date-str count entity-labels]] 
                          [date-str count entity-labels entity-str]) grouped-data)]
    
    {:backgroundColor "#0d1117"  ; Dark background
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
  [:div.mb-8
   ;; Desktop version
   [:div.hidden.md:block
    [:h2.text-xl.font-bold.mb-4 desktop-title]
    [:div {:id (str base-chart-id "-desktop")
           :style {:height "400px" :width "100%"}
           :data-chart-data (str base-chart-id "-desktop-data")}]
    [:div {:id (str base-chart-id "-desktop-data") :class "hidden"}
     (json/generate-string desktop-config {:pretty true})]]
   
   ;; Mobile version
   [:div.block.md:hidden
    [:h2.text-xl.font-bold.mb-4 mobile-title]
    [:div {:id (str base-chart-id "-mobile")
           :style {:height "600px" :width "100%"}  ; Taller for vertical calendar
           :data-chart-data (str base-chart-id "-mobile-data")}]
    [:div {:id (str base-chart-id "-mobile-data") :class "hidden"}
     (json/generate-string mobile-config {:pretty true})]]])

(defn viz-page
  "Generate visualization page for an entity."
  [ctx entity-key entity-schema entity-str plural-str]
  (let [temporal-pattern (detect-temporal-pattern entity-schema)]
    (if temporal-pattern
      (let [user-id (get-in ctx [:session :uid])
            ;; Fetch entity data respecting user settings (sensitive/archived)
            entity-data (queries/all-for-user-query
                         {:entity-type-str entity-str
                          :schema entity-schema
                          :filter-references true}
                         ctx)
            calendar-config (generate-calendar-heatmap-config temporal-pattern entity-data ctx entity-key entity-schema entity-str)
            mobile-calendar-config (generate-mobile-calendar-config temporal-pattern entity-data ctx entity-key entity-schema entity-str)]
        (ui/page
         (assoc ctx ::ui/echarts true)
         (side-bar 
          ctx
          [:div.container.mx-auto.p-6
           [:h1.text-3xl.font-bold.mb-6 
            (str "Visualizations - " (str/capitalize entity-str))]
           [:p.mb-6.text-gray-600 
            (str "Temporal pattern: " (name (:pattern temporal-pattern)))]
           
           ;; Responsive chart section - horizontal calendar on desktop, vertical calendar on mobile
           (render-responsive-chart-section 
            "activity-chart" 
            "Activity Calendar" 
            "Activity Calendar"
            calendar-config 
            mobile-calendar-config)])))
      
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
            (viz-page ctx entity-key entity-schema entity-str plural-str))}]])
