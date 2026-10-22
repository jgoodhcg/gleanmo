---
title: "AI Assistance Integration"
status: active
description: "Restore task use through conversational backlog cleanup and daily planning, starting with scoped agent access and approved batch changes"
created: 2026-07-11
updated: 2026-10-22
tags: [integration, ai, mcp, api, security, llm-context]
priority: high
---

# AI Assistance Integration

## Intent

Let external agentic tools — CLI agents (e.g. opencode, Claude Code) and chat surfaces like open-web-ui — read from and write to Gleanmo so the user can offload daily planning and analysis workflows to an AI assistant.

The motivating use cases:

1. **Day planning**: ask an agent "what of my todo list is worth doing today?" — the agent reads tasks, reasons about priority/energy/context, and writes back state changes (move a task into the today section, mark one done, defer another).
2. **Exercise analysis**: ask an agent to review recent exercise logs and surface patterns, soreness risk, or progression suggestions.

Current use favors external reminders because manual backlog review and
organization cost more than the task system currently returns. Keep Gleanmo
tasks available, but defer standalone task-interface polish until agent access
tests whether conversational backlog management makes them useful again.

The first delivery slice must let an agent read the actionable backlog, discuss
its organization, preview a batch update, and apply only the approved changes.
Renewed task use validates this product hypothesis; API functionality alone does
not.

The hard constraint is **sensitivity**. Gleanmo holds medical, mood, and other private data. The integration must default to denying sensitive entities and never exfiltrate fields the user has marked sensitive, even when an agent requests them.

### Companion CLI Utility

A companion CLI (`gleanmo-agent`) distributed alongside the API, with its installation instructions and download URL exposed through agent discovery.
The original proposal was a lightweight TypeScript script; a downloadable Rust binary is now a serious candidate (see the 2026-10-18 proposal below).
The CLI wraps the authenticated API with explicit commands:

```sh
# After generating a token in the web app:
gleanmo-agent config set-token gmn_xxxx
gleanmo-agent tasks list --today
gleanmo-agent tasks complete <id>
gleanmo-agent exercise recent --days 7
```

The CLI is optional — agents can curl the API directly. It exists to reduce friction for ad-hoc scripting and to serve as a reference client that documents the API contract in executable form.

## Specification

### Integration Surface (TBD — see Open Questions)

Two leading options, not mutually exclusive:

1. **MCP server** — a Model Context Protocol server that exposes Gleanmo as tools/resources to any MCP-compatible client (Claude Desktop, open-web-ui, CLI agents that speak MCP). Preferred for broad compatibility.
2. **Scoped HTTP/CLI API** — a thin authenticated API (or CLI wrapper around it) that agents can call directly. Simpler to build and debug; useful for agents that don't speak MCP.

Either path must route all data access through `db/queries.clj` and all writes through `db/mutations.clj` — no direct XTDB access from the integration layer (per project rules).

### Operations

**Reads** (scoped per entity type):
- List tasks (with state filters), today-section tasks, projects, recent exercise logs/sessions, habits, calendar events.
- Fetch a single entity by id.
- Aggregate/summary endpoints tuned for agent context (e.g. "today's plan" bundle: tasks due, today tasks, recent sleep/exercise).

**Writes** (narrow, allow-listed):
- Update task state (`inbox`/`now`/`later`/`waiting`/`done`/`canceled`), daily selection, and deferral.
- Create/update non-sensitive logs where it makes sense (e.g. log a quick note).
- Writes must respect the same validation/malli schemas as the web UI.

### First Vertical Slice: Agent-Assisted Task Backlog

- Make the complete permitted actionable backlog accessible through bounded pages, without silently truncating the agent's review.
- Support discussion and proposed reorganization before any write occurs.
- Preview every proposed task change as a reviewable batch.
- Apply only the approved batch through allow-listed task mutations.
- Preserve unrelated fields and reject stale or invalid changes.

### Task Adoption Sequence (2026-09-07)

The task backlog was populated, then abandoned because it felt overwhelming.
The first task milestone must reduce review effort and support renewed use.

1. **Reset:** Identify possible duplicates, obsolete commitments, and unclear tasks through conversation.
2. **Choose:** Suggest one useful task or a short daily list using the user's available time, energy, and stated commitments.
3. **Adjust:** Reconsider selections when plans change, without requiring a full backlog review.
4. **Apply:** Preview the proposed changes, then apply the approved batch.

