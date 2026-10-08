# UI redesign screen inventory

Updated: 2026-10-08.
Owner: [087-essential-information-redesign.md](./087-essential-information-redesign.md).

This running checklist covers standalone pages, generated screens, and important embedded states.
The initial list comes from registered routes and shared route generators.
This is a source audit; no visual review has occurred.

A screen is a distinct user task or layout.
Record-specific URLs share a route template; aliases share a row.
Background fragments and mutation endpoints belong to their visible parent screen.

Review states: **pending**, **briefed**, **iterating**, **reviewed**, **retired**.
Add brief and iteration links as work proceeds.
Reviewed means phone and desktop layouts were assessed, including applicable empty, populated, validation-error, and loading states.
Keep retired entries with a reason and replacement link.
Reconcile this list whenever routes or substantial interactions change.

## Standalone screens

| Screen | Route | Review |
|---|---|---|
| Home and activity timeline | `/app` | pending |
| Logging hub | `/app/log` | pending |
| Today | `/app/task/today` | pending |
| Focus (alias /app/task) | `/app/task/focus` | pending |
| Year calendar | `/app/calendar/year` | pending |
| Calendar event form | `/app/calendar/event-form` | pending |
| Timers workspace | `/app/timers` | pending |
| Entities dashboard | `/app/dashboards/entities` | pending |
| Activity logs dashboard | `/app/dashboards/activity-logs` | pending |
| Stats dashboard | `/app/dashboards/stats` | pending |
| Goals dashboard | `/app/goals` | pending |
| New goal | `/app/goals/new` | pending |
| Goal detail | `/app/goal/:id` | pending |
| Edit goal | `/app/goal/:id/edit` | pending |
| Workout workspace | `/app/exercise/session` | pending |
| Workout summary | `/app/exercise/session/:id/summary` | pending |
| Bouldering workspace | `/app/boulder/session` | pending |
| Bouldering summary | `/app/boulder/session/:id/summary` | pending |
| Bouldering problems | `/app/boulder/problems` | pending |
| Habit patterns | `/app/stats/habit-patterns` | pending |
| Meditation statistics | `/app/stats/meditation` | pending |
| BM statistics | `/app/stats/bm` | pending |
| Medication history | `/app/stats/medication-history` | pending |
| My user / settings entry | `/app/my-user` | pending |
| User detail | `/app/users/:id` | pending |
| User edit and settings | `/app/users/:id/edit` | pending |
| Database browser | `/app/db; /app/db/:type` | pending |
| Performance monitoring | `/app/monitoring/performance` | pending |
| Database diagnostics | `/app/monitoring/db` | pending |
| Public home | `/` | pending |
| Sign in | `/signin` | pending |
| Sign up | `/signup` | pending |
| Link sent | `/link-sent` | pending |
| Email verification | `/verify-link` | pending |
| Code entry | `/verify-code` | pending |
| About | `/about/` | pending |

## Generated CRUD screens

Each entity has three screens, tracked independently below.
Review list, table, and card modes on each list screen.

- List: `/app/crud/<entity>`.
- New: `/app/crud/form/<entity>/new`.
- Edit: `/app/crud/form/<entity>/edit/:id`.
- Inline creation: `/app/crud/inline/<entity>/new`, when offered in a parent form.

| Entity | List | New | Edit |
|---|---|---|---|
| cruddy | pending | pending | pending |
| calendar-event | pending | pending | pending |
| habit | pending | pending | pending |
| habit-log | pending | pending | pending |
| location | pending | pending | pending |
| meditation | pending | pending | pending |
| meditation-log | pending | pending | pending |
| bm-log | pending | pending | pending |
| medication | pending | pending | pending |
| medication-log | pending | pending | pending |
| book-source | pending | pending | pending |
| book | pending | pending | pending |
| reading-log | pending | pending | pending |
| project | pending | pending | pending |
| project-log | pending | pending | pending |
| symptom-episode | pending | pending | pending |
| symptom-log | pending | pending | pending |
| mood-log | pending | pending | pending |
| exercise | pending | pending | pending |
| exercise-session | pending | pending | pending |
| exercise-set | pending | pending | pending |
| exercise-line | pending | pending | pending |
| boulder-session | pending | pending | pending |
| boulder-problem | pending | pending | pending |
| boulder-attempt | pending | pending | pending |
| task | pending | pending | pending |
| goal | pending | pending | pending |

`cruddy` is a registered example entity; decide whether to retain or retire it.
Goals have dedicated screens and registered CRUD screens; review both until an explicit retirement decision.

## Generic heatmap screens

Each entity uses `/app/viz/<entity>`.
Lazy year fragments belong to the parent screen.

| Entity | Review |
|---|---|
| habit-log | pending |
| meditation-log | pending |
| bm-log | pending |
| medication-log | pending |
| reading-log | pending |
| project-log | pending |
| symptom-log | pending |
| mood-log | pending |
| exercise-session | pending |
| boulder-session | pending |

## Entity timer screens

These remain registered alongside the unified workspace.

| Screen | Route | Review |
|---|---|---|
| Project timer | `/app/timer/project-log` | pending |
| Meditation timer | `/app/timer/meditation-log` | pending |
| Reading timer | `/app/timer/reading-log` | pending |

## Shared and embedded surfaces

Review these in their parent screens.
Add newly discovered states during each screen's brief.

| Surface | Important states | Review |
|---|---|---|
| Navigation shell | Sidebar, mobile tabs, menus, quick add, current destination | pending |
| CRUD list controls | Search, filters, pagination, view selection, empty results | pending |
| Shared form controls | Choices.js, changed-field highlighting, date/time, scales, validation | pending |
| Inline relation creation | Open, invalid, saved, cancelled; entity-specific exceptions | pending |
| Home timeline | Empty/populated, expanded entries, edit links, active timers, pinned goals | pending |
| Today and focus | Quick add, completed/unfinished, expanded details, defer/snooze | pending |
| Timers | Idle/running/multiple, location, relocate, recent logs | pending |
| Goal editor and detail | Measure/filter selection, suggestions, progress/history, preferences | pending |
| Workout | Idle/running/ended, sets, line add/edit, notes, validation | pending |
| Bouldering | Idle/running/ended, attempts, problem selection and filters | pending |
| Calendar | Date selection, event creation, validation | pending |
| Charts | Empty/populated, filters, tooltips, loading/failure | pending |
| Authentication feedback | Invalid/expired code or link, signed-out redirects | pending |
| Global feedback | Errors, missing records, confirmations, PWA install banner | pending |

## Planned screens

Add concrete routes and states when implementations define them.

| Screen | Work unit | Review |
|---|---|---|
| Generic statistics | [095](./095-generic-entity-statistics.md) | pending; planned |
| Inbox quick capture | [093](./093-inbox-quick-capture.md) | pending; planned |
| Data export | [078](./078-data-export.md) | pending; planned |
| Revised daily plan | [094](./094-today-plan.md) | pending; planned replacement of Today |

## Evidence and artifacts

- Route sources: `src/tech/jgood/gleanmo/app.clj`, `home.clj`, and registered `app/*` route definitions.
- Route generators: `crud/routes.clj`, `viz/routes.clj`, and `timer/routes.clj`.
- `e2e/scripts/manifest.ts` remains the curated visual-timeline sample, not this exhaustive checklist.
- Briefs and iterations: `mockups/<yyyy-mm>-essential-redesign/`, following `mockups/README.md`.
