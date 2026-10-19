---
title: "Agent API security review (2026-10)"
status: active
description: "End-to-end review of the agent API and its tokens; five fixes on agent-api-security, every area given a verdict with a test, four questions for the user"
created: 2026-10-18
updated: 2026-10-18
tags: [security, api, ai]
priority: high
---

# Agent API security review (2026-10)

## Questions for the user

1. **Token expiry** (finding 6).
   Add an optional `expires-at` picked at creation (30 / 90 days / never)
   and refuse expired tokens at lookup?
   It's additive: existing tokens keep working.
   The note in 061 on `security-audit` rates it low priority.
2. **Body limit on site routes** (finding 8).
   The site stack also decodes JSON and form bodies with no size limit
   before CSRF or sign-in checks run.
   Should the same cap go on every route?
   That first needs a check that no form or upload route needs more than 1 MiB.
3. **The 1 MiB API body cap** (finding 1).
   Changing the cap changes the API contract,
   though no realistic batch comes close:
   200 ops of task edits with long notes stays well under it.
   To change it, edit `api.middleware/max-body-bytes`;
   to drop it, revert `6510877`.
4. **HTTPS at the edge** (prod, not verifiable here).
   Biff sets HSTS, but its `ssl-redirect` defaults to off,
   so the app relies on App Platform to redirect `http://`.
   Worth one `curl -I http://…/api/v1/types` against prod
   to confirm that a client set up with `http://` is redirected rather than served.
   HSTS doesn't protect a non-browser client's first request.

## Intent

The user wants confidence that the agent API is hardened:
token generation, storage, display, authentication, authorization,
and the `tx` write endpoint.
The audit in `097-security-audit.md` (unmerged `security-audit` branch)
called token generation and hashing, group, sensitive and archived scoping,
and the `/tx` checks "sound".
This unit re-checks each of those claims with tests rather than taking them as given,
and covers the Cronometer and Activities groups added after that audit.

## Specification

Branch `agent-api-security`, based on `dev` at `d757ea4`.
One commit per finding, so each can be reverted alone.

| # | Finding | Severity | Decision | Commit |
|---|---------|----------|----------|--------|
| 1 | API routes decoded JSON and form bodies before the token check, with no size limit, so an unauthenticated client could make the server buffer and parse an arbitrarily large body | Medium | Fixed: bodies over 1 MiB get 413 (immediately when the declared length is too large, otherwise once the parser reads past the limit) | `6510877` |
| 2 | Malformed JSON escaped the API's JSON error handler (which sits inside the parser), so it came back as a 500 HTML page and logged an error-level stack trace. No token was needed to cause this | Low | Fixed: JSON 400 | `cad824a` |
| 3 | API responses carried no `Cache-Control` | Low | Fixed: `no-store` on every API response, 401s included | `256c1a1` |
| 4 | Request profiling keyed each request by its path, and only ids were normalized. The stats are cleared only when a super user persists a snapshot, so made-up paths (signed in or not, `/api` included) grew memory without bound | Low–Medium | Fixed: at most 1000 distinct keys per snapshot window; later new keys count as `:other` | `e9b3718` |
| 5 | `tx` checked only that relation targets were owned by the user, not that the token could see them. A token without the sensitive/archived grant could move a record it sees under a sensitive or archived parent, which hides that record from the token. `flag-errors` refuses exactly this move when it's made with the record's own flag | Low | Fixed: targets must be visible to the token (`queries/visible-entity-ids`) | `d78663a` |
| 6 | Tokens never expire | Low | **Open, question 1.** Revocation works, and `last-used-at` shows tokens that have gone stale | — |
| 7 | Request logs include the values of query parameters such as `q` and the equality filters (health search text) | Low | Already fixed on `security-audit` (`61e212f`, which keeps parameter names only), so not re-implemented here. `dev` logs those values until that branch merges | — |
| 8 | Site routes have the same unbounded body parsing as finding 1, before CSRF and sign-in checks | Low–Medium | **Open, question 2.** Outside this unit's API scope | — |
| 9 | A create that names its own id returns "id is already in use" for an id held by any document in the database, whoever owns it | Info | Accepted: ids are random v4 UUIDs, so this check can't be used to find records; the check is what stops a create from overwriting someone's document | — |
| 10 | No rate limit on token authentication | Info | Accepted: tokens carry 256 random bits, so guessing is hopeless. An unauthenticated request costs one indexed lookup and, after finding 1, at most 1 MiB of body | — |
| 11 | `q` text search and large offsets read every document of a type for the token's user | Info | Accepted: needs a token, only touches the token owner's own data, and `limit` is capped at 500 | — |
| 12 | A relation may point at a visible record in a group the token can't read (e.g. a habits-only token setting a location id) | Info | Accepted: the record belongs to the same user, and reads already expose related ids as bare values by design (see `activities-group-test`) | — |
| 13 | After a token is created, browser Back can restore the revealed callout from the back/forward cache | Info | Accepted: this only happens on the user's own device. The page's HTML never contains the plaintext; only the `no-store` HTMX fragment does | — |

