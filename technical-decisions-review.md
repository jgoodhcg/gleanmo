# Technical Decisions Review

A log of architectural decisions that either cause ongoing friction, frequent reconsideration, or have evolved beyond their original intent.

## Entity Relationship Naming Convention

### Current State (Inconsistent)

**Entity-scoped pattern** (primary convention):
```clojure
;; Most entities follow this pattern
[:meditation-log/location-id :location/id]
[:meditation-log/type-id :meditation/id]  
[:habit-log/habit-ids [:set :habit/id]]
[:exercise-log/id :exercise-log/id]
```

**Foreign key pattern** (exception):
```clojure
;; User relationships always use this
[:user/id :user/id]

;; Project-log breaks convention (inconsistent)
[:project/id :project/id]
[:location/id :location/id]
```

### Problems This Creates

1. **Label Generation Issues**: `:project/id` → "Id" instead of "Project Id"
2. **Form Pre-population Complexity**: Different field name patterns require different URL param handling
3. **Mental Model Confusion**: Developers have to remember which entities use which pattern
4. **Query Inconsistency**: Relationship queries work differently across entities

### Decision Record

**Why entity-scoped was chosen**: Clear field ownership, no namespace collisions, better labels
**Why user/id is special**: User relationships are ubiquitous and `:user/id` is semantically clearer than `:entity/user-id`
**Why project-log broke pattern**: Likely copied from an older entity or developed before pattern was established

### Proposed Resolution

Standardize on **entity-scoped relationships** except for user ownership:

```clojure
;; Standard pattern
[:entity-log/parent-entity-id :parent-entity/id]
[:entity-log/location-id :location/id]

;; Exception for user ownership
[:user/id :user/id]  ; Always this pattern
```

## Routing Architecture Philosophy

### The Dilemma

**Domain-first vs Function-first URL patterns**

**Domain-first** (intuitive but problematic):
```
/app/projects/timer
/app/projects/:id          # Conflicts with /projects/new
/app/habits/:id/logs
```

**Function-first** (current system):
```
/app/crud/project
/app/viz/project-log  
/app/timer/project-log
```

### Technical Constraint: Reitit Trie Optimization

Reitit's default trie router cannot distinguish:
- `/app/projects/:id` vs `/app/projects/new`
- Routes like `/:id` consume everything, making literal routes unreachable

**Options:**
1. Use non-optimized router (performance trade-off)
2. Stick with function-first routing (current approach)
3. Use workarounds like `/app/projects/id/:id`

### Current Decision

**Function-first routing** because:
- Works with default Reitit optimization
- Consistent pattern across all features
- No performance trade-offs
- Clear functional grouping

**Mental model**: Users think "I want to **track** time" not "I want to work with project-logs"

### Ongoing Tension

Function-first feels less intuitive but is technically superior given the router constraints. This decision gets revisited frequently when adding new features.

### Route Evolution

**Current stable patterns:**
- `/app/crud/{entity}` - CRUD operations
- `/app/viz/{entity}` - Visualizations  
- `/app/timer/{entity}` - Time tracking interfaces
- `/app/dashboards/{view}` - Summary views

**Future considerations**: As the app grows, function-first may become unwieldy. May need to revisit router optimization vs. URL aesthetics trade-off.

---

*Note: This document captures decisions that generate ongoing discussion or friction. Well-settled decisions don't need to be included unless they're frequently questioned.*