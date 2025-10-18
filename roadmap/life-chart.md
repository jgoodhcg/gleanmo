# Life Chart Calendar Requirements

## Overview
A life visualization calendar where each row represents a year of life and each cell represents a week. This provides a "life at a glance" view to understand major periods, transitions, and milestones across an entire lifetime.

## Visual Structure

### Grid Layout
- **Rows**: Years of life (Age 0 to projected lifespan ~80-100 years)
- **Columns**: Weeks (52 weeks per year)
- **Cells**: Individual weeks (small rectangles)
- **Scale**: ~80-100 rows × 52 columns = 4,160-5,200 cells total

### Display Considerations
- **Responsive Design**: Must work on various screen sizes
- **Scrolling**: Vertical scroll through years, horizontal scroll if needed
- **Zoom Levels**: Different detail levels (decade view, full life view)
- **Current Position**: Highlight current week/age
- **Navigation**: Jump to specific years/ages

## Core Entities

### Life Periods (Eras)
```clojure
:life-period/id           :uuid
:life-period/label        :string          ; "High School", "Google Job", "Marriage to X"
:life-period/category     [:enum :education :work :relationship :residence :project :health :other]
:life-period/start-date   :instant         ; Period beginning
:life-period/end-date     {:optional true} :instant ; Period end (nil = ongoing)
:life-period/color        {:optional true} :string  ; Hex color for visualization
:life-period/description  {:optional true} :string  ; Detailed notes
:life-period/importance   {:optional true} [:enum :major :normal :minor] ; Visual prominence
:user/id                  :user/id         ; Owner
```

### Life Events (Extension of existing events)
```clojure
:event/life-chart-visible {:optional true} :boolean ; Show on life chart
:event/life-significance  {:optional true} [:enum :major :milestone :minor] ; Visual treatment
:event/life-category      {:optional true} [:enum :birth :death :graduation :move :achievement :relationship :career :health :travel :other]
```

## Period Categories & Examples

### Education Periods
- Elementary School (Age 5-11)
- Middle School (Age 11-14)
- High School (Age 14-18)
- College/University (Age 18-22)
- Graduate School
- Professional Training

### Work Periods  
- First Job
- Career Changes
- Specific Companies/Roles
- Unemployment Periods
- Retirement

### Relationship Periods
- Dating Relationships
- Marriages/Partnerships
- Parenthood Phases
- Close Friendships

### Residence Periods
- Childhood Home
- College Housing
- Apartments/Houses lived in
- Cities/Countries resided in

### Project Periods
- Major Personal Projects
- Hobbies with defined timeframes
- Creative endeavors
- Business ventures

### Health/Life Phase Periods
- Major health conditions
- Fitness phases
- Mental health periods
- Life transitions

## Visual Design

### Period Representation
- **Background Colors**: Periods fill weeks with translucent colors
- **Overlapping Periods**: Multiple periods can overlap (e.g., job + relationship + address)
- **Layer System**: Different categories on different visual layers
- **Blending**: Smooth color transitions for overlapping periods

### Event Representation
- **Markers**: Small dots or icons on specific weeks
- **Event Types**: Different shapes/colors for different event categories
- **Milestones**: Larger, more prominent markers for major events

### Week Cells
- **Hover States**: Show date range, overlapping periods, events
- **Click Actions**: View details, add periods/events
- **Current Week**: Special highlighting
- **Future Weeks**: Different styling (dimmed/outlined)

## Interactions & Features

### Period Management
- **Create Period**: Click and drag to define start/end dates
- **Edit Period**: Modify dates, labels, colors, categories
- **Period Details**: View/edit full information
- **Period Search**: Find periods by name/category/date range

### Event Integration
- **Existing Events**: Show big-calendar events on life chart
- **Event Filtering**: Choose which events to display
- **Event Creation**: Add life-significant events directly

### Navigation & Views
- **Scroll Navigation**: Smooth scrolling through years
- **Jump to Age**: Quick navigation to specific life periods
- **Zoom Levels**: Overview vs detailed views
- **Filtering**: Show/hide specific period categories

### Analytics & Insights
- **Period Duration**: How long did periods last?
- **Overlap Analysis**: Which periods coincided?
- **Life Phase Insights**: Current age relative to life phases
- **Pattern Recognition**: Recurring cycles or transitions

## Data Integration

### Relationship to Big Calendar
- **Shared Event Data**: Same events can appear on both calendars
- **Different Granularities**: Big calendar (days) vs Life chart (weeks)
- **Complementary Views**: Zoom in (big cal) vs zoom out (life chart)

### Data Sources
- **Manual Entry**: Primary method for historical data
- **Import Options**: 
  - Resume/CV data for work periods
  - Social media timeline data
  - Photo metadata for life events
- **Big Calendar Events**: Automatically consider for life chart

## Technical Implementation

### Database Schema
- **New Entity**: `:life-period` with overlapping date ranges
- **Extended Events**: Add life-chart fields to existing event schema
- **Efficient Queries**: Get all periods/events for date ranges
- **User Preferences**: Default colors, categories, display settings

### Rendering Strategy
- **Canvas or SVG**: For performance with thousands of cells
- **Virtualization**: Only render visible portions
- **Responsive Grid**: Adapt cell size to screen
- **Smooth Interactions**: Optimized hover and click handling

### Performance Considerations
- **Lazy Loading**: Load data as user scrolls
- **Caching**: Cache rendered portions
- **Efficient Updates**: Minimal re-renders on data changes

## Future Enhancements

### Advanced Visualizations
- **Heat Maps**: Intensity of activity during periods
- **Relationship Lines**: Connections between related periods
- **Milestone Paths**: Visual journey between major events

### Data Enrichment
- **Photo Integration**: Show photos from specific periods
- **Journal Integration**: Link to journal entries from periods
- **External Data**: Import from various life tracking sources

### Sharing & Export
- **Life Story Export**: Generate narrative from periods/events
- **Visual Export**: High-resolution life chart images
- **Privacy Controls**: What to share vs keep private

## Success Metrics
- **Completeness**: Percentage of life documented
- **Engagement**: Time spent viewing/editing chart
- **Insights Generated**: Personal discoveries about life patterns
- **Emotional Impact**: Sense of life perspective and accomplishment

## Priority Features for MVP
1. **Basic Grid Rendering**: Years × weeks layout
2. **Simple Period Creation**: Click to add/edit periods
3. **Period Categories**: Education, work, relationships, residence
4. **Current Week Highlighting**: Show where user is now
5. **Hover Details**: Basic information display
6. **Responsive Design**: Works on desktop and mobile

This life chart would provide profound perspective on personal growth, transitions, and the arc of a human life. Combined with the big calendar, it creates a comprehensive temporal view of existence.