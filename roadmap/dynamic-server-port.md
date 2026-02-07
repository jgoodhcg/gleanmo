---
title: "Dynamic Server Port Selection"
status: idea
description: "Make the server dynamically choose a port to run on to support git worktree and multiple project development"
tags: [area/dev-tools, type/enhancement, tech/dev-server]
priority: medium
created: 2026-02-06
updated: 2026-02-06
---

# Dynamic Server Port Selection

## Problem / Intent

When working with git worktrees or multiple branches of the same project, the fixed port configuration (8080) causes conflicts. Only one instance can run at a time, making it difficult to:
- Test different branches simultaneously
- Compare feature implementations side-by-side
- Work on multiple projects in the same environment

## Constraints

- Should default to a known port if possible for consistency
- Must display the actual port in the startup logs
- Should work with existing E2E test flow
- Should not require manual configuration changes per worktree
- Must preserve backward compatibility with production (PORT env var)

## Proposed Approach

1. Modify `resources/config.edn` to use a dynamic port selection strategy:
   - Try to bind to the configured port (8080)
   - If port is taken, find an available port in a range (e.g., 8080-8100)
   - Update `:biff/port` and `:biff/base-url` with the selected port

2. Update startup logging to display the actual bound port and base URL

3. Ensure E2E tests can discover the running port or configure it via environment

## Open Questions

- What port range should we search? 8080-8100 (20 ports) should be sufficient
- Should we use a file-based lock or rely on socket bind errors?
- How should E2E tests discover the port? Environment variable or socket discovery?
- Should we store the chosen port in a temp file for other processes to discover?

## Notes

Current config in `resources/config.edn:7-8`:
```clojure
:biff/port     #profile {:dev 8080
                         :prod #long #or [#biff/env PORT 8080]}
```