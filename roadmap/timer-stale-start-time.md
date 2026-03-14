---
title: "Timer Stale Start Time"
status: draft
description: "Fix timer starting with old timestamp when PWA has been idle on timers page"
created: 2026-03-14
updated: 2026-03-14
tags: [area/timers, type/bug, pwa]
priority: high
---

# Timer Stale Start Time

## Problem / Intent

When a user opens the PWA after not interacting for a while and is already on the timers page, hitting "start timer" causes the timer to start with an old/stale timestamp instead of the current time. This suggests that a timestamp is being set client-side that becomes stale when the app is idle or backgrounded.

The goal is to ensure that timer start times always reflect the actual moment the user pressed the start button, regardless of how long the app has been idle.

## Constraints

- Must work reliably in PWA context where apps can be backgrounded/resumed
- Should maintain fast UI response (avoid blocking on network requests if possible)
- Must handle edge cases like network latency and clock skew
- Should not break existing timer functionality or data

## Proposed Approach

### Backend-Driven Start Time

Move the start time determination to the backend:

1. **Backend change**: When a timer start request is received, the backend sets `:started-at` to the current server timestamp (`(java-time/instant)` or equivalent)
2. **Client change**: Remove any client-side timestamp pre-computation or caching that might become stale
3. **UI response**: Optimistic UI update showing timer started, with server-provided actual start time replacing the optimistic value once the response arrives

### Alternative Approaches Considered

- **Client-side "now" on button press**: Still vulnerable to clock issues and doesn't solve the stale timestamp issue if the client is computing it elsewhere
- **Hybrid approach**: Use client timestamp for immediate UI, but always use server timestamp as source of truth (current likely approach, but needs verification)

## Specification

### Backend Changes

In the timer start mutation handler:
- Ignore any client-provided start time
- Set `:started-at` to `(java-time/instant)` at the moment the mutation executes
- Return the actual start time in the response

### Frontend Changes

- Remove any cached or pre-computed start times
- Ensure start button handler requests a fresh start time from the backend
- Consider optimistic UI with server reconciliation

## Validation

- [ ] Test timer start after PWA idle for 5+ minutes on timers page
- [ ] Test timer start after PWA backgrounded and resumed on timers page
- [ ] Test timer start with poor network connectivity
- [ ] Verify no stale timestamps in timer logs
- [ ] E2E test: Open timers page, idle for N minutes, start timer, verify start time is within 1 second of actual button press

## Context

This is related to [pwa-experience.md](./pwa-experience.md) as it affects the reliability of the PWA timer functionality. The timers system is a core feature of Gleanmo, so timestamp accuracy is critical for meaningful time tracking data.

Relevant files likely include:
- Timer mutation handlers (probably in `src/tech/jgood/gleanmo/db/mutations.clj`)
- Timer start route handlers
- Timer UI components (likely Rum components for the timers page)

## Open Questions

- Is the stale timestamp coming from a cached value, a pre-computed value, or client-side time calculation?
- Should we implement optimistic UI updates, or wait for server response before showing the timer as started?
- How should we handle network failures during timer start (retry logic, offline queue)?
- Are there other timestamp-related operations that might have similar staleness issues?

## Notes

- User reports the issue occurs specifically when the PWA has been idle and the user is already on the timers page
- The issue suggests a timestamp is being set somewhere that gets stale - likely client-side caching or pre-computation
- Backend-driven timestamps are more reliable for time-sensitive operations in distributed/PWA contexts
