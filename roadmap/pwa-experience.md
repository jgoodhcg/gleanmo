---
title: "PWA Experience"
status: draft
description: "Improve progressive web app experience for native-like feel on iOS and Android"
created: 2026-03-01
updated: 2026-03-01
tags: [pwa, mobile, ios, android, ux]
priority: medium
---

# PWA Experience

## Intent

Enable a native app-like experience when users add Gleanmo to their home screen on iOS and Android, including standalone display mode, push notification support, and home screen badging.

## Constraints

- Must work without App Store deployment (PWA approach)
- iOS requires specific meta tags and manifest properties
- Service Worker required to unlock push notification and badging APIs
- Must maintain existing web app functionality

## Specification

### 1. Web App Manifest (manifest.json)

Create `/manifest.json` with:
- `"display": "standalone"` to hide browser UI
- App name, short name, and description
- Icons in multiple sizes (192px, 512px minimum)
- Theme and background colors matching Gleanmo branding

### 2. Apple-Specific Meta Tags

Add to HTML `<head>`:
- `<meta name="apple-mobile-web-app-capable" content="yes">`
- `<meta name="apple-mobile-web-app-status-bar-style" content="default|black|black-translucent">`
- `<link rel="apple-touch-icon" href="/icon-192.png">`

### 3. Service Worker (sw.js)

Create and register `/sw.js`:
- Basic service worker registration in app initialization
- `addEventListener('push', ...)` handler for incoming notifications
- Empty or minimal caching strategy initially (can expand later)

### 4. Home Screen Prompt

Implement "Add to Home Screen" detection:
- Detect when app is running in standalone mode
- Show tooltip or banner prompting users to add to home screen (when not already installed)
- Provide platform-specific instructions (iOS vs Android)

### Integration Checklist

| File | Content / Purpose |
|------|-------------------|
| `index.html` or base template | Add `<link rel="manifest" href="/manifest.json">` and apple-mobile-web-app meta tags |
| `/manifest.json` | Define name, icons, and `"display": "standalone"` |
| Main app JS/init | Call `navigator.serviceWorker.register('/sw.js')` |
| `/sw.js` | Add `addEventListener('push', ...)` listener |

## Validation

- Manual testing on iOS Safari (add to home screen, verify standalone mode)
- Manual testing on Android Chrome (add to home screen, verify standalone mode)
- Verify service worker registration in browser dev tools
- Lighthouse PWA audit score
- E2E test for manifest.json accessibility

## Context

Currently Gleanmo runs as a standard web app with full browser UI. For quantified-self tracking, users benefit from quick access from home screen and native-like interaction patterns. Push notifications could enable future features like reminders and habit prompts.

## Open Questions

- Should we implement offline caching in the service worker initially, or defer?
- What push notification use cases should we prioritize? (medication reminders, habit prompts, etc.)
- Do we need a separate icon set for iOS vs Android, or can we use a single set?
- Should the home screen prompt be dismissible permanently or show again after a period?

## Notes

### Reference Implementation

Based on PWA best practices, three components are required for native iOS feel:

1. **Web App Manifest** - The "ID card" for the app, tells iOS to hide browser UI and treat as standalone in App Switcher
2. **Apple Meta Tags** - Legacy tags required by iOS for status bar control and full-screen mode
3. **Service Worker** - Technical prerequisite for Push Notifications and Home Screen Badging APIs

### Platform Differences

- **iOS**: Requires apple-specific meta tags, more restrictive PWA support
- **Android**: Better PWA support, standard manifest properties sufficient
- Both platforms benefit from service worker for push notifications and badging
