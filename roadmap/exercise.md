# Exercise Tracking Requirements

## Overview
A comprehensive exercise tracking system that supports flexible workout logging, including supersets and detailed rep tracking. Designed to handle Airtable data migration while providing an intuitive workout flow.

## Core Entities

### Exercise
**Purpose**: Basic exercise definitions (e.g., "Bench Press", "Squats")
```clojure
:exercise/id          :uuid
:exercise/label       :string          ; "Bench Press", "Deadlift"  
:exercise/notes       {:optional true} :string
```

### Exercise Session  
**Purpose**: Represents a single trip to the gym or workout session
```clojure
:exercise-session/id         :uuid
:exercise-session/beginning  :instant     ; Start of gym session
:exercise-session/end        {:optional true} :instant ; End of gym session
:exercise-session/notes      {:optional true} :string
```

### Exercise Set
**Purpose**: A single set or superset within a session
```clojure
:exercise-set/id             :uuid
:exercise-session/id         :exercise-session/id  ; Links to session
:exercise-set/beginning      :instant               ; Start of set
:exercise-set/end            {:optional true} :instant ; End of set  
:exercise-set/notes          {:optional true} :string
```

### Exercise Rep
**Purpose**: Individual rep data within a set (supports supersets)
```clojure
:exercise-rep/id             :uuid
:exercise-set/id             :exercise-set/id       ; Links to set
:exercise/id                 :exercise/id           ; Which exercise
:exercise-rep/rep-number     {:optional true} :int  ; Rep sequence in set
:exercise-rep/weight         {:optional true} :number
:exercise-rep/weight-unit    {:optional true} :string ; "lbs", "kg"
:exercise-rep/reps           {:optional true} :int   ; Number of reps
:exercise-rep/distance       {:optional true} :number ; For cardio
:exercise-rep/distance-unit  {:optional true} :string ; "miles", "km"
:exercise-rep/notes          {:optional true} :string
```

## Data Entry Workflow

### Starting a Workout
1. **Create Session**: User starts a new exercise session
2. **Session Screen**: Shows active session with ability to add sets

### During Workout  
1. **Add Set**: User clicks to add a new set before starting
2. **Start Set**: Timer begins for the set
3. **Stop Set**: User stops the set timer when complete
4. **Add Reps**: User adds rep data (exercise, weight, reps)
   - **Single Exercise**: One rep entry per set (most common)
   - **Superset**: Multiple rep entries per set (different exercises)

### Completing Workout
1. **Finish Session**: Mark session as complete
2. **Review**: Option to review and edit entered data

## Key Features

### Superset Support
- Multiple `exercise-rep` entries can belong to a single `exercise-set`
- Each rep entry specifies its exercise, allowing mixed exercises per set
- Set timing covers the entire superset duration

### Flexible Data Entry
- **No timestamps on reps**: Avoids tedious data entry during workouts
- **Optional rep numbering**: For tracking progression within sets
- **Multiple units**: Support for both metric and imperial measurements

### Data Migration Support
- Schema accommodates existing Airtable data structure
- Migration fields can be removed after successful data port

## Schema Discrepancies vs Current Implementation

### Missing Entities
- **Exercise Rep**: Current schema puts rep data directly on exercise-set
- **Proper superset support**: Current design doesn't clearly separate set timing from rep data

### Current Schema Issues
1. **Type Error**: Exercise entity has `[:sm/type [:enum :habit-log]]` instead of `:exercise`
2. **Exercise Log Confusion**: Current `exercise-log` entity overlaps with proposed `exercise-set`
3. **Mixed Concerns**: Current `exercise-set` combines timing and rep data

### Airtable Historical Fields
Current schema includes Airtable fields for preserving data lineage:
- `:airtable/exercise-log` - Original Airtable log reference
- `:airtable/log-count` - Historical log count
- `:airtable/id` - Original Airtable record ID
- `:airtable/ported` - Migration status flag
- `:airtable/created-time` - Original creation timestamp
- `:airtable/missing-duration` - Data quality indicator

These fields preserve historical context and should be maintained permanently.

## Recommended Schema Updates

### 1. Fix Exercise Type
Change `[:sm/type [:enum :habit-log]]` to `[:sm/type [:enum :exercise]]`

### 2. Simplify Current Entities
- Keep current `exercise`, `exercise-session` entities
- Rename `exercise-log` to `exercise-set` (if not already used)
- Add new `exercise-rep` entity

### 3. Migration Strategy
- Preserve Airtable fields permanently for historical data lineage
- Map current `exercise-set` data to new `exercise-rep` structure
- Maintain data provenance through optional Airtable fields

## Success Metrics
- **Quick Entry**: Can log a full workout in under 2 minutes
- **Superset Support**: Easy to track complex workout patterns  
- **Data Integrity**: Full historical data preserved via Airtable fields
- **Flexible Analysis**: Support for various workout analytics and trends

## Implementation + Migration Notes
- The Malli schemas in `src/tech/jgood/gleanmo/schema/exercise_schema.clj` exist but no routes, CRUD pages, or background tasks currently persist exercise data—treat the roadmap above as unimplemented work.
- Airtable remains the system of record. We must:
  1. Export the latest exercise + log tables (sessions, sets, reps) into `airtable_data/`.
  2. Build REPL ingesters mirroring the BM log approach: deterministic UUIDs per Airtable record, enum normalization, and Malli validation.
  3. Persist historical sessions before enabling the new UI so trends remain continuous.
- Once migration helpers exist, document the run (record counts, file names) alongside any cleanup scripts so future backfills are reproducible.
