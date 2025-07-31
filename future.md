# Gleanmo Future Features & Ideas

## Generic Data Visualizations

### Concept
Build reusable visualizations leveraging consistent timestamp patterns across entities, similar to the CRUD abstraction. Use Apache ECharts for implementation.

### Temporal Patterns Identified

**Single Timestamps** (point events):
- `habit-log/timestamp`, `bm-log/timestamp`, `medication-log/timestamp`

**Intervals** (duration events):
- `meditation-log/beginning|end`, `exercise-session/beginning|end`, `exercise-log.interval/beginning|end`

### High-Value Generic Visualizations

#### 1. Calendar Heatmap (Priority #1)
**Why**: Perfect for habit tracking, shows streaks/gaps, patterns over months
- Color intensity = frequency/count per day
- Works for both timestamps and intervals (count activities per day)
- ECharts calendar component handles this beautifully
- **Use cases**: Meditation streaks, workout consistency, medication adherence

#### 2. Activity Timeline (Priority #2)
**Why**: See daily rhythms, overlapping activities, time-of-day patterns
- Gantt-style chart showing intervals as bars
- Point events as markers
- Zoom/brush for different time ranges
- **Use cases**: Daily schedule view, see if meditation timing affects other activities

#### 3. Frequency Distribution Charts
**Why**: Identify patterns - what days/times are you most active?
- Bar charts: activity count by hour/day-of-week/month
- Line charts: trends over time
- **Use cases**: "I meditate most on Tuesdays", "Exercise peaks at 6pm"

#### 4. Duration Analysis (intervals only)
**Why**: Track efficiency, spot outliers
- Box plots or violin plots showing duration distributions
- Trend lines for average duration over time
- **Use cases**: Meditation session lengths, workout duration trends

#### 5. Multi-Metric Correlation Dashboard
**Why**: See relationships between tracked metrics
- Small multiples showing different entities side-by-side
- Synchronized time axes for comparison
- **Use cases**: "Do longer meditations correlate with better BM logs?"

### Recommended Starting Point

**Calendar Heatmap** + **Activity Timeline** combo would provide immediate value:

```javascript
// Generic calendar config
const calendarConfig = {
  type: 'heatmap',
  calendar: {
    range: ['2024-01-01', '2024-12-31'],
    cellSize: ['auto', 'auto']
  },
  visualMap: {
    type: 'piecewise',
    orient: 'horizontal'
  }
}

// Generic timeline config  
const timelineConfig = {
  type: 'custom',
  renderItem: (params, api) => {
    // Render bars for intervals, dots for timestamps
  },
  dataZoom: [{ type: 'slider' }]
}
```

### Implementation Approach

1. **Generic Chart Namespace**: Create `tech.jgood.gleanmo.charts` similar to CRUD
2. **Chart Type Detection**: Automatically determine chart type based on entity schema
3. **Configuration Abstraction**: Generic configs that adapt to different entities
4. **Data Transformation**: Generic functions to transform entity data into chart-ready format

### Benefits

- **Immediate Value**: Works across all existing entities with timestamp patterns
- **Consistent UX**: Same visualization patterns for similar data types
- **Low Maintenance**: Add new entities, get charts automatically
- **Extensible**: Easy to add new chart types or customize for specific entities

## Other Future Ideas

(Add other future features here as they come up)