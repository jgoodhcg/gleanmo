---
title: "Reading Log Page Numbers"
status: dropped
description: "Superseded by the approved reading-position and goals implementation plan in 081."
tags: [reading-log, schema, tracking]
priority: low
created: 2026-04-03
updated: 2026-09-13
---

# Reading Log Page Numbers

## Disposition

Superseded by [081 — Goals dashboard](../081-goals-dashboard.md).
Implement reading positions through that work unit rather than as a separate change.

The accepted design uses optional starting and ending page, chapter, and audiobook positions.
Book totals are optional, and the existing finished flag controls book completion.
Named numeric aliases provide reusable positive and nonnegative integer validation.

The previous proposal's alternatives are resolved.
In particular, do not enforce ending page greater than or equal to starting page.
Backward positions and edition differences are allowed.
Reading-speed calculations remain deferred.

## Reference

[Approved version 10 mockup](../../mockups/2026-09-motivating-dashboards/codex/mockup-codex-10-goals-dashboard.html).
