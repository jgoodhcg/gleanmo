# Mood Tracking Roadmap

## Work Unit Summary
- Status: idea
- Problem / intent: Add structured mood logging with Airtable backfill and simple visualizations.
- Constraints: Preserve Airtable lineage fields and keep the entry flow lightweight.
- Proposed approach: Define schema + CRUD, build an Airtable ingester, then enable calendar heatmaps.
- Open questions: Should mood be multi-dimensional or a single scale for v1?

Introduce structured mood logging (daily or multi-entry) with full Airtable history.

## Goals
- Capture affect, energy, and contextual notes in a reusable schema.
- Import every Airtable mood entry before switching to Gleanmo-only tracking.
- Feed dashboards (streaks, correlations) and future mental health insights.

## Proposed Schema
```clojure
:mood-log/id             :uuid
:user/id                 :user/id
:mood-log/timestamp      :instant     ; allow multiple per day
:mood-log/mood           [:enum :very-low :low :neutral :good :great]
:mood-log/energy         {:optional true} [:enum :exhausted :low :medium :high]
:mood-log/stress         {:optional true} [:enum :low :medium :high]
:mood-log/tags           {:optional true} [:set :keyword] ; e.g., :social, :work, :health
:mood-log/notes          {:optional true} :string
:mood-log/activity-ref   {:optional true} :string ; link to diary entry / location
:airtable/id             {:optional true} :string
:airtable/created-time   {:optional true} :instant
```
_Adjust enums/tags once we inspect Airtable columns._

## Implementation Steps
1. **Schema + CRUD**: Add Malli schema, CRUD routes, and a compact entry form (emoji buttons + sliders).  
2. **Airtable Migration**: Export mood base, write converters (enum mapping, deterministic UUIDs, validation), and import history.  
3. **Visualization**: Enable `/app/viz/calendar/mood-log`; plan future line charts (rolling averages, energy vs. mood).  
4. **Integrations**: Consider cross-linking with meditation/exercise logs once data is centralized.

## Open Questions
- Do we track multiple mood dimensions (valence, arousal, anxiety) separately or stick to a primary scale?  
- Should tags be free-form strings (like Airtable) or enforced keywords?  
- Any need for reminders/notifications tied to mood logging?