Present ambiguous commitments in small groups.
Do not treat age, snooze counts, or state churn as proof of importance or obsolescence.
Do not require classification of the whole backlog before daily planning can begin.
Keep deferred tasks discoverable without placing the full backlog in each response.

Use existing task states: `inbox`, `now`, `later`, `waiting`, `done`, and `canceled`.
Use `:task/focus-date` and `:task/focus-order` for daily selection.
Keep snoozing and hard due dates separate from daily selection.
Candidate cleanup actions include clarifying labels, assigning existing projects, deferring tasks, and canceling confirmed obsolete commitments.
Confirm the initial field allow-list before implementation.
Duplicate detection produces suggestions; record merging and deletion are outside this first slice.

Deliver task reads and approved updates before broader exercise access or optional CLI packaging.
Select one initial integration surface; a second transport is not required to test task adoption.
Keep token authorization and sensitivity controls in the first delivery.

### Interim CLI Workflow and Priority (2026-10-04)

Priority raised from medium to high on 2026-10-04.
The backlog-cleanup flow this unit describes is already in use as a dev-only stopgap.
That stopgap works, but it now has two uses and still needs friction this unit would remove.

The stopgap is `dev/tasks/task_entities.clj`, registered in `dev/tasks.clj` as two Biff tasks:

- `export-tasks` writes the user's open tasks to `tmp/task-export-<target>.edn`.
- `apply-task-changes` validates an EDN plan of `:update`, `:delete`, and `:create` entries.
  It prints a dry run, and with `--commit` it writes the whole plan in one transaction.

Uses so far:

1. 2026-09-18: three rounds of label cleanup, plus moving one-off Apple Reminders into tasks.
2. 2026-10-04: another Reminders cleanup and task refresh.

The workflow already proves the shape of the first vertical slice: export, discuss, preview the batch, apply only what was approved.
Its friction is what this unit removes:

- It needs a JVM, the repo, and a hand-copied prod `XTDB_JDBC_URL` in local `config.env`.
- Prod runs connect straight to the database from the developer's machine, with no token scoping or sensitivity boundary.
- The agent writes plan files by hand, and the user runs each step.

Carry its safety checks into the API's batch contract.
The label check rejects an id that points at the wrong task.
State-change signals are derived the same way the app derives them.
A plan with any invalid entry writes nothing.
These checks are a concrete starting point for the open question on stale-change rejection.

### API v1 (2026-10-08)

Decisions made on starting the build, answering several open questions below:

- **Surface:** HTTP JSON first (`/api/v1`, `src/tech/jgood/gleanmo/api.clj`). MCP is a later thin layer over the same handlers.
- **Tokens:** `api-token` entity; opaque `gmn_` + 32 random bytes, stored as SHA-256 only, plaintext shown once. Managed at `/app/api-tokens`. Revocable; `last-used-at` refreshed at most every 5 minutes.
- **Permissions:** per entity *group* (`api.access/groups`), none/read/write, plus `sensitive-granted` and `archived-granted`. Health data (mood, symptoms, medication, BM) are ordinary groups, off unless granted. `user`, `ical-url`, `api-token` belong to no group and are unreachable.
- **Reads** reuse `all-entities-for-user` (now with `:where` equality and `:text` substring filters) and the new `visible-entity-for-user`, so parent-sensitivity exclusion applies. Lists exclude sensitive/archived unless the request asks *and* the token is granted; asking without the grant is a 400. By-id reads see whatever the token is granted.
- **Writes:** one endpoint, `POST /api/v1/tx`, taking `create | update | delete` ops — the user proposed raw XTDB actions with one checker; our own op format was chosen instead because `put` replaces whole documents and `evict`/`fn` cannot be checked meaningfully. Every op is checked (type writable, record visible to the token, own non-hidden fields only, relation ids owned by the user, sensitive/archived flags need the grant, malli schema) and the batch writes all-or-nothing via `write-entities!`. `dry_run` returns per-field `from`/`to` changes for review. Optional per-op `expect` rejects stale edits — the answer to the stale-change question. Deletes are soft. Task state/snooze signals are derived as the app derives them.
- `GET /api/v1/types` describes the token's grants and every readable type's fields, enum values, scales and relations, so agents do not guess field names.

2026-10-12 review of token setup and use (decision record: `.decisions/api-token-reveal.json`):

