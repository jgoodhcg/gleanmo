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

| Command | Description |
|---------|-------------|
| `clj -M:dev test` | Run all tests |
| `clj -M:dev test <namespace>` | Run tests for a specific namespace |
| `clj -M:cljfmt check` | Check code formatting |
| `clj -M:cljfmt fix` | Fix code formatting |

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
  - `/schema/` - Data models and validation (Malli)
  - `/crud/` - Generic CRUD operations
  - `/viz/` - Visualization routes and chart generation
- `/resources/` - Configuration and static assets
- `/dev/` - Development utilities
- `/test/` - Unit and integration tests
- `/roadmap/` - Feature planning and requirements documentation

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
