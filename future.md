# Future Improvements

## Visualization Enhancements

### Clickable Calendar Navigation
- **Goal**: Make calendar heatmap dates clickable to show filtered entity lists
- **Details**: Click on a calendar date to route to filtered CRUD view showing only entities from that specific date
- **Considerations**:
  - Route structure (extend existing CRUD vs new routes)
  - Generic implementation across all temporal entities
  - Date filtering integration with existing CRUD system
  - Back navigation from filtered view to calendar
  - URL format for date parameters

### Additional Chart Types
- **Timeline View**: For interval entities (meditation sessions, exercise)
- **Activity by Day of Week**: Bar chart showing patterns
- **Habit Streaks**: Show consecutive days of habit completion
- **Time of Day Patterns**: When activities typically happen

## Generic Viz System Extensions
- **Multi-entity dashboards**: Combine multiple entity types in single view
- **Custom date ranges**: User-configurable time periods
- **Data export**: Export chart data or images
- **Real-time updates**: Live updating charts


## Roam Integration (Planned)
- **Project schemas**: Connect projects with Roam pages
- **Metrics collection**: Pull todo counts, activity from Roam
- **Time tracking**: Link time tracking to projects
- **Dashboard integration**: Unified project tracking view

## Data-Driven Pattern Improvements
- **Chart configuration validation**: Schema validation for chart configs
- **Performance optimization**: Lazy loading for large datasets
- **Chart lifecycle management**: Proper cleanup and memory management