---
title: "Auth Expired Home Page Layout Bug"
status: draft
description: "Home page shows login form inside authenticated layout with sidebar when session expires"
created: 2026-05-02
updated: 2026-05-02
tags: [area/auth, type/bug, ux]
priority: high
---

# Auth Expired Home Page Layout Bug

## Problem / Intent

When a user's auth session expires while they have the app open, the home page renders the login/sign-in screen within the authenticated layout shell — specifically, the sidebar remains visible alongside the login form. This creates a confusing broken state where the user sees both authenticated chrome and an unauthenticated content area.

The fix should ensure that when auth expires, the user is either redirected to a clean login page (no sidebar) or the full layout gracefully degrades to the unauthenticated state.

## Constraints

- Must handle session expiry gracefully for both active tabs and backgrounded/reopened PWAs
- Should not break the normal login flow for unauthenticated users
- Must work with Biff's email-based auth system

## Specification

### Expected Behavior

- When auth expires, navigating to the home page should show the login screen **without** the sidebar or any authenticated layout chrome
- The transition from authenticated → expired should feel seamless to the user

### Possible Approaches

1. **Middleware/auth wrapper fix**: Ensure the home page route checks auth and serves the unauthenticated layout (no sidebar) when the session is invalid, rather than serving the authenticated layout with login content
2. **Client-side redirect**: On auth expiry detection, redirect to `/login` or equivalent clean login route
3. **Layout-level check**: Make the layout wrapper auth-aware so it suppresses the sidebar when the user is not authenticated

## Validation

- [ ] Test: open app, wait for session to expire, navigate to home page → should see clean login (no sidebar)
- [ ] Test: background PWA, wait for session expiry, reopen → should see clean login
- [ ] Test: normal unauthenticated visit to home page still works as expected
- [ ] Test: after re-authenticating from the expired state, full layout restores correctly

## Scope

- This fix is scoped to the home page layout behavior on auth expiry
- Does not include changes to session duration or auth mechanism itself

## Context

- Biff auth middleware likely controls layout rendering
- Relevant files: route handler for home page, auth middleware, layout wrapper
- Related to [email-auth.md](./email-auth.md) and [pwa-experience.md](./pwa-experience.md)

## Open Questions

- Does Biff provide a built-in mechanism for handling expired session redirects?
- Is the issue specific to the home page or does it affect all authenticated routes?
- Is there a client-side mechanism (e.g., HTMX response) that could detect 401/redirect and handle it uniformly?
