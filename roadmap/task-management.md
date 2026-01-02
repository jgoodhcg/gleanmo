# Task Management (Projects + Tasks + Time)

## Work Unit Summary
- Status: idea
- Problem / intent: Add lightweight task tracking linked to projects, with optional time logging, for non-repo personal work.
- Constraints: Avoid GTD complexity, no priorities or deadlines initially, keep the Now list small, and allow projects with only tasks or only time logs.
- Proposed approach: Add a task entity with lifecycle states and optional project linkage; add Now/Later/Snoozed/Archived views and a bulk reset by staleness; keep time tracking in project-log and let tasks link to projects without forcing timers.
- Open questions: Should a task belong to exactly one project or support many? What is the default staleness threshold? What metrics (age, churn, counts) are worth tracking?

## Notes
### Task entity (initial)
```
:task/id                   :uuid
:task/title                :string
:task/state                [:enum :now :later :snoozed :archived]
:task/context              {:optional true} :string
:task/project-id           {:optional true} :project/id
:task/created-at           :instant
:task/last-seen-at         :instant
:task/last-state-change-at :instant
```

### Project relationship
- Tasks can reference a project, but projects do not require tasks.
- Time logging stays in `:project-log`; tasks do not require timers.
- Projects may later link to roam pages/tags without becoming the canonical spec store.

### Core behaviors
- Now list capped at 10 visible tasks.
- Later list can be large and messy.
- One-click reset: archive tasks not touched in N days (reversible).
- Simple context filtering (home, computer, errand) without priority scores.