- Token create posts with HTMX and returns the plaintext as a fragment; a refresh previously re-submitted the form and minted a second token. The token arrives masked with Reveal/Copy. Session flash, browser-generated tokens, a server-side one-time store, and a 303 carrying a body were considered and are recorded there.
- The stored `prefix` (`gmn_` + 8 of 43 characters) is not the token; the callout copy now says a hash and the first few characters are kept.
- Every create in `db/mutations.clj` uses `:db/op :create`, so a create over an existing id fails at commit (API: 409) instead of replacing the document. Client-supplied create ids stay: batch references and safe retries.
- A dry run reports `id: null` for a create whose id the server will generate.

Deferred: MCP, companion CLI and discovery endpoint, aggregate reads (today bundle, exercise composite), an audit log of agent writes, token expiry, rate limiting, escalation-mode sensitivity.

### Agent discovery (2026-10-20)

Answers the 2026-10-13 dogfood note below. Decision record: `.decisions/agent-discovery.json`, chosen by the agent under delegation and approved by the user 2026-10-22. The guide documents the two-batch workaround for the same-batch reference defect below.

> "I want to type in an easy-to-remember url, have the agent read instructions, then walk me through setup. If there are standards for this, let's use them." (user, 2026-10-15)

Built on branch `agent-discovery`. All of it is generated from `api.access/groups` and the handlers' limits (`api/limits`), with no user data on any public URL:

- `/agents`, mirrored at `/llms.txt` (llms.txt convention), is a plain-text setup guide and API reference. An agent sends the user to `/app/api-tokens`, has them store the token in `GLEANMO_TOKEN` themselves, saves the skill, and verifies with `/types`.
- `/agents/SKILL.md` is an Agent Skills file for harnesses that load skills.
- `GET /api/v1` is an unauthenticated index. `/api/v1/openapi.json` is generated OpenAPI 3.1, which Open WebUI tool servers and similar importers can use. `/.well-known/api-catalog` (RFC 9727) links both.
- A 401 now carries `WWW-Authenticate: Bearer` and a `docs` link to `/agents`.
- The token page opens with "tell your agent: set up Gleanmo, see `<base>/agents`". A new token's callout carries a copyable prompt naming the grants, never the token.

Deferred:
- MCP. Remote MCP auth means OAuth with an authorization server that mints tokens, which needs its own decision. It is the only route to chat-only surfaces such as Claude.ai connectors.
- The companion CLI (see "Downloadable Rust CLI candidate" below). If it is built, `/agents` should offer it as a setup option. Its planned `/.well-known/agent-cli` endpoint is no longer needed, since `/agents` is the discovery entry point.

### Performance monitoring (added 2026-10-12)

> "can you also add that performance monitoring should be part of the ai api
> integration?" — user, 2026-10-12

Two parts, both confirmed by the user 2026-10-12:

> "I meant what you interpreted but also we should be performance onitoring the
> api endpoints as well add that to the roadmap"

1. **Read access.** An agent with a granted token can
read performance monitoring data, the persisted `performance-report` snapshots
and per-route aggregates from [021-performance.md](./021-performance.md), so
it can answer "what got slower since the last deploy?". This is a read-only
group, like Cronometer data in
[056-cronometer-integration.md](./056-cronometer-integration.md), restricted to
super users as the dashboard is today.
Add Performance monitoring to the `/app/api-tokens` permission controls.
Only super users can grant it, and an ungranted token cannot read monitoring data.
Expose the granted permission through token discovery alongside the existing groups.
Verify that issuing and editing a token preserve the read-only and super-user restrictions.

2. **Instrument the API.** `/api/v1` requests are profiled like app routes, so
agent traffic appears on the performance dashboard. `obs/wrap-request-profiling` sits in
`wrap-base-defaults` (`middleware.clj`), which likely already covers
`/api/v1` — verify. If it does, the work is making them distinguishable (per endpoint and per op type for `tx`,
not one bucket per path) and confirming they show on the dashboard.

Dependency: the data is only as good as
[076-performance-regression-tracking.md](./076-performance-regression-tracking.md)
makes it. Today snapshots are manual and per instance, so an agent would read
the same partial picture the dashboard shows.

### Adoption Check

After the initial cleanup, evaluate one week of normal use with the user.
Record whether the user returns voluntarily, chooses useful tasks, and reports less review effort.
Ask whether AI conversations themselves create another review burden.
Use a brief user review; no new analytics system is required.
If use stops again, identify the remaining friction before expanding task features.

Defer Things-style tags, project headings, recurrence, and additional statistics until use demonstrates a need.
Prioritize fast capture, easy deferral, and a manageable daily list within the existing model.
Evaluate carry-forward through [012-daily-focus.md](./012-daily-focus.md).
Offline capture is now part of [093-inbox-quick-capture.md](./093-inbox-quick-capture.md), built offline-first (decided 2026-10-05).

