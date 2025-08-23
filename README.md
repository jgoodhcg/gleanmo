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

Gleanmo uses a **declarative chart initialization pattern** that separates data generation (Clojure) from presentation (JavaScript) while avoiding inline script issues common with server-side rendering.

## How It Works

### 1. Generate Chart Data in Clojure

```clojure
(defn habit-frequency-chart [ctx habit-logs]
  (let [chart-options {:title {:text "Daily Habit Completion"}
                       :xAxis {:type "category"
                               :data (map :habit-log/date habit-logs)}
                       :yAxis {:type "value"}
                       :series [{:data (map :habit-log/count habit-logs)
                                :type "bar"}]}]
    (ui/page
     (assoc ctx ::ui/echarts true)
     [:div
      [:h2 "Habit Tracking Dashboard"]
      ;; Chart container with data reference
      [:div#habit-chart {:style {:height "400px"} 
                         :data-chart-data "habit-chart-data"}]
      ;; Hidden data element
      [:div#habit-chart-data.hidden
       (json/generate-string chart-options {:pretty true})]])))
```

### 2. JavaScript Auto-Discovery

The `main.js` file automatically finds and renders charts on page load:

```javascript
// Auto-initialize any charts on page load
document.addEventListener('DOMContentLoaded', function() {
  const chartElements = document.querySelectorAll('[data-chart-data]');
  chartElements.forEach(element => {
    const dataElementId = element.getAttribute('data-chart-data');
    renderEChartFromData(element.id, dataElementId);
  });
});
```

### 3. Multiple Charts Per Page

Create dashboards by adding multiple chart/data pairs:

```clojure
(defn meditation-dashboard [ctx meditation-logs bm-logs]
  (let [meditation-chart {:title {:text "Meditation Sessions This Month"}
                          :xAxis {:type "category" 
                                  :data (map #(-> % :meditation-log/beginning str) meditation-logs)}
                          :series [{:data (map :meditation-log/duration meditation-logs)
                                   :type "line"}]}
        bm-chart {:title {:text "Daily BM Tracking"}
                  :xAxis {:type "category"
                          :data (map :bm-log/timestamp bm-logs)}
                  :series [{:data (map :bm-log/bristol-scale bm-logs)
                           :type "scatter"}]}]
    (ui/page
     (assoc ctx ::ui/echarts true)
     [:div.grid.grid-cols-2.gap-4
      ;; Meditation chart
      [:div
       [:h3 "Meditation Progress"]
       [:div#meditation-chart {:style {:height "300px"} 
                               :data-chart-data "meditation-data"}]]
      ;; BM tracking chart  
      [:div
       [:h3 "Health Metrics"]
       [:div#bm-chart {:style {:height "300px"}
                       :data-chart-data "bm-data"}]]
      ;; Hidden data elements
      [:div#meditation-data.hidden (json/generate-string meditation-chart)]
      [:div#bm-data.hidden (json/generate-string bm-chart)]])))
```

## Pattern Benefits

### ✅ **No Build Step Required**
- Pure vanilla JavaScript with ECharts CDN
- No webpack, rollup, or compilation needed
- Immediate development feedback

### ✅ **Reliable Rendering** 
- Avoids Rum/Hiccup inline JavaScript encoding issues
- No HTML entity problems (`&amp;` vs `&`)
- Clean separation between data and presentation

### ✅ **Type-Safe Data Generation**
- Chart options defined in Clojure data structures
- Compile-time validation of data shapes
- JSON generation handled automatically

### ✅ **Reusable Across Entity Types**
- Same pattern works for habit-logs, meditation-logs, bm-logs, etc.
- Easy to add new chart types by extending JavaScript functions
- Consistent developer experience

## Adding New Chart Types

1. **Add rendering function to main.js:**
```javascript
function renderTimelineChart(elementId, options) {
  const chart = echarts.init(document.getElementById(elementId));
  // Timeline-specific configuration
  chart.setOption({
    timeline: options.timeline,
    // ... timeline options
  });
  return chart;
}
```

2. **Use in Clojure with data-driven approach:**
```clojure
[:div#exercise-timeline {:data-chart-data "timeline-data"}]
[:div#timeline-data.hidden (json/generate-string timeline-options)]
```

This pattern scales naturally as you add more entity types and visualization needs to your tracking application.

## License

All rights reserved. This code is shared for demonstration and educational purposes.
