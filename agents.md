# Shared Agent Guidelines

This is the source of truth for all AI agents (Claude, Gemini, Codex, etc.) working on this project.

## Workflow Preferences

- **One Step at a Time:** Perform a single logical task, then stop and ask for validation/feedback. Do not chain multiple feature implementations or fixes together.
- **Validation First:** Always reflect on the current state and plan before executing.
- **User Control:**
    - **Never** run the dev server (`clj -M:dev dev`) automatically.
    - **Never** start a REPL (`clj -M:repl`, `lein repl`) automatically.
    - **Never** run any long-running processes.
    - **Never** assume the state of the database; ask the user to verify if unsure.
- **Roadmap Driven:** The roadmap lives in `roadmap/` with `index.md` as canonical state and `README.md` as the catalog. Reference it frequently when planning features.

## UI/UX Patterns

### Changed Field Highlighting
When implementing CRUD forms, the system automatically highlights fields that have been modified by the user.
- **Requirement:** All form inputs must have a `data-original-value` attribute containing their initial server-side value.
- **Implementation:** `src/tech/jgood/gleanmo/crud/forms/inputs.clj` handles this automatically for standard types. If creating custom inputs, ensure this attribute is present.
- **Frontend:** `main.js` handles the comparison and class application (`border-neon-cyan`). It supports standard inputs, textareas, and Choices.js widgets.

## Why Server/REPL Restrictions Exist

When AI agents run the dev server or REPL, it creates orphaned processes that:
- Lock the RocksDB database file
- Prevent the human developer from running their own server instance
- Require a complete computer restart to recover
- Cause significant workflow disruption

## Tech Stack