### Sensitivity Model

- Default: **sensitive entities are excluded** from all reads, regardless of request. The integration uses `resolve-user-settings` with `show-sensitive` forced to `false` unless an explicit, deliberate escalation flow is invoked.
- An opt-in "sensitive mode" may exist for trusted, local-only contexts (e.g. CLI on the same machine), gated behind explicit user action and never enabled by default for remote/open-web-ui clients.
- The `sensitive` flag on each entity type is the source of truth; the integration does not invent a second sensitivity taxonomy.
- Entity types that are inherently sensitive (mood, medication, bm-log, symptom) may be excluded from the read allow-list entirely by default.

### Auth

- Token-based auth for API/MCP clients, scoped to a single user, revocable.
- Tokens stored as hashed values (never plaintext) — only the prefix/suffix shown to the user for recognition (like `gmn_xxxx...yyyy`).
- Each token carries **granular permissions**, configurable at creation time and editable afterward:
  - Per entity type (tasks, exercise, habits, mood, etc.)
  - Read vs. write per entity type
  - Sensitivity override: `default-exclude` (treat as sensitive), `include` (allow sensitive entities), `require-escalation` (ask on each request)
  - Archived entity visibility: `exclude` (default), `include`
- Tokens created/managed in-app via a dedicated settings page (or a modal in the existing settings area).
- No session reuse with the web app — separate credential surface, scoped to only what the token permits.

## Validation

- [ ] A batch creating projects and goals referencing those projects passes both dry-run and commit validation.
- [ ] Same-batch relation checks preserve ownership, type, and permission restrictions; rejected batches write nothing.
- [ ] Read tools return only allow-listed entity types
- [ ] Sensitive entities are excluded by default; verified with a fixture containing both sensitive and non-sensitive docs
- [ ] Write tools reject fields outside the allow-list and validate against malli schemas
- [ ] Auth rejects unauthenticated requests and authorizes only the token's user
- [ ] Editing an existing token's permissions changes access on subsequent API requests while keeping the same bearer token.
- [ ] Permission edits support granting and removing access; another user's tokens cannot be edited.
- [ ] E2E flow: an MCP/CLI client can list today's tasks and mark one complete
- [ ] An agent can read the actionable backlog, discuss its organization, preview a batch update, and apply the approved changes
- [ ] An unapproved proposal leaves task data unchanged; an approved batch preserves unrelated fields and rejects stale changes
- [ ] A planning conversation can produce a useful next action without classifying the entire backlog
- [ ] The one-week adoption review records whether task use resumed and which friction remains
- [ ] E2E flow: an MCP/CLI client can fetch recent exercise logs without leaking sensitive sibling entities

## Scope

### In scope
- A read/write integration surface (MCP and/or API) for a curated set of entity types
- Sensitivity-aware read filtering using existing `sensitive` flags and `resolve-user-settings`
- Token auth for external clients with granular entity-type-level permissions (read/write per type, sensitivity visibility, archive visibility)
- In-app token management UI (create, list, edit permissions, revoke)
- Companion CLI utility in TypeScript wrapping the HTTP API
- Discovery endpoint at `/.well-known/agent-cli` serving CLI download/install instructions
- Task state mutations (the day-planning use case)
- Exercise read access (the analysis use case)

### Out of scope
- In-app AI / chat UI inside Gleanmo itself
- Vector embeddings / semantic search over Gleanmo data
- Writing back to Airtable, Cronometer, Roam, or other external systems
- Exposing raw XTDB query capability to agents
- Real-time/streaming subscriptions
- Multi-user sharing or collaborative access

## Context

- DB access rules: `src/tech/jgood/gleanmo/db/queries.clj`, `src/tech/jgood/gleanmo/db/mutations.clj` — integration must call these, never `xt/q`/`xt/entity` directly.
- Sensitivity resolution: `resolve-user-settings` in `db/queries.clj:59`; the `sensitive` boolean field on participating entity schemas.
- Task schema and states: `src/tech/jgood/gleanmo/schema/task_schema.clj`.
- CRUD patterns (for mirroring write validation): `src/tech/jgood/gleanmo/crud/forms/converters.clj`, `src/tech/jgood/gleanmo/crud/forms/inputs.clj`.
- Related: `056-cronometer-integration.md` (LLM-context data layer), `026-roam-integration.md` (external read integration precedent), `012-daily-focus.md` (today planning ritual this would augment).
- Interim path: [078-data-export.md](./078-data-export.md) and its exercise composite ([075-exercise-export.md](./075-exercise-export.md)) feed the health-coach project by copy-paste until this ships. The composite `assemble` functions defined there are the natural bodies for this unit's aggregate read endpoints — reuse them rather than writing a second assembly layer.

