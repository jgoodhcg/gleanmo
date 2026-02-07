# AGENTS

Follows AGENT_BLUEPRINT.md

## Project Overview

Gleanmo is a personal quantified-self web app built in Clojure 1.11.1 with Biff, XTDB (RocksDB for local development and PostgreSQL-backed in production), Rum + HTMX + Tailwind for UI, and ECharts for charting. It uses email-based auth with reCAPTCHA and tick/cheshire for time/JSON.

## Stack

- Clojure 1.11.1
- Biff + Rum + HTMX + Tailwind + ECharts
- XTDB (RocksDB locally; PostgreSQL-backed in production)
- Self-hosted web app

## Commit Trailer Template

Store a template, not concrete runtime values.

```text
Co-authored-by: [AI_PRODUCT_NAME] <[AI_PRODUCT_EMAIL]>
AI-Provider: [AI_PROVIDER]
AI-Product: [AI_PRODUCT_LINE]
AI-Model: [AI_MODEL]
```

Template rules:
- `AI_PRODUCT_LINE` must be one of: `codex|claude|gemini|opencode`.
- Determine `AI_PRODUCT_LINE` from current session.
- Determine `AI_PROVIDER` and `AI_MODEL` from runtime model metadata.
- Fill this template at commit time; do not persist filled values in `AGENTS.md`.

## Validation Commands

| Level | Command | When |
|-------|---------|------|
| 1 | `just check` | After every change |
| 2 | `clojure_eval` (pure reads only) | Quick data checks / schema validation |
| 3 | `clj -M:dev test <namespace>` | Specific logic changes |
| 4 | `just validate` | Before commits / significant changes |
| 5 | `just e2e-screenshot /path` | After UI changes (requires user-run dev server) |
| 6 | `just e2e-flow <name>` | User flow regression checks (requires user-run dev server) |

## Allowed Commands

- `just check` — format + lint (primary validation)
- `just validate` — full validation
- `clj -M:dev test` — run all tests
- `clj -M:dev test <namespace>` — run a specific test namespace
- `clj -M:cljfmt check src test` — formatting check
- `clj -M:cljfmt fix src test` — formatting fix
- `clj -M:lint --lint src --lint test` — clj-kondo lint
- `just e2e-install` — install Playwright and browsers (one-time)
- `just e2e-screenshot /path` — screenshot a route (requires dev server)
- `just e2e-screenshot-full /path` — full-page screenshot (requires dev server)
- `just e2e-flow <name>` — run a UI flow (requires dev server)

## Require Confirmation

- Any server, REPL, watcher, or long-running process
- Network calls, paid services, or anything that spends money
- Database writes, migrations, or data changes (including read-only queries on live DB)
- Publishing, deployments, or uploads
- Destructive commands or overwrites
- Writing outside the repo boundary
- Installing or upgrading dependencies
- Running unfamiliar scripts
- `(require ... :reload)` in nREPL

## Never Run

- `clj -M:dev dev` — dev server (user must run)
- `clj -M:repl` — REPL (user must run)
- `lein repl` — REPL (user must run)

## Project-Specific Rules

- Execute `ready` roadmap work units autonomously and self-validate before returning.
- Never commit without explicit user approval.
- Validation first: reflect on current state and plan before executing; run validation after changes.
- User control: never assume database state; ask the user if unsure.
- Roadmap driven: canonical roadmap lives in `roadmap/` with `index.md` as canonical state and `README.md` as catalog.

UI/UX patterns
- CRUD inputs must include `data-original-value` for changed-field highlighting.
- Standard inputs are handled by `src/tech/jgood/gleanmo/crud/forms/inputs.clj`; custom inputs must add the attribute.
- `main.js` applies the comparison and `border-neon-cyan` class for inputs, textareas, and Choices.js widgets.

Database layer
- All database reads and writes must go through `src/tech/jgood/gleanmo/db/queries.clj` and `src/tech/jgood/gleanmo/db/mutations.clj`.
- Use `get-user-settings` and filter out deleted entities with `(not [?e ::sm/deleted-at])`.
- Do not query XTDB directly from route handlers or view functions.

Code style
- Namespaces use `tech.jgood.gleanmo.*`.
- Kebab-case for functions/vars; PascalCase for records/types.
- Group imports: `clojure.*` > external libs > project namespaces.
- Docstrings required for public functions.
- Prefer data-driven validation over `try/catch`.

Testing philosophy
- Unit tests for logic and compilation verification; avoid brittle tests.
- E2E for user-facing flows and UI changes.

Styling rules
- Do not use `px` values in Tailwind class names; use named sizes.

Parens fix attempts
- Limit to three fixes; if still failing, stop and ask for guidance.

Schema conventions
- Schemas live in `src/tech/jgood/gleanmo/schema/` and follow the standard field ordering with `:closed true`.
- Register new schemas in `src/tech/jgood/gleanmo/schema.clj`.
- Add new field types by updating schema registry, input renderer, form converter, and list formatter in that order.

## References

- For roadmap conventions and work-unit lifecycle, see `roadmap/README.md`.
- For canonical roadmap state, see `roadmap/index.md`.
- For shared cross-project policy, see `AGENT_BLUEPRINT.md`.

## Key Files

- `AGENT_BLUEPRINT.md` — shared agent policy
- `AGENTS.md` — project-specific agent rules (this file)
- `roadmap/index.md` — canonical roadmap state
- `roadmap/README.md` — roadmap system documentation
- `roadmap/_template.md` — work unit template
- `src/tech/jgood/gleanmo/db/queries.clj` — database reads
- `src/tech/jgood/gleanmo/db/mutations.clj` — database writes
- `src/tech/jgood/gleanmo/crud/forms/inputs.clj` — CRUD input rendering
- `src/tech/jgood/gleanmo/crud/forms/converters.clj` — form value conversion
- `src/tech/jgood/gleanmo/crud/views/formatting.clj` — list formatting
