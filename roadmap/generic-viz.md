# Gleanmo Generic Visualization System Requirements

## Project Overview

Gleanmo currently has entity-specific data visualizations scattered across different modules. The goal of this project is to create a generic, reusable visualization system that automatically works with any entity type containing timestamp or interval data, similar to how the existing CRUD abstraction provides generic data management capabilities.

## Goals

1. **Generic Chart Generation**: Automatically detect temporal patterns in entity schemas and generate appropriate visualizations
2. **Consistent Routing**: Provide predictable routes following `/app/viz/{chart-type}/{entity-type}` pattern
3. **ECharts Integration**: Use Apache ECharts for modern, interactive visualizations with consistent styling
4. **Temporal Pattern Support**: Handle both point-in-time events (timestamps) and duration events (intervals)
5. **Zero Configuration**: Entities get visualizations automatically based on their schema patterns
6. **Extensible Architecture**: Easy to add new chart types and customize for specific entities

## Technical Approach

### Core Architecture

#### Visualization Route Generation
```clojure
(def viz-routes 
  (viz/gen-routes {:entity-key :habit-log
                   :entity-schema habit-schema
                   :entity-str "habit-log"
                   :plural-str "habit-logs"}))
```

#### Temporal Pattern Detection
Automatically detect timestamp patterns in Malli schemas:

**Point Events** (single timestamps):
- `entity/timestamp` fields (habit-log, bm-log, medication-log)

**Interval Events** (duration with beginning/end):
- `entity/beginning` + `entity/end` fields (meditation-log, exercise-session)

#### Chart Type Mapping
- **Calendar Heatmap**: Ideal for point events and interval frequency tracking
- **Timeline View**: Perfect for interval events showing duration and overlap
- **Distribution Charts**: Hour-of-day, day-of-week patterns (future)

### Namespace Structure

```
tech.jgood.gleanmo.viz
├── core.clj              # Pattern detection, route generation
├── calendar.clj          # Calendar heatmap implementation  
├── timeline.clj          # Timeline charts (future)
├── data.clj              # Generic data transformation utilities
└── routes.clj            # Route generation and handling
```

### Route Structure

```
/app/viz/calendar/habit-log       # Calendar heatmap for habit logs
/app/viz/calendar/meditation-log  # Calendar heatmap for meditation frequency  
/app/viz/calendar/bm-log          # Calendar heatmap for BM tracking
/app/viz/timeline/meditation-log  # Timeline view for meditation sessions
```

### Schema Integration Pattern

```clojure
;; In habit-log namespace
(def crud-routes (crud/gen-routes {...}))
(def viz-routes (viz/gen-routes {:entity-key :habit-log
                                 :entity-schema hc/habit-log
                                 :entity-str "habit-log"
                                 :plural-str "habit-logs"}))

;; Module definition  
(def module
  {:routes [;; existing CRUD routes
            ["/habit-log" crud-routes]
            ;; new viz routes
            ["/viz" viz-routes]]})
```

## Implementation Phases

### Phase 1: Core Infrastructure
- [ ] Create `tech.jgood.gleanmo.viz.core` with timestamp pattern detection
- [ ] Implement `viz/gen-routes` function paralleling CRUD system
- [ ] Build generic data transformation utilities in `viz.data`
- [ ] Create basic route structure and parameter handling

### Phase 2: Calendar Heatmap Implementation  
- [ ] Implement `tech.jgood.gleanmo.viz.calendar` namespace
- [ ] Add ECharts calendar heatmap configuration generation
- [ ] Create responsive UI components with consistent styling
- [ ] Handle timezone conversion and date aggregation

### Phase 3: Entity Integration
- [ ] Add viz routes to habit-log, meditation-log, and bm-log modules
- [ ] Test automatic pattern detection across different entity types
- [ ] Ensure proper filtering and user-scoped data access
- [ ] Add navigation links from entity list views to visualizations

### Phase 4: Timeline Charts (Future)
- [ ] Implement timeline visualization for interval data
- [ ] Add Gantt-style charts for meditation sessions and exercise
- [ ] Support overlapping interval visualization
- [ ] Add time-of-day pattern analysis

## Technical Benefits

### Leverages Existing Patterns
- **Malli Schema Integration**: Uses existing schema definitions for pattern detection
- **Query Infrastructure**: Builds on established `db.queries` patterns
- **User Scoping**: Inherits existing user filtering and security model
- **UI Consistency**: Uses established Tailwind and component patterns

### Automatic Visualization Generation
- **Zero Configuration**: Entities get charts based on schema analysis
- **Consistent Experience**: Same visualization patterns across all temporal entities  
- **Type Safety**: Leverages Malli validation for robust data handling
- **Performance**: Generic data transformation with entity-specific optimizations

### Extensible Foundation
- **Chart Type Expansion**: Easy to add new visualization types
- **Custom Overrides**: Entities can customize or extend generated visualizations
- **Filter Integration**: Builds on existing sensitive/archived filtering patterns
- **Export Capabilities**: Foundation for future data export features

## Success Metrics

1. **Automatic Detection**: 100% of timestamp/interval entities get appropriate visualizations
2. **Performance**: Calendar heatmaps load in <2 seconds for 1000+ data points
3. **Consistency**: All visualizations follow same interaction and styling patterns
4. **Maintainability**: Adding new chart type requires <100 lines of code
5. **User Experience**: Clear navigation from entity management to visualizations

## Future Expansion Opportunities

### Multi-Entity Dashboards
- Combine multiple entity types in single visualizations
- Correlation analysis: Overlay different entity types to find patterns
- Custom layouts and widget arrangements

### Additional Chart Types
- **Timeline View**: For interval entities (meditation sessions, exercise)
- **Activity by Day of Week**: Bar chart showing patterns
- **Habit Streaks**: Show consecutive days of habit completion
- **Time of Day Patterns**: When activities typically happen

### Clickable Calendar Navigation
- Make calendar heatmap dates clickable to show filtered entity lists
- Click date → route to filtered CRUD view showing entities from that date
- Generic implementation across all temporal entities
- Integration with existing CRUD filtering system

### Data & Interaction
- **Export Functionality**: Generate visualization images or data exports
- **Real-time Updates**: Live updating charts via WebSocket or polling
- **Custom Date Ranges**: User-configurable time period filtering
- **Pattern Recognition**: AI-driven insight generation from temporal patterns
- **Chart configuration validation**: Schema validation for chart configs
- **Performance optimization**: Lazy loading for large datasets
- **Chart lifecycle management**: Proper cleanup and memory management
