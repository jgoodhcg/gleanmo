---
title: "Auth Expired Home Page Layout Bug"
status: done
description: "Home page shows login form inside authenticated layout with sidebar when session expires"
created: 2026-05-02
updated: 2026-06-20
completed: 2026-06-20
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

## Resolution

Root cause was the HTMX request path, not full-page navigation. A normal browser
navigation to `/app` after expiry already redirected cleanly: `wrap-signed-in`
returns a `303` to `/signin`, which the browser follows to a sidebar-less page.

The broken state came from in-page HTMX actions. Authenticated pages fire
`hx-post`/`hx-get` calls against `/app/...` endpoints with targets *inside* the
authenticated shell (e.g. `/app/task/today` completing a task swaps into
`#today-content`). When the session had expired, `wrap-signed-in` returned a
`303` to `/signin`, the XHR transparently followed it, and HTMX swapped the full
sign-in page HTML into that inner target — producing the sidebar-around-a-login-form
state described above.

Fix (`src/tech/jgood/gleanmo/middleware.clj`, `wrap-signed-in`): detect the
`hx-request` header on unauthenticated requests and respond `200` with an
`HX-Redirect: /signin?error=not-signed-in` header instead of a `303`. HTMX turns
that into a full-page browser navigation to the clean sign-in page. Non-HTMX
requests keep the original `303` redirect behavior, so the normal sign-in flow is
unchanged.

This applies to every route under the `/app` middleware stack, not just the home
page, so all authenticated routes degrade to a clean sign-in page on expiry.

Tested in `test/tech/jgood/gleanmo/test/middleware_test.clj`.

### Open Questions — answered

- **Built-in Biff mechanism?** Biff's `wrap-signed-in` equivalent only does a plain
  redirect; HTMX-awareness is not built in, hence the manual `HX-Redirect` branch.
- **Home page only, or all authenticated routes?** All authenticated routes share
  the `wrap-signed-in` middleware, so the fix covers all of them uniformly.
- **Client-side HTMX detection?** Handled server-side via `HX-Redirect`, which is
  the idiomatic HTMX way to convert an auth failure into a full-page navigation —
  no client JS needed.

## Validation

- [x] Anonymous HTMX request returns `200` + `HX-Redirect` to clean sign-in (unit test)
- [x] Anonymous browser navigation still returns `303` redirect, no `HX-Redirect` (unit test)
- [x] Signed-in requests (HTMX and non-HTMX) still pass through to the handler (unit test)
- [x] `just lint-fast` clean; full test suite green (76 tests, 622 assertions)
- [ ] Manual: background PWA, wait for session expiry, trigger an in-page action → clean login (verify on device)

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