### Verdicts by area

Tests are in `test/tech/jgood/gleanmo/test/api_security_test.clj` unless named otherwise.
Each one exercises a real XTDB node,
and the body tests go through `main/handler`, the handler Jetty calls.

| Area | Verdict | Evidence |
|------|---------|----------|
| Generation: `SecureRandom`, 32 bytes, base64url, `gmn_` prefix | Sound (agrees with 097) | `token-format-test`; `access/generate-token` |
| Storage: SHA-256 hex, no salt or pepper | Sound (agrees with 097). With 256 random bits of input, a salt or slow hash would add nothing. The stored hash isn't itself a credential | `token-format-test`, `bearer-parsing-test` |
| Lookup and comparison timing | Sound: the lookup is an indexed equality on the hash, so no attacker-controlled comparison happens in the app | `queries/api-token-by-hash` |
| Display prefix | Sound: 8 of the 43 random characters are stored, which leaves 208 bits unknown | `token-format-test` |
| One-time display | Sound: plaintext is sent once in a `no-store` HTMX fragment, never stored, never in a URL (no Referer or history leak), and the page makes no HTMX history snapshot (no `hx-push-url`) | `token-management-test`; `e2e/scripts/test-api-tokens.ts`; `.decisions/api-token-reveal.json` |
| Expiry | Finding 6 | — |
| Revocation | Sound: only the owner can revoke, and it takes effect on the next request | `token-management-test` |
| Listing, last-used | Sound: the list is a user-scoped query; `last-used-at` is advisory, updated at most every 5 minutes | `queries/api-tokens-for-user`, `api/touch-last-used!` |
| Per-user token limit | None; accepted for a single-user app | — |
| CSRF on token routes | Sound: `biff/form` token plus the site's anti-forgery middleware (covered site-wide by 097) | `app/api_tokens.clj` |
| Bearer parsing | Sound: only an exact `Bearer <token>` authenticates; Basic, empty, truncated, trailing data and the hash all fail | `bearer-parsing-test` |
| Missing, unknown, revoked, soft-deleted tokens | Sound: all get the same 401 | `authentication-test` (api_test), `bearer-parsing-test` |
| Session and CSRF bleed between site and API | Sound: the API stack has no session middleware, and a signed-in session alone gets 401; site routes never read `Authorization`; there are no CORS headers, so a browser can't send a token cross-origin | `bearer-parsing-test` |
| Types never reachable (`user`, `ical-url`, `api-token`) | Sound, even for a token granted every group, read or write | `unreachable-types-test` |
| Group scoping | Sound for reads and writes; unknown and forbidden types look identical | `list-test`, `types-test` (api_test), `group-scope-write-test` |
| Sensitive and archived on reads | Sound (agrees with 097): lists must ask and hold the grant; by-id reads need the grant; logs under a hidden parent stay hidden | `list-test`, `get-test` (api_test) |
| Sensitive and archived on writes | Partly disagrees with 097: flags and hidden records were sound, but relation targets weren't (finding 5) | `visibility-write-test`, `hidden-relation-target-test` |
| User scoping | Sound: every read and write resolves through the token's `user/id` | `get-test`, `tx-test` (api_test), `cross-user-write-test` |
| `tx` protected fields (`user/id`, `xt/id`, meta, `airtable/*`, hidden and derived fields, timer `running`) | Sound: refused with an error, not silently dropped | `protected-fields-test` |
| `tx` other users' records and relations | Sound: update, delete, `expect` and dry run all say "not found" without revealing values; single and set relations to another user's records are refused | `cross-user-write-test`, `tx-test` |
| Dry run | Sound: same checks as a commit and writes nothing; a delete's `before` appears only for visible records | `tx-test`, `cross-user-write-test` |
| Cronometer (Nutrition, Biometrics) and Activities groups | Sound: never writable, even with a tampered write grant; raw payloads stay out of responses; the exercise-session link is a bare id | `read-only-groups-test`, `activities-group-test` (api_test) |
| Request size | Finding 1 (API), finding 8 (site) | `body-size-limit-test` |
| Op and page caps | Sound: 200 ops per `tx`, `limit` 1–500 | `api/max-ops`, `api/max-limit` |
| Brute force and rate limiting | Finding 10 | — |
| Logging | Authorization headers are never logged (Biff logs method, path and status only); query values are finding 7; `wrap-json-errors` logs the exception, not the request | `middleware.clj`, `api.clj` |
| Profiling | Finding 4. Magic-link tokens that end up in profiler keys are lowercased and mangled, so they can't be reused | `observability-test/route-key-cardinality-test` |
| Error reports | Finding 2 | `malformed-body-test` |
| Response caching | Finding 3 | `no-store-test` |

