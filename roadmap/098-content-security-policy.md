---
title: "Content Security Policy"
status: draft
description: "Send an enforced CSP so the browser refuses script the app did not ship, after moving inline code into served files"
created: 2026-10-14
updated: 2026-10-14
tags: [security, frontend]
priority: medium
---

# Content Security Policy

## Intent

Every page renders text that users, agents and imports wrote. Rum escapes it on
the server, and the heatmap tooltip now escapes it in the browser, but each new
place that builds HTML from data is another chance to miss one. A Content
Security Policy is the backstop: the browser runs only script the app shipped,
so a missed escape shows up as a broken label rather than code running in the
user's session.

Vendoring the front-end libraries (2026-10-14, `resources/public/vendor/`) made
this cheap on the source side: scripts come from this origin, except reCAPTCHA
and Plausible.

## Specification

An enforced `Content-Security-Policy` header on every HTML response, roughly:

- `script-src 'self'` plus `https://www.google.com/recaptcha/`,
  `https://www.gstatic.com/recaptcha/` and `https://plausible.io`
- `style-src 'self'` (plus whatever inline styles survive the audit below)
- `frame-src https://www.google.com/recaptcha/` for the reCAPTCHA widget
- `connect-src 'self' https://plausible.io`
- `object-src 'none'`, `base-uri 'self'`, `frame-ancestors 'none'`

Before it can be enforced, the inline code it would block has to move:

1. The six inline `<script>` blocks rendered with `biff/unsafe` (workout,
   bouldering, overview, `shared/tick-script`) become files under
   `resources/public/js/`, loaded with `<script src>`.
2. The ~17 `onclick` / `onchange`-style attributes and the one `hx-on`
   attribute become listeners in a JS file, keyed off `data-` attributes.
3. Inline `style` attributes and ECharts' injected styles: decide between
   moving them to classes and allowing `'unsafe-inline'` for styles only, which
   is much lower risk than for scripts.

## Validation

- [ ] A report-only run across `just e2e-shot-series` routes shows no
      violations in the console
- [ ] `just e2e-test-all` passes with the policy enforced
- [ ] A label containing `<img src=x onerror=…>` rendered through a raw-HTML
      path is blocked by the browser
- [ ] reCAPTCHA sign-in and Plausible still work in prod

## Scope

Not included: Subresource Integrity (unneeded now that libraries are
vendored), self-hosting reCAPTCHA (not possible) or Plausible (possible via a
proxy, separate decision).

## Context

- Head tags: `src/tech/jgood/gleanmo/ui.clj` `base`
- Vendored libraries: `resources/public/vendor/README.md`
- Biff middleware stack: `src/tech/jgood/gleanmo/middleware.clj`
- Came out of the 2026-10 security audit (finding 5: unpinned CDN scripts).

## Open Questions (draft only)

- Report-only first, or straight to enforcing once the inline code has moved?
  Report-only was deferred on 2026-10-14 to avoid console noise.
- Does htmx 1.9.0 need `'unsafe-eval'` for anything we use? (`hx-on` and
  `hx-vals` with `js:` do; plain swaps do not.)

## Notes
