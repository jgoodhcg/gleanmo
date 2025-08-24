# Gleanmo

A personal tracking and lifestyle management application built with Clojure. 

## About

I'm obsessed with tracking personal metrics. I find quantified information motivating. This project is an attempt to consolidate all the aspects of my life that I want to track digitally. It's also a playground to play with data visualizations of that information.

## Project Structure

- `/src/tech/jgood/gleanmo/` - Core application code
  - `/app/` - Domain-specific modules (habits, meditation, etc.)
  - `/schema/` - Data models and validation
  - `/crud/` - Generic CRUD operations
- `/resources/` - Configuration and static assets
- `/dev/` - Development utilities
- `/test/` - Unit and integration tests

## Tech Stack

- **Backend**: Clojure with [Biff](https://biffweb.com/) framework
- **Database**: [XTDB](https://xtdb.com/) (bitemporal database)
- **Frontend**: [Rum](https://github.com/tonsky/rum) (React wrapper) + [HTMX](https://htmx.org/) + [Tailwind CSS](https://tailwindcss.com/)
- **Authentication**: Email-based with reCAPTCHA protection

## Development

### Development Environment

The project can be run locally with a _standalone_ XTDB topology stored in `storage/xtdb`. Unit tests will run automatically on file saves.

To run the development environment:
```bash
clj -M:dev dev
```

### Running Tests

The project uses the Clojure CLI with a custom test runner defined in `dev/tasks.clj`. Tests are organized under the `tech.jgood.gleanmo.test` namespace.

To run all tests:
```bash
clj -M:dev test
```

To run a specific test namespace just provide the namespace as the next argument:
```bash
clj -M:dev test tech.jgood.gleanmo.test.crud.handlers-test
```

Tests use an in-memory XTDB database via `test-xtdb-node` from the Biff framework, making them fast and isolated from the development database.

# Chart Rendering Pattern

## Overview

Charts are configured in Clojure and rendered with ECharts. Chart options are generated as JSON and auto-discovered by JavaScript.

## Pattern Implementation

### 1. Clojure Chart Configuration

From `src/tech/jgood/gleanmo/viz/routes.clj` - Calendar heatmap generation:

```clojure
(defn render-chart-section
  "Render a chart section with title and chart container."
  [chart-id title chart-config]
  [:div.mb-8
   [:h2.text-xl.font-bold.mb-4 title]
   [:div {:id chart-id 
          :style {:height "300px" :width "100%"}
          :data-chart-data (str chart-id "-data")}]
   [:div {:id (str chart-id "-data") :class "hidden"}
    (json/generate-string chart-config {:pretty true})]])

(defn generate-calendar-heatmap-config
  "Generate ECharts calendar heatmap configuration with entity details."
  [temporal-pattern data ctx entity-key entity-schema entity-str]
  (let [current-year (str (java.time.Year/now))
        chart-data (map (fn [[date-str count entity-labels]] 
                          [date-str count entity-labels entity-str]) grouped-data)]
    {:backgroundColor "#0d1117"
     :title {:text "Activity Calendar"
             :left "center"
             :textStyle {:color "#c9d1d9"}}
     :tooltip {:backgroundColor "rgba(22, 27, 34, 0.95)"
               :borderColor "#30363d"}
     :calendar {:range current-year
                :cellSize [18, 18]
                :itemStyle {:color "#161b22"
                            :borderWidth 1
                            :borderColor "#30363d"}}
     :series [{:type "heatmap"
               :coordinateSystem "calendar"
               :data chart-data}]}))
```

### 2. JavaScript Auto-Discovery

From `resources/public/js/main.js` - Automatic chart initialization:

```javascript
// Auto-initialize any charts on page load
document.addEventListener('DOMContentLoaded', function() {
  const chartElements = document.querySelectorAll('[data-chart-data]');
  chartElements.forEach(element => {
    const dataElementId = element.getAttribute('data-chart-data');
    renderEChartFromData(element.id, dataElementId);
  });
});

function renderEChartFromData(chartElementId, dataElementId) {
  const dataElement = document.getElementById(dataElementId);
  const dataJson = dataElement.textContent || dataElement.innerText;
  
  try {
    const options = JSON.parse(dataJson);
    
    // Apply theme and tooltip customizations for calendar heatmaps
    if (options.calendar && options.series && options.series[0].type === 'heatmap') {
      // Custom tooltip with grouped relationship data
      options.tooltip = {
        formatter: function(params) {
          const entityLabels = params.value[2] || {};
          // Show grouped relationships (e.g., "Meditation: • Mindfulness", "Location: • Living Room")
          // ...tooltip formatting logic
        }
      };
    }
    
    return renderEChart(chartElementId, options);
  } catch (error) {
    console.error('Failed to parse chart options:', error);
  }
}
```

### 3. Page Integration

From `src/tech/jgood/gleanmo/viz/routes.clj` - How charts are embedded in pages:

```clojure
(defn viz-page
  "Generate visualization page for an entity."
  [ctx entity-key entity-schema entity-str plural-str]
  (let [temporal-pattern (detect-temporal-pattern entity-schema)
        entity-data (queries/all-for-user-query
                     {:entity-type-str entity-str
                      :schema entity-schema
                      :filter-references true} ctx)
        calendar-config (generate-calendar-heatmap-config 
                         temporal-pattern entity-data ctx entity-key entity-schema entity-str)]
    (ui/page
     (assoc ctx ::ui/echarts true)  ; Include ECharts CDN
     (side-bar ctx
       [:div.container.mx-auto.p-6
        [:h1.text-3xl.font-bold.mb-6 (str "Visualizations - " (str/capitalize entity-str))]
        (render-chart-section "calendar-heatmap" "Activity Calendar" calendar-config)]))))
```

## Generic Visualization System

Add to any entity namespace:

```clojure
(def viz-routes
  (viz-routes/gen-routes {:entity-key :habit-log
                          :entity-schema habit-schema/habit-log
                          :entity-str "habit-log"
                          :plural-str "habit-logs"}))
```

Features:
- Temporal pattern detection (point events vs intervals)
- Generic relationship resolution for tooltips  
- User privacy settings compliance
- Consistent theming

## License

All rights reserved. This code is shared for demonstration and educational purposes.
