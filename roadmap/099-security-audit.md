---
title: "Security audit (2026-10)"
status: active
description: "Walk through a static security audit finding by finding, fixing or deciding each; all 11 decided; fixes committed on security-audit, awaiting user review and merge"
created: 2026-10-14
updated: 2026-10-22
tags: [security]
priority: high
---

# Security audit (2026-10)

## Intent

A static read of the code turned up eleven findings. Each gets a decision —
fix now, roadmap, accept, or not real — after re-reading the cited code to
confirm the claim. Fixes land on the `security-audit` branch; production
changes ship only when the user merges to `main`.

## Specification

Decisions so far (branch `security-audit`, rebased onto local `dev` at `5fb2ad6` on 2026-10-22):

| # | Finding | Severity | Decision | Commit |
|---|---------|----------|----------|--------|
| 1 | CRUD update, task actions and drag-to-reorder did not check record ownership; forms could set `xt/id` / `user/id` | High | Fixed | `86ca962` |
| 2 | A sample `COOKIE_SECRET` value exists in git history (deleted CI doc) | — | Accepted: not the production value (user's judgement; a direct compare was offered) | — |
| 3 | Dev-only e2e sign-in routes reachable from the network while `biff dev` runs | High (dev) | Fixed: loopback-only, server-generated token, `@localhost` accounts only | `8347312`, `00d239b`, `17e168b` |
| 4 | Heatmap tooltip built HTML from record labels without escaping | Medium | Fixed | `34f772b` |
| 5 | Front-end libraries loaded from CDNs, two unversioned, no integrity check | Medium | Fixed: vendored with fonts; CSP roadmapped as 102 | `9dcda5d` |
| 6 | Account email could be changed without verification or a uniqueness check | Medium | Fixed: code sent to the new address, uniqueness enforced | `c3aa3ec` |
| 7 | Request logs held magic-link tokens and query values | Low–Med | Fixed: logger masks tokens, keeps parameter names only | `1ef72cb` |
| 8 | Relation fields (CRUD, timer parent/location, bouldering problem, workout exercise) are not ownership-checked | Low | Roadmapped as 103 | — |
| 9 | `redirect` parameter on CRUD create/update and the settings `turn-off-*` routes is not restricted to local paths (timer routes already are) | Low | Fixed: one `local-path` check in `app/shared.clj` for CRUD create/update, the CRUD form's hidden field and Cancel link, the settings routes and timer routes | `2e58e86` |
| 10 | Config-dependent fallbacks: captcha, email delivery and API-token lifetime | Low | Fixed: prod refuses to start without the reCAPTCHA and MailerSend keys (user confirmed they are set); token expiry since built in [100](./100-agent-api-security.md) (`9eb3b51`) | `fbb9d93` |
| 11 | Supply-chain and hosting hygiene: unchecked tool downloads, CI `permissions:`, container user, dev-only deps in prod, outdated libraries | Low | Fixed, one commit each so any can be reverted alone: checksums, read-only CI token, non-root container, Clerk to `:dev`, slf4j-simple 2.0.12. Biff and ring-defaults upgrades deferred to 037 | `9d886de`, `a318c5e`, `b5260dc`, `b29300e`, `2f2529f` |

Checked and found sound by the audit: API token generation and hashing, API
group / sensitive / archived scoping and `/tx` checks, credential encryption,
Cronometer credential handling, CSRF on site routes, session cookie settings,
Biff's sign-in code attempt limit, CI secrets usage. Every non-CRUD write path
was re-checked during finding 1 and scopes the record it writes to the user.

## Validation

- [x] Each fix has unit tests against a real XTDB node, written failing first where it was a bug
- [x] `just validate` and `just e2e-test-all` green after each batch (last: 2026-10-22, after the rebase onto `5fb2ad6`)
- [x] Findings 8–11 each have a decision
- [ ] User reviews the branch, merges `security-audit` → `dev`, then ships

## Scope

No production actions by agents: App Platform settings, secret rotation and
the merge to `main` are the user's. No history rewrite for finding 2.

## Context

- Fix commits: `git log dev..security-audit`
- CSP follow-up: [102-content-security-policy.md](./102-content-security-policy.md)
- Relation ownership follow-up: [103-relation-ownership-checks.md](./103-relation-ownership-checks.md)
- Vendored libraries: `resources/public/vendor/README.md`
- E2e sign-in guard: `dev/tech/jgood/gleanmo/e2e_auth.clj` (token in `target/e2e-login-token`)

## Progress

- Checkout: `~/projects/gleanmo.worktrees/security-audit`, branch `security-audit`, 24 commits ahead of local `dev` (`5fb2ad6`), not pushed. Renumbered 097→099 (and its follow-ups 098→102, 099→103) on 2026-10-22 because dev took 097, 098 and 100; the email-change `defnp` commit was dropped as dev had already converted the db layer
- Completed: all 11 findings decided (8 roadmapped as 103; 9, 10, 11 fixed); 1, 3, 4, 5, 6, 7 fixed and verified (unit + e2e, live checks against `biff dev` for 3 and 5)
- Remaining: user review; first deploy confirms the non-root container (`b5260dc`, revert alone if it fails), then the follow-ups below
- Validation: `just validate` and `just e2e-test-all` all 26 pass after the rebase onto `5fb2ad6` (2026-10-22, 257 tests / 0 failures); Docker image not built locally
- Blockers: none
- Next action: user decides on the follow-ups below and whether 099 moves to done

## Notes

Follow-ups found along the way (not yet decided):

- Email change: optional notice to the old address; a short re-send cooldown
  for codes (no limit today; each re-send resets attempts but sends a visible email).
- Email change e2e can't cover a successful verify (code reaches only the
  console) or a taken address (`@localhost` accounts fail format checks first);
  unit tests cover both.
- A user with no time zone sees "Africa/Abidjan" preselected on the account
  page, and saving stores it.
- Optional: refuse to load dev-only modules outside the dev profile, rather
  than relying on `dev/` being absent from the uberjar.
- `e2e-auth-test` had never run in CI (not registered in `test.clj`); fixed in `8347312`.
