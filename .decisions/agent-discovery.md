# Agent discovery

The JSON matrix (`agent-discovery.json`) is the authoritative record. This page gives the narrative.

The user wants to say "set up Gleanmo, see gleanmo.com/agents" to any agent.
The agent should then do the rest, apart from the user creating a token.

## What an agent finds

| URL | Auth | For |
|-----|------|-----|
| `/agents` (also `/llms.txt`) | none | The setup guide and API reference, as plain markdown. This is the entry point. |
| `/agents/SKILL.md` | none | An Agent Skill a harness saves, so future sessions know Gleanmo |
| `/api/v1` | none | JSON index of endpoints and auth |
| `/api/v1/openapi.json` | none | Generated OpenAPI 3.1, for tool importers |
| `/.well-known/api-catalog` | none | RFC 9727 linkset pointing at the two above |
| `/api/v1/types` and the rest | bearer | Unchanged; `/types` remains the per-token field reference |

None of the public URLs carry user data. They expose the API's shape, the fixed list of permission groups and types, and the base URL.

## Why not MCP yet

Remote MCP's standard auth is OAuth 2.1 with protected-resource metadata.
Supporting it means running an authorization server that mints tokens on a consent screen.
That is the automated minting path the user asked to have flagged rather than built.
CLI agents can already use the HTTP API, so the MCP gap is chat-only surfaces such as Claude.ai connectors.
When MCP gets built, it should wrap the same handlers and reuse this guide's wording.

## Where the token goes

The user puts the token in `GLEANMO_TOKEN` themselves, in a shell profile or the harness's env/secrets setting.
Agents are told not to ask for it in chat, not to print it, and not to write it into a repo.
The token page's copyable prompt includes the grants and base URL, never the token.
