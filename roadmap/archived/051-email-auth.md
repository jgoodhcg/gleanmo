---
title: "Re-enable Email Authentication"
status: done
description: "Passwordless email sign-in — magic links and 6-digit codes — delivered through MailerSend, guarded by reCAPTCHA"
created: 2026-03-16
updated: 2026-09-10
tags: [auth, security]
priority: high
---

# Re-enable Email Authentication

## Intent

Restore passwordless email authentication so users can sign in without a password. The app was already built on Biff's email auth. The work was to get delivery running again and to give the flow a real front door.

## Specification

Sign-in is passwordless. Biff's `:biff.auth` module supplies the `/auth/*` endpoints; `home.clj` supplies the pages around them.

| Flow | Page (GET) | Posts to |
|---|---|---|
| Sign up — magic link | `/signup` | `/auth/send-link`, verified at `/auth/verify-link` |
| Sign in — 6-digit code | `/signin` | `/auth/send-code`, verified at `/auth/verify-code` |
| Link-sent confirmation | `/link-sent` | — |

Delivery is **MailerSend**, over its REST API (`api.mailersend.com/v1/email`, OAuth bearer token). `email.clj/send-email` dispatches to `send-mailersend` when `mailersend/api-key` is set, and to `send-console` otherwise. Templates are `:signin-link` and `:signin-code`.

reCAPTCHA guards every request endpoint: `biff/recaptcha-callback` on each form, `:recaptcha/site-key` in the sign-in page context, and `biff/recaptcha-disclosure` in the footer.

## Validation

- [x] Sign-up magic link sends and verifies — `a88e4c4`
- [x] Sign-in code sends and verifies — `a88e4c4`, pages restyled in `bc08aed`
- [x] reCAPTCHA attached to all four form posts (`home.clj:298-373`)
- [x] Local dev needs no provider account — no API key falls back to `send-console`, which prints the message
- [ ] Prod delivery confirmed against the live provider — depends on `MAILERSEND_API_KEY` in the App Platform dashboard, which the repo cannot verify

## Scope

Email authentication only. No change to the auth architecture, and no additional auth methods (OAuth, passkeys).

## Context

- `src/tech/jgood/gleanmo/email.clj` — templates and the two senders
- `src/tech/jgood/gleanmo/home.clj` — sign-up, sign-in, and verification pages, routes at `home.clj:375`
- `resources/config.edn` — `MAILERSEND_API_KEY`, `MAILERSEND_FROM`, `MAILERSEND_REPLY_TO`, `RECAPTCHA_SECRET_KEY`, `RECAPTCHA_SITE_KEY`
- `middleware.clj`, `wrap-signed-in` — HTMX-aware handling of session expiry, see `057-auth-expired-home-layout.md`
- Config reaches production through App Platform environment variables, not `config.env` — see `AGENTS.md`, Deployment
- Provider history: **Postmark** until `669ebd1` (2024-06-18, v1.8.10); MailerSend since

## Open Questions — answered

- **Why was email auth disabled?** A MailerSend trial-plan change. `c6cee65` (2025-06-07) forced `send-console` and commented the real sender out, with a dated note in the code.
- **Is the provider still configured correctly?** It is now. The question assumed Postmark, which had been replaced a year before this unit was written.
- **Are the reCAPTCHA keys still valid?** In active use — the site key drives the client widget on both sign-in and sign-up.
- **Are any environment variables missing?** No. The set is the five listed under Context, and the code degrades to console output when the API key is absent.

## Notes

Re-enabled 2026-07-04 (`a88e4c4`), initially gating on the MailerSend key plus both reCAPTCHA values. `48e4e47` (2026-07-06) simplified the gate to the API key alone.

This unit was written 2026-03-16 naming Postmark, a provider already gone for 21 months, and its open questions were never revisited while the work landed under other commits. Corrected 2026-09-10. The durable operational fact — current provider, config keys, and console fallback — now lives in `docs/maintainer-reference.md`.