## Open Questions

- [ ] MCP server vs. HTTP/CLI API first? Build both, or pick one? Recommend MCP-first for client compatibility, with a thin HTTP fallback.
- [ ] Which entity types are on the read allow-list by default? (tasks, projects, exercise, habits, calendar — yes; mood, medication, bm-log, symptom — likely no by default.)
- [ ] Which entity types are writable from agents? Tasks almost certainly; logs maybe; anything else?
- [ ] Which cleanup fields join state, focus-date, focus-order, and snoozing in the first write allow-list?
- [ ] Token management UX — where in the app does the user create/revoke tokens?
- [ ] Should the MCP server run as a separate process (long-lived) or be spawned on demand by clients?
- [ ] For local CLI use, is a loopback-only transport acceptable to loosen sensitivity defaults, or keep one policy everywhere?
- [ ] Rate limiting / abuse protection — needed for a personal app, or defer?
- [ ] Should agent writes be auditable (a log of what an agent changed)?
- [ ] How should an approved batch reject stale tasks: expected values, entity versions, or another optimistic-locking contract?
- [ ] Companion CLI — evaluate an in-repo Rust binary alongside the original TypeScript option. Choose release platforms, GitHub Releases vs. DigitalOcean Spaces distribution, token configuration, and JSON command / MCP mode contracts.
- [ ] Token permission UI — what does the permission matrix look like in the form? A checkbox grid (entity types × read/write) with sensitivity/archive toggles?
- [ ] Token format — `gmn_` prefix with what payload? Random bearer string with server-side permission lookup, or a self-contained signed JWT? Recommending opaque random with server-side lookup for revocability without a blacklist.

## Notes

### Token permission editing (2026-10-18)

Add the ability to change an existing API token's permissions from `/app/api-tokens`.
Reuse the existing permission controls: none/read/write per entity group, plus sensitive and archived access grants.
Keep the same bearer token so clients do not need a replacement credential or configuration change.
Saved permission changes must apply to subsequent API requests, including removal of previously granted access.
This is a requested follow-up, not an implemented capability.

### Token expiry (2026-10-16)

From the security audit ([099-security-audit.md](./099-security-audit.md),
finding 10): API tokens last until revoked; `api-token` has `revoked-at` but no
end date. Add an optional `expires-at` chosen at creation (e.g. 30 / 90 days /
never), shown on `/app/api-tokens`, and refuse expired tokens in the API's
token lookup. Low priority — revocation already works and `last-used-at` shows
stale tokens.

### Dogfood notes (2026-10-13)

Raised during the Cronometer production rollout ([056-cronometer-integration.md](./056-cronometer-integration.md)).

**Standardized agent discovery / initial prompt.**

> "I think I should probably have some kind of standardized ai discovery of hte api or initial prompt."

Today an agent is told how to start only by one sentence on `/app/api-tokens`
(`app/api_tokens.clj:192`): use `Authorization: Bearer gmn_…` and "Start at
`GET /api/v1/types`". `/types` (`api.clj` `types-handler`) already describes the
token's grants, each readable type's fields, enums, scales and relations, and
the nutrient table — but nothing says how to page lists, what `since` needs,
how `/tx` ops and `dry_run`/`expect` look, or that read-only groups exist. An
agent learns those from 400 messages.

Decided and built 2026-10-20; see "Agent discovery" above. Original candidates: an unauthenticated `GET /api/v1` (or
`/.well-known/…`) index naming endpoints and auth; an OpenAPI document; an
`llms.txt`-style plain-text guide; a copyable "initial prompt" on the token page
filled in with the base URL and the token's grants. This subsumes the deferred
"discovery endpoint" above, and an MCP layer would reuse whatever it settles on.

### Downloadable Rust CLI candidate (2026-10-18)

Extend the standardized discovery proposal above with a serious evaluation of a Rust CLI built and maintained in this repository.
Current API use requires agents to assemble arcane shell commands and ad hoc Python scripts.
Provide a documented set of functional calls so API use is easier to discover, review, and audit later.
This is a proposed implementation option, not a language or hosting decision.

