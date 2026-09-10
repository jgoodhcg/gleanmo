---
title: "Today Page Mobile Redesign"
status: draft
description: "Redesign the today task page for better mobile ergonomics and responsiveness"
created: 2026-02-09
updated: 2026-02-09
tags: [ui, mobile, ergonomics]
priority: medium
---

# Today Page Mobile Redesign

## Intent

The today task page is not optimized for mobile screens. Users need an ergonomic mobile experience that prioritizes touch targets, readability, and efficient task management on small screens.

## Constraints

- Must work on standard mobile viewport widths (375px+)
- Touch targets must meet accessibility guidelines (min 44px)
- Desktop experience should not degrade

## Specification

- Redesign today page layout for mobile breakpoints
- Increase touch target sizes for task interactions (complete, edit, delete, reorder)
- Optimize vertical space usage (compact but readable task items)
- Ensure task input form is ergonomic on mobile (keyboard-friendly, accessible controls)
- Consider mobile-specific interactions (swipe gestures, bottom navigation)

## Validation

- `just e2e-screenshot /today` at mobile viewport widths
- Manual testing on actual mobile device or device emulator
- Lighthouse accessibility audit for touch targets

## Context

The today page is a core feature used daily. Mobile access is increasingly important for on-the-go task management.

## Open Questions

- Should reorder behavior differ on mobile?
- What mobile-specific gestures would add value?

## Notes