- **Language:** Clojure 1.11.1
- **Framework:** [Biff](https://biffweb.com/) (web framework)
- **Database:** [XTDB](https://xtdb.com/) (bitemporal database) with RocksDB storage
- **Frontend:** [Rum](https://github.com/tonsky/rum) (React wrapper) + [HTMX](https://htmx.org/) + [Tailwind CSS](https://tailwindcss.com/)
- **Charting:** [ECharts](https://echarts.apache.org/) with JSON config pattern
- **Authentication:** Email-based with reCAPTCHA protection
- **Time:** tick library for date/time handling
- **JSON:** cheshire for JSON encoding/decoding

## Allowed Verification Commands

The agent **IS** permitted to run these commands to verify code compilation and syntax:

### Primary Command (run after every change)

```bash
just check
```

This single command runs formatting check + linting and catches most issues: syntax errors, unresolved symbols, invalid arity, unused imports, and formatting problems. **Run this after every code change.**

### All Commands

| Command | Description |
|---------|-------------|
| `just check` | **Primary validation** - format + lint (run after changes) |
| `just validate` | Full validation - format + lint + tests |
| `clj -M:dev test` | Run all tests |
| `clj -M:dev test <namespace>` | Run tests for a specific namespace |
| `clj -M:cljfmt check src test` | Check code formatting |
| `clj -M:cljfmt fix src test` | Fix code formatting |
| `clj -M:lint --lint src --lint test` | Run clj-kondo linter |

## nREPL Usage (Clojure MCP Tools)

A running nREPL (typically port 7888) may be available via MCP tools (`clojure_eval`, `clojure_inspect_project`, etc.). This provides fast feedback without JVM cold-starts, but operates on the **live application state**.

### Safe (No Permission Needed)

These operations are pure and have no side effects:

- **Evaluate pure expressions** — arithmetic, string manipulation, data transformations
- **Validate data against Malli schemas** — `(malli.core/validate schema data)`, `(malli.core/explain schema data)`
- **Inspect loaded var metadata** — `(-> #'some-fn meta :arglists)`
- **Check if a symbol resolves** — `(resolve 'some.ns/some-fn)`
- **Read project structure** — `clojure_inspect_project`
- **List nREPL servers** — `list_nrepl_ports`

### Requires User Permission

**Ask before running** anything that could have side effects:

- **`(require ... :reload)`** — reloading namespaces may re-register routes, redefine multimethods, or alter system state
- **Any function that touches the database** — even read-only queries hit the live DB
- **Calling application functions** — unless you are certain they are pure (no IO, no atoms, no DB)
- **Anything involving atoms, refs, or agents** — state mutation
- **Running tests via nREPL** — tests may have DB fixtures or side effects; prefer `just validate` instead

### General Rules

1. **`just check` remains the primary validation command.** It is safe, isolated, and catches most issues.
2. **Use the nREPL for fast pure-expression feedback only** — e.g., verifying a data transformation works before committing to it.
3. **When in doubt, don't evaluate it.** Use `just check` instead.
4. **Never use `clojure_eval` as a substitute for running the dev server or REPL.** The same restrictions from "User-Only Commands" apply.

## User-Only Commands

The agent must **NOT** run these unless explicitly instructed:

| Command | Description |
|---------|-------------|
| `clj -M:dev dev` | Starts the development server |
| `clj -M:repl` | Starts an interactive REPL |
| `lein repl` | Starts an interactive REPL (alternative) |

## Project Structure

- `/src/tech/jgood/gleanmo/` - Core application code
  - `/app/` - Domain-specific modules (habits, meditation, etc.)
  - `/db/` - Database queries and mutations (single source for all db access)
  - `/schema/` - Data models and validation (Malli)
  - `/crud/` - Generic CRUD operations
  - `/viz/` - Visualization routes and chart generation
- `/resources/` - Configuration and static assets
- `/dev/` - Development utilities
- `/test/` - Unit and integration tests
- `/roadmap/` - Feature planning and requirements documentation

## Database Layer

**All database reads and writes must go through the db layer** (`/db/queries.clj` and `/db/mutations.clj`). This ensures:
- Consistent handling of user settings (sensitive, archived)
- Soft-delete filtering via `::sm/deleted-at`
- Single point of change if we ever port to a new database

When adding new queries:
1. Add the query function to `db/queries.clj`
2. Use `get-user-settings` to respect user preferences
3. Filter out deleted entities with `(not [?e ::sm/deleted-at])`
4. Call the db function from your route handler

**Do not** query XTDB directly from route handlers or view functions.

## Code Style Guidelines

- **Namespaces:** Use `tech.jgood.gleanmo.*` namespace structure
- **Naming:** Use kebab-case for functions/variables, PascalCase for records/types
- **Formatting:** Follows cljfmt conventions with custom indents in `cljfmt-indents.edn`
- **Structure:** Group related functions, public functions first followed by private
- **Imports:** Group in order: `clojure.*` > external libs > project namespaces
- **Documentation:** Docstrings for public functions explaining purpose and args
- **Error handling:** Use `try/catch` sparingly, prefer data-driven validation
- **Testing:** Write tests in `test/tech/jgood/gleanmo/`

## Styling Rules

- Do not use `px` values in Tailwind class names. Use Tailwind's named sizes (e.g., `text-xs`, `text-sm`) instead.

## Parens Fix Attempts

If you encounter a parentheses/brace compilation error, limit yourself to **three** fix attempts. If it still fails after three tries, pause and ask the human for guidance before proceeding.

## Emergency Recovery

If you accidentally run a server command and the database becomes locked:
1. Stop all agent processes immediately
2. The human developer will need to restart their computer
3. This is why the restrictions above are critical

## Schema Conventions

Schemas are defined using Malli in `/src/tech/jgood/gleanmo/schema/`. Each entity has its own file (e.g., `task_schema.clj`).

### Schema Structure

Every schema follows this structure:
```clojure
(ns tech.jgood.gleanmo.schema.my-entity-schema
  (:require [tech.jgood.gleanmo.schema.meta :as sm]))

(def my-entity
  [:map {:closed true}
   ;; System fields (always first, in this order)
   [:xt/id :my-entity/id]
   [::sm/type [:enum :my-entity]]
   [::sm/created-at :instant]
   [::sm/deleted-at {:optional true} :instant]
   [:user/id :user/id]

   ;; Entity-specific fields
   [:my-entity/label {:crud/priority 1} :string]
   [:my-entity/notes {:optional true :crud/priority 2} :string]
   ...])
```

Note: Legacy entities use `(concat sm/legacy-meta)` for Airtable migration compatibility. New entities don't need this.

### Standard Field Names

| Field | Type | Purpose |
|-------|------|---------|
| `:entity/label` | `:string` | Primary display name (required, priority 1) |
| `:entity/notes` | `:string` | Freeform text (optional, priority 2) |
| `:entity/sensitive` | `:boolean` | Hide when sensitive mode off |
| `:entity/archived` | `:boolean` | Hide when archived mode off |

### Log Entity Fields

Log entities track events with timestamps:

| Pattern | Fields | Use Case |
|---------|--------|----------|
| Point-in-time | `:entity-log/timestamp`, `:entity-log/time-zone` | habit-log, medication-log, bm-log |
| Time interval | `:entity-log/beginning`, `:entity-log/end`, `:entity-log/time-zone` | project-log, meditation-log |

### Field Metadata

Metadata in field options controls CRUD behavior:

| Metadata | Purpose |
|----------|---------|
| `:crud/priority N` | Form field order (1 = first, lower = higher) |
| `:crud/label "Label"` | Override form field label |
| `:optional true` | Field is not required |
| `:hide true` | Exclude from forms (for system or deprecated fields) |

### Schema-Level Metadata

Metadata on the map itself:

| Metadata | Purpose |
|----------|---------|
| `:closed true` | Required - Malli validates exact match |
| `:timer/primary-rel :field-key` | For timer entities, specifies primary relationship |

### Registration

After creating a schema, register it in `schema.clj`:
```clojure
:my-entity/id  :uuid           ;; Add ID type
:my-entity     ms/my-entity    ;; Add schema reference
```

## Adding New Field Types

When adding a new field type to the CRUD system, update these files in order:

### 1. Schema Registry (`src/tech/jgood/gleanmo/schema.clj`)

Add a Malli type definition:
```clojure
{:local-date [:fn t/date?]  ;; example for date type
 :my-type    [:fn my-predicate?]}
```

### 2. Form Input Renderer (`src/tech/jgood/gleanmo/crud/forms/inputs.clj`)

Add a `render` multimethod for the HTML input:
```clojure
(defmethod render :local-date
  [field _]
  (let [{:keys [input-name input-label input-required value]} field]
    [:div
     [:label.form-label {:for input-name} input-label]
     [:div.mt-2
      [:input.form-input
       (cond-> {:type "date", :name input-name, :required input-required}
         value (assoc :value (str value)))]]]))
```

### 3. Form Value Converter (`src/tech/jgood/gleanmo/crud/forms/converters.clj`)

Add a `convert-field-value` multimethod to parse form strings:
```clojure
(defmethod convert-field-value :local-date
  [_ value _]
  (when (not-empty value)
    (java.time.LocalDate/parse value)))
```

### 4. List Display Formatter (`src/tech/jgood/gleanmo/crud/views/formatting.clj`)

Add a `format-cell-value` multimethod for table display:
```clojure
(defmethod format-cell-value :local-date
  [_ value _]
  (if (nil? value)
    [:span.text-gray-400 "—"]
    [:span (str value)]))
```

### 5. Verify

Run `clj -M:dev test` to ensure everything compiles.

### Existing Field Types

| Type | HTML Input | Notes |
|------|------------|-------|
| `:string` | text/textarea | Auto-selects based on field name |
| `:boolean` | checkbox | |
| `:number` | number (step=any) | |
| `:int` | number (step=1) | |
| `:float` | number (step=0.001) | |
| `:instant` | datetime-local | Requires timezone handling |
| `:local-date` | date | No timezone needed |
| `:enum` | select | Options from schema |
| `:boolean-or-enum` | select | yes/no + enum options |
| `:single-relationship` | select | Loads related entities |
| `:many-relationship` | multi-select | Loads related entities |