## Validation

- [x] Each fix has a test against a real XTDB node, written failing first, plus its own commit
- [x] Each "sound" verdict is backed by a named test or code reference
- [x] `just validate`: 227 tests / 0 failures (2026-10-18, `b1f6ae8`)
- [ ] `just e2e-test api-tokens` (no UI changed; needs the user's `biff dev`)
- [ ] User answers the questions above, reviews the branch, then merges it into `dev`

## Scope

No production actions:
no App Platform settings, secret rotation, migrations, or merges to `main`.
The branch isn't merged into `dev` either.
Findings describe each problem and its fix, not how to exploit it.

## Context

- API design: [061-ai-assistance.md](./061-ai-assistance.md)
- Prior pass: `097-security-audit.md` on `security-audit`
  (`git show security-audit:roadmap/097-security-audit.md`),
  and the relation-ownership follow-up `099-relation-ownership-checks.md`
  for the CRUD side of finding 5
- Surface built in `a82c7e8`, `53ceaf0`, `da73924`, `5b6d2e9`
- New guards: `src/tech/jgood/gleanmo/api/middleware.clj`,
  wired outside `wrap-api-defaults` in `src/tech/jgood/gleanmo.clj`

## Progress

- Checkout: `~/projects/gleanmo.worktrees/agent-api-security`, branch `agent-api-security`, not pushed
- Completed: findings 1–5 fixed; 9–13 accepted; every area has a verdict
- Remaining: questions 1–4; e2e re-run; review and merge
- Validation: see above
- Blockers: user decisions only
- Next action: user answers questions 1–4

## Notes

- Merging with `security-audit` conflicts only in `test/tech/jgood/gleanmo/test.clj`
  (both branches add requires; keep both).
  The conflict in `roadmap/061-ai-assistance.md` exists between `dev` and `security-audit` already.
  `security-audit` replaces Biff's `wrap-log-requests` in `wrap-base-defaults`;
  this branch only adds route middleware for the API,
  so the two don't overlap.
- The profiler cap (finding 4) also affects site routes,
  because the profiler wraps the whole handler.
