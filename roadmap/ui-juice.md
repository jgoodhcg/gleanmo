---
title: "UI Juice & Delight"
status: draft
description: "Add micro-interactions, animations, and haptic feedback to make the app feel alive and rewarding"
created: 2026-02-18
updated: 2026-02-18
tags: [ux, animations, haptics, delight]
priority: low
---

# UI Juice & Delight

## Intent

Transform Gleanmo from a functional data-entry tool into a delightful experience that rewards consistent tracking. Inspired by "juice" in game design—polish that makes interactions feel satisfying and memorable. Aligns with !boring software's philosophy: functional core, expressive shell.

## Constraints

- Must not hurt performance (lazy-load animation libraries)
- Mobile-first haptics (desktop fallbacks graceful)
- All celebratory features user-togglable (some find them distracting)
- Accessibility: respect `prefers-reduced-motion`
- Prefer no external dependencies for core animations (CSS + small JS)

## Specification

### Haptic Feedback (Vibration API)

| Action | Pattern | Platform |
|--------|---------|----------|
| Successful log entry | Single 50ms buzz | Mobile |
| Delete/archive | Double 30ms buzz | Mobile |
| Streak milestone (7, 14, 30, 100) | `[100, 50, 100, 50, 200]` | Mobile |
| Goal achieved | `[50, 30, 50, 30, 50]` | Mobile |

### Visual Micro-interactions

1. **Save feedback**: Form fields briefly glow green (200ms) on successful save
2. **Checkbox pop**: Spring animation on toggle/check
3. **Card hover**: Subtle lift (2px translate, soft shadow) on dashboard cards
4. **Number animations**: Counters animate from 0 → value (e.g., "7 day streak")
5. **Progress fills**: Rings/bars fill with easing, not instant

### Celebration Moments

| Trigger | Reward |
|---------|--------|
| Daily streak 7+ | Growing fire emoji, intensity scales with streak |
| Weekly review complete | Confetti burst + "Week captured!" toast |
| First log of day | "Day started!" micro-animation |
| Monthly milestone | Special badge pulse |
| New personal record | Highlight + celebratory animation |

### Chart Animations

- Data points pulse on hover
- Charts draw in smoothly on load
- Tooltips slide in with easing

### Optional Audio (User Toggle)

- Soft click on button press
- Gentle chime for achievements
- Subtle "whoosh" on data sync

## Validation

- [ ] `prefers-reduced-motion` disables all animations
- [ ] Haptic feedback graceful degradation on unsupported browsers
- [ ] No performance regression on page load (measure before/after)
- [ ] All celebration features have user toggle in settings
- [ ] E2E: animations don't block interactions

## Context

Gleanmo is functional but dry. Adding juice increases emotional engagement and makes tracking feel rewarding rather than chore-like. This is low priority but high impact for long-term retention.

## Open Questions

- Which animation library? Options: none (CSS-only), anime.js, GSAP, motion.one
- Should audio be considered at all, or too intrusive?
- Confetti: custom implementation or library (canvas-confetti)?
- Integration with existing HTMX transitions?

## Notes

### Phase 1: Foundation (Low Effort, High Impact)
1. Save glow feedback (CSS only)
2. Haptic patterns for mobile
3. Number counter animations

### Phase 2: Celebration
1. Streak fire emoji with scaling
2. Confetti for milestones
3. Achievement toasts

### Phase 3: Polish
1. Chart hover animations
2. Card hover effects
3. Smooth page transitions

### Technical Approach
- Use CSS `@keyframes` for simple animations
- `navigator.vibrate()` for haptics with feature detection
- `IntersectionObserver` for scroll-triggered animations
- Consider `view-transition-api` when browser support improves
