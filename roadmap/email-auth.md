---
title: "Re-enable Email Authentication"
status: draft
description: "Restore email-based magic link authentication for user sign-in"
created: 2026-03-16
updated: 2026-03-16
tags: [auth, security]
priority: high
---

# Re-enable Email Authentication

## Intent

Re-enable the standard Biff email-based magic link authentication flow so users can sign in securely without passwords.

## Specification

- Restore email authentication functionality
- Verify Postmark integration is working
- Ensure reCAPTCHA protection is functional
- Test the complete sign-in flow end-to-end

## Validation

- [ ] User can request a sign-in link via email
- [ ] Sign-in link successfully authenticates the user
- [ ] reCAPTCHA protects against bot abuse
- [ ] Works in both development and production environments

## Scope

- Email authentication only
- Not changing the auth system architecture
- Not adding alternative auth methods (OAuth, etc.)

## Context

Email auth was previously disabled or is currently non-functional. Biff's default auth system uses Postmark for sending magic links and reCAPTCHA for bot protection.

## Open Questions

- Why was email auth disabled?
- Is Postmark still configured correctly?
- Are reCAPTCHA keys still valid?
- Are there any environment variables missing?

## Notes

[Design details, context, implementation notes as work progresses.]
