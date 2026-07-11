---
title: "AI Assistance Integration"
status: draft
description: "Expose Gleanmo data to external agentic tools (CLI agents, open-web-ui) via MCP and/or a scoped API for read/write with strict sensitivity controls"
created: 2026-07-11
updated: 2026-07-11
tags: [integration, ai, mcp, api, security, llm-context]
priority: medium
---

# AI Assistance Integration

## Intent

Let external agentic tools — CLI agents (e.g. opencode, Claude Code) and chat surfaces like open-web-ui — read from and write to Gleanmo so the user can offload daily planning and analysis workflows to an AI assistant.

The motivating use cases:

1. **Day planning**: ask an agent "what of my todo list is worth doing today?" — the agent reads tasks, reasons about priority/energy/context, and writes back state changes (move a task into the today section, mark one done, defer another).
2. **Exercise analysis**: ask an agent to review recent exercise logs and surface patterns, soreness risk, or progression suggestions.

The hard constraint is **sensitivity**. Gleanmo holds medical, mood, and other private data. The integration must default to denying sensitive entities and never exfiltrate fields the user has marked sensitive, even when an agent requests them.

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
- Update task state (todo/doing/done, today-section membership, defer).
- Create/update non-sensitive logs where it makes sense (e.g. log a quick note).
- Writes must respect the same validation/malli schemas as the web UI.

### Sensitivity Model

- Default: **sensitive entities are excluded** from all reads, regardless of request. The integration uses `resolve-user-settings` with `show-sensitive` forced to `false` unless an explicit, deliberate escalation flow is invoked.
- An opt-in "sensitive mode" may exist for trusted, local-only contexts (e.g. CLI on the same machine), gated behind explicit user action and never enabled by default for remote/open-web-ui clients.
- The `sensitive` flag on each entity type is the source of truth; the integration does not invent a second sensitivity taxonomy.
- Entity types that are inherently sensitive (mood, medication, bm-log, symptom) may be excluded from the read allow-list entirely by default.

### Auth

- Token-based auth for API/MCP clients, scoped to a single user, revocable.
- Tokens stored as hashed values (never plaintext), created/managed in-app.
- No session reuse with the web app — separate credential surface.

## Validation

- [ ] Read tools return only allow-listed entity types
- [ ] Sensitive entities are excluded by default; verified with a fixture containing both sensitive and non-sensitive docs
- [ ] Write tools reject fields outside the allow-list and validate against malli schemas
- [ ] Auth rejects unauthenticated requests and authorizes only the token's user
- [ ] E2E flow: an MCP/CLI client can list today's tasks and mark one complete
- [ ] E2E flow: an MCP/CLI client can fetch recent exercise logs without leaking sensitive sibling entities

## Scope

### In scope
- A read/write integration surface (MCP and/or API) for a curated set of entity types
- Sensitivity-aware read filtering using existing `sensitive` flags and `resolve-user-settings`
- Token auth for external clients
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
- Related: `cronometer-integration.md` (LLM-context data layer), `roam-integration.md` (external read integration precedent), `daily-focus.md` (today planning ritual this would augment).

## Open Questions

- [ ] MCP server vs. HTTP/CLI API first? Build both, or pick one? Recommend MCP-first for client compatibility, with a thin HTTP fallback.
- [ ] Which entity types are on the read allow-list by default? (tasks, projects, exercise, habits, calendar — yes; mood, medication, bm-log, symptom — likely no by default.)
- [ ] Which entity types are writable from agents? Tasks almost certainly; logs maybe; anything else?
- [ ] How is "today section" membership represented for task writes — is there an existing field/flag, or does it need one?
- [ ] Token management UX — where in the app does the user create/revoke tokens?
- [ ] Should the MCP server run as a separate process (long-lived) or be spawned on demand by clients?
- [ ] For local CLI use, is a loopback-only transport acceptable to loosen sensitivity defaults, or keep one policy everywhere?
- [ ] Rate limiting / abuse protection — needed for a personal app, or defer?
- [ ] Should agent writes be auditable (a log of what an agent changed)?

## Notes

### Use case sketches (2026-07-11)

**Day planning.** User asks an agent: "What of my todo list is worth doing today?" The agent calls a `list-tasks` tool (filtered to `todo`/`doing` states), reasons about priority and energy, then calls `update-task` to move chosen tasks into the today section and optionally marks one as `doing`. Sensitivity: tasks may carry sensitive notes; by default the integration should exclude sensitive tasks unless escalated.

**Exercise analysis.** User asks: "Look at my recent exercises and tell me if I'm overtraining." The agent calls `list-exercise-sessions` (last N days), reads associated sets, and produces a summary. Mood and medication data — which would help the analysis — are sensitive and excluded by default; the user can opt in per-session if desired.
