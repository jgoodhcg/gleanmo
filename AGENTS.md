# AGENTS

Follows `AGENT_BLUEPRINT.md` (version: 1.4.4)

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
- Determine `AI_PRODUCT_LINE` from current session:
  - Codex or ChatGPT coding agent -> `codex`
  - Claude -> `claude`
  - Gemini -> `gemini`
  - OpenCode -> `opencode` (regardless underlying provider/model, including z.ai)
- Determine `AI_PROVIDER` and `AI_MODEL` from runtime model metadata.
- `AI_PRODUCT_NAME` and `AI_PRODUCT_EMAIL` format:
  - `codex` -> `Codex <codex@users.noreply.github.com>`
  - `claude` -> `Claude <claude@users.noreply.github.com>`
  - `gemini` -> `Gemini <google-gemini@users.noreply.github.com>`
  - `opencode` -> `GLM <zai-org@users.noreply.github.com>`
- Fill this template at commit time; do not persist filled values in `AGENTS.md`.

## Validation Commands

| Level | Command | When | Cost |
|-------|---------|------|------|
| 1 | `just lint-fast` or `just lint-fast <files>` | **After every edit** — run aggressively | ~200ms |
| 2 | `clojure_eval` (pure reads only) | Quick data checks / schema validation | ~1s |
| 3 | `just check` | After completing a subtask or batch of changes | ~10-15s |
| 4 | `clj -M:dev test <namespace>` | Specific logic changes | ~20-30s |
| 5 | `just validate` | Before commits / significant changes | ~60s+ |
| 6 | `just e2e-screenshot /path` | After UI changes (requires user-run dev server) | varies |
| 7 | `just e2e-flow <name>` | User flow regression checks (requires user-run dev server) | varies |

### Environment Detection

At the start of a session, detect your environment by running:

```sh
which java && java -version 2>&1 | head -1
which clj-kondo
```

- **Full environment** (java + clj found): Use all validation levels (1-7) as described above.
- **Lint-only environment** (no java, clj-kondo found): Use Level 1 only. Defer Levels 3-7 to GitHub Actions CI.
- **Bare environment** (no java, no clj-kondo): Run `./script/setup-cloud-lint.sh` first, then operate as lint-only.

### Cloud Environment Setup

Remote/cloud agentic runtimes (Ubuntu 24+) that lack a JVM should run the setup script on first use:

```sh
./script/setup-cloud-lint.sh        # installs standalone clj-kondo to ~/.local/bin
export PATH="$HOME/.local/bin:$PATH"
```

This installs a ~30MB static binary with no JVM or Clojars dependency. After setup, `just lint-fast` works identically to local environments.

**Cloud agents must not run** Levels 2-7 commands (`clojure_eval`, `just check`, `clj -M:dev test`, `just validate`, e2e commands). These require a JVM and/or running dev server that cloud environments do not have.

### CI Feedback Loop (cloud agents)

After pushing a commit, cloud agents can check CI results using `gh`:

```sh
gh run watch --exit-status   # wait for the validate workflow to complete
gh run view --log-failed     # on failure, view only the failed step logs
```

The `validate` workflow cascades: **lint-fast → check → test → e2e**. A failure in any stage cancels downstream stages, so the first failure reported is the one to fix. After fixing, push again and re-check.

## Allowed Commands

- `just lint-fast` — fast standalone clj-kondo lint (primary validation, run after every edit)
- `just lint-fast <files>` — lint specific changed files only (fastest)
- `just check` — format + lint via JVM (periodic validation)
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
- Always include commit trailers (Co-authored-by, AI-Provider, AI-Product, AI-Model) using the template above.
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

## Decision Artifacts

- For high-impact or irreversible decisions, record a decision matrix in `.decisions/[name].json`.
- Use `matrix-reloaded` format for structured comparison.
- Do not run `matrix-reloaded` CLI commands from agent sessions; use project-provided matrix instructions/schema.
- Optional: add `.decisions/[name].md` for human-readable narrative context.
- Treat the JSON decision matrix as the authoritative record.

## User Profile (optional)

See `.agent-profile.md` (git-ignored) for interaction preferences. Create on project init or alignment.

## References

- For roadmap conventions and work-unit lifecycle, see `roadmap/README.md`.
- For canonical roadmap state, see `roadmap/index.md`.
- For decision records and optional matrix format, see `AGENT_BLUEPRINT.md` section `Decision Artifacts [BP-DECISIONS]`.

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
