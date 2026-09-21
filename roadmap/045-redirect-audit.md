---
title: "Redirect Audit"
status: draft
description: "Audit all actions to implement intuitive redirects with query parameter support"
created: 2026-03-09
updated: 2026-09-21
tags: [ux, navigation]
priority: medium
---

# Redirect Audit

## Intent

Ensure all form submissions and actions support intuitive redirects via a `redirect` query parameter, so users return to contextually appropriate pages after completing actions instead of hardcoded default locations.

## Specification

All POST handlers that perform redirects after form submission should:
1. Accept a `redirect` query parameter (string or keyword key)
2. Pass the redirect through to the response headers (`HX-Redirect` for HTMX, `location` for full page)
3. Forms should include a hidden `redirect` input when the parameter is present
4. Default redirects remain unchanged when no parameter is provided

**Currently implemented:**
- `src/tech/jgood/gleanmo/app/user.clj` - signin/signout/signup
- `src/tech/jgood/gleanmo/crud/handlers.clj` - create/update actions
- `src/tech/jgood/gleanmo/timer/routes.clj` - timer start/edit redirects

**Likely missing:**
- Task completion toggles
- Habit logging
- Medication logging
- Exercise logging
- Calendar event actions
- Any other POST handlers not listed above

## Validation

- [ ] Audit all POST handlers in the codebase for redirect support
- [ ] Add redirect parameter handling to missing handlers
- [ ] Update forms to pass redirect through hidden inputs
- [ ] E2E test: submit form with redirect param, verify correct destination
- [ ] Manual testing of key flows (task complete, habit log, etc.)

## Scope

**In scope:**
- All POST handlers that redirect after completion
- Form templates that need hidden redirect inputs
- Query parameter extraction and URL encoding

**Out of scope:**
- GET request redirects (middleware already handles signed-in redirect)
- Changing default redirect destinations
- New redirect UI/UX patterns

## Context

The redirect pattern was implemented piecemeal. Key files:
- `src/tech/jgood/gleanmo/crud/handlers.clj:57-58` - parameter extraction pattern
- `src/tech/jgood/gleanmo/crud/forms.clj:73-75` - hidden input pattern
- `src/tech/jgood/gleanmo/timer/routes.clj` - URL encoding for redirect targets

## Open Questions

- Which POST handlers currently lack redirect support?
- Are there DELETE handlers that also need redirect support?
- Should we standardize on a helper function for redirect parameter extraction?
- Do any actions need redirect support for GET requests (e.g., cancel buttons)?

## Notes

Search for `:headers {"location"` or `:headers {"HX-Redirect"` to find all redirect responses, then cross-reference with handlers that accept POST requests.

## Observed issues

### Focus/today task edit doesn't return to the referring page (2026-09-21)

User-observed: editing an individual task from the focus today page and saving
keeps you on the edit form instead of returning to the page you came from.

- **Cause**: the task edit links on the focus page
  (`src/tech/jgood/gleanmo/app/task_focus.clj:265`) and today page
  (`src/tech/jgood/gleanmo/app/task_today.clj:157`) point at
  `/app/crud/form/task/edit/<id>` with no `redirect` query parameter. The CRUD
  update handler (`src/tech/jgood/gleanmo/crud/handlers.clj:216-217`) already
  honors a `redirect` param but defaults to the edit form itself.
- **Fix pattern already exists**: the home overview's `edit-form-url`
  (`src/tech/jgood/gleanmo/app/overview.clj:115-118`) appends
  `?redirect=<url-encoded return path>` — replicate that on the focus/today
  edit links, pointing back at the referring page (including any query string
  the focus page was rendered with).