- Publish prebuilt binaries for the selected platforms through GitHub Releases, DigitalOcean Spaces, or another download host.
  Discovery must identify the supported version, platform download, integrity checksum, and installation instructions.
- Accept an existing scoped API bearer token through documented configuration, environment, or stdin input.
  Keep tokens out of command output, audit records, and examples containing real credentials.
  Reuse server permissions and validation; the binary must not access the database directly.
- Provide explicit named commands for API discovery, type/schema inspection, entity reads, transaction preview, and approved transaction application.
  Return machine-readable JSON with documented errors and exit codes, and keep diagnostic output separate.
- Offer an MCP server mode over the same API operations for clients that use MCP.
  JSON command mode and MCP tools must share operation names and semantics where possible.
- Make calls reviewable: expose the operation, target, filters, and proposed changes without hiding extra writes behind a command.
  Carry forward `dry_run` and `expect` checks, and define how an invocation maps to a later server audit record without logging tokens or private payloads by default.
- Keep discovery, command help, JSON examples, and MCP tool descriptions aligned with the API contract.
  Evaluate whether an agent can install the binary, inspect its grants, read a bounded page, preview a batch, and apply an approved batch without writing shell or Python glue.

Compare Rust packaging and maintenance costs with the original TypeScript proposal.
Settle supported platforms, download hosting, update/version compatibility, and the audit contract before implementation.

### Generic imported entity writes (2026-10-13)

**Agent writes to generic imported entities.**

> "food logs, biometrics, and activities are meant to be generic things. Eventually I might want api to allow writes to those."

Since `674cced` the `food`, `food-log`, `nutrition-day`, `biometric-log` and
`activity-observation` schemas are provider-neutral (every `:cronometer/*`
attribute optional). The API still marks the `:nutrition` and `:biometrics`
groups `:read-only` as a whole (`api/access.clj` `groups`), because the next
sync overwrites a synced row an agent edited.

Interpretation: writes become safe per document rather than per group. Rows a
sync owns (carrying provider lineage) stay read-only; rows created by the app or
an agent are writable. User-managed fields on synced rows are the exception
already in practice: the sync leaves `activity-observation/exercise-session-id`
alone (`sync/user-managed-keys`), so that link could be writable even on a
synced row. Needs a decision on how ownership is detected (lineage presence vs.
an explicit `source`), how `/types` reports per-document writability, and how
`can?` changes from type-level to document-level checks.

### Same-batch references rejected after successful dry run (2026-10-18)

An agent reported that a batch created projects and goals referencing those projects.
The dry run passed, but the server rejected the write; nothing was written.
Splitting the work into two batches succeeded: create the referenced records first, then create the goals.
The agent reported adding this workaround to its skill.
This report has not been independently reproduced.

The API already documents client-supplied create IDs for references within the same batch (`api.clj`, `tx-handler`).
Treat the reported rejection as a validation defect against that contract.
Investigate the difference between API preflight checks and commit-time write rules, including goal-to-project validation.
Dry-run and commit validation must agree on same-batch references when database state and permissions remain unchanged.
Preserve ownership, type, permission, and all-or-nothing write guarantees.

Add regression coverage for a project and a referencing goal created together, plus invalid and unauthorized relation targets.
Document the two-batch workaround in agent discovery and future CLI/MCP guidance until the defect is fixed.
The workaround uses two transactions; it does not preserve atomicity across both batches.

### Dogfood notes (2026-10-17)

**BM logs on the token form.**

> "Bm logs show when toggles off in api token creation"

Captured from the user's task inbox; the exact meaning is not yet confirmed.
Likely reading: on `/app/api-tokens` the "BM logs" group row (`api/access.clj`
`groups`, `:bm`) still shows when a toggle is off, probably the sensitive
toggle, since BM data is personal health data. Confirm with the user which
toggle, and whether the row should be hidden, disabled, or flagged.

### Use case sketches (2026-07-11)

**Day planning.** The user asks, "What is worth doing today?"
The agent reads permitted active tasks and discusses available time, energy, and commitments.
It previews changes to existing task states and daily selection fields, then applies the approved batch.
Sensitive tasks remain excluded by default.

**Exercise analysis.** User asks: "Look at my recent exercises and tell me if I'm overtraining." The agent calls `list-exercise-sessions` (last N days), reads associated sets, and produces a summary. Mood and medication data — which would help the analysis — are sensitive and excluded by default; the user can opt in per-session if desired.
