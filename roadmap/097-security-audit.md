---
title: "Security audit (2026-10)"
status: active
description: "Walk through a static security audit finding by finding, fixing or deciding each; all 11 decided; fixes committed on security-audit, awaiting user review and merge"
created: 2026-10-14
updated: 2026-10-20
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

Decisions so far (branch `security-audit`, rebased onto local `dev` at `7aa7e06` on 2026-10-20):

| # | Finding | Severity | Decision | Commit |
|---|---------|----------|----------|--------|
| 1 | CRUD update, task actions and drag-to-reorder did not check record ownership; forms could set `xt/id` / `user/id` | High | Fixed | `399731a` |
| 2 | A sample `COOKIE_SECRET` value exists in git history (deleted CI doc) | — | Accepted: not the production value (user's judgement; a direct compare was offered) | — |
| 3 | Dev-only e2e sign-in routes reachable from the network while `biff dev` runs | High (dev) | Fixed: loopback-only, server-generated token, `@localhost` accounts only | `5c1d74b`, `86fe0ef`, `2a12c37` |
| 4 | Heatmap tooltip built HTML from record labels without escaping | Medium | Fixed | `e012534` |
| 5 | Front-end libraries loaded from CDNs, two unversioned, no integrity check | Medium | Fixed: vendored with fonts; CSP roadmapped as 098 | `f37c34b` |
| 6 | Account email could be changed without verification or a uniqueness check | Medium | Fixed: code sent to the new address, uniqueness enforced | `cd9e385` |
| 7 | Request logs held magic-link tokens and query values | Low–Med | Fixed: logger masks tokens, keeps parameter names only | `8be102c` |
| 8 | Relation fields (CRUD, timer parent/location, bouldering problem, workout exercise) are not ownership-checked | Low | Roadmapped as 099 | — |
| 9 | `redirect` parameter on CRUD create/update and the settings `turn-off-*` routes is not restricted to local paths (timer routes already are) | Low | Fixed: one `local-path` check in `app/shared.clj` for CRUD create/update, the CRUD form's hidden field and Cancel link, the settings routes and timer routes | `afd627d` |
| 10 | Config-dependent fallbacks: captcha, email delivery and API-token lifetime | Low | Fixed: prod refuses to start without the reCAPTCHA and MailerSend keys (user confirmed they are set); token expiry noted in 061 | `d91468a` |
| 11 | Supply-chain and hosting hygiene: unchecked tool downloads, CI `permissions:`, container user, dev-only deps in prod, outdated libraries | Low | Fixed, one commit each so any can be reverted alone: checksums, read-only CI token, non-root container, Clerk to `:dev`, slf4j-simple 2.0.12. Biff and ring-defaults upgrades deferred to 037 | `c5176fb`, `04eb8dd`, `4ef1cfa`, `a06b628`, `b39daea` |

Checked and found sound by the audit: API token generation and hashing, API
group / sensitive / archived scoping and `/tx` checks, credential encryption,
Cronometer credential handling, CSRF on site routes, session cookie settings,
Biff's sign-in code attempt limit, CI secrets usage. Every non-CRUD write path
was re-checked during finding 1 and scopes the record it writes to the user.

## Validation

- [x] Each fix has unit tests against a real XTDB node, written failing first where it was a bug
- [x] `just validate` and `just e2e-test-all` green after each batch (last: 2026-10-14)
- [x] Findings 8–11 each have a decision
- [ ] User reviews the branch, merges `security-audit` → `dev`, then ships

## Scope

No production actions by agents: App Platform settings, secret rotation and
the merge to `main` are the user's. No history rewrite for finding 2.

## Context

- Fix commits: `git log dev..security-audit`
- CSP follow-up: [098-content-security-policy.md](./098-content-security-policy.md)
- Relation ownership follow-up: [099-relation-ownership-checks.md](./099-relation-ownership-checks.md)
- Vendored libraries: `resources/public/vendor/README.md`
- E2e sign-in guard: `dev/tech/jgood/gleanmo/e2e_auth.clj` (token in `target/e2e-login-token`)

## Progress

- Checkout: `~/projects/gleanmo.worktrees/security-audit`, branch `security-audit`, clean, 22 commits ahead of local `dev` (`7aa7e06`, itself 4 commits ahead of origin), not pushed
- Completed: all 11 findings decided (8 roadmapped as 099; 9, 10, 11 fixed); 1, 3, 4, 5, 6, 7 fixed and verified (unit + e2e, live checks against `biff dev` for 3 and 5)
- Remaining: user review; first deploy confirms the non-root container (`4ef1cfa`, revert alone if it fails), then the follow-ups below
- Validation: `just validate` 226 tests / 0 failures after finding 11 (2026-10-16); `just e2e-test-all` all 26 pass after finding 9; Docker image not built locally
- Blockers: none
- Next action: user decides on the follow-ups below and whether 097 moves to done

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
- `e2e-auth-test` had never run in CI (not registered in `test.clj`); fixed in `5c1d74b`.
