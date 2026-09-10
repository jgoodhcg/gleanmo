---
title: "PWA Experience"
status: draft
description: "Verify existing PWA support and plan offline task capture after task adoption improves"
created: 2026-03-01
updated: 2026-09-07
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

### Source Status (2026-09-07)

`resources/public/manifest.json` and service worker registration already exist.
`resources/public/js/main.js` also contains home-screen installation prompts.
`resources/public/sw.js` contains a push notification display handler.
These files establish PWA scaffolding; they do not establish a complete notification delivery system.
The worker has no page caching or offline submission queue.
Treat the original installation checklist below as verification work against existing source.
Device behavior still needs manual verification.

### Offline Task Capture (Proposed Follow-up)

Prioritize [conversational task cleanup](./061-ai-assistance.md#task-adoption-sequence-2026-09-07) and its adoption check before this work.
Offline capture addresses losing a thought without cell service.
It does not resolve backlog overwhelm.

Keep the existing server architecture and add a small capture page that works after an initial online visit.
The proposed flow is:

1. Cache the capture page and required static assets during an online visit.
2. Save new tasks locally in IndexedDB before attempting delivery.
3. Show whether each task is saved on the device, awaiting login, or synchronized.
4. Retry delivery when the app opens or reconnects.
5. Remove pending entries only after the server acknowledges successful storage.

Use stable submission identifiers and server-side duplicate prevention when retrying requests.
Retain pending entries across reloads and interrupted requests.
Handle expired sessions before retrying; do not replay stale authentication or anti-forgery tokens.
Keep pending entries associated with the originating account.
Do not submit them to a different account after a login change.
Background synchronization is an optional enhancement, not a requirement for reliable foreground retries.

Limit the first slice to creating tasks and reviewing pending captures.
Full offline backlog browsing, editing existing tasks, and cross-device conflict resolution remain outside this slice.
Notification delivery and task reminders are separate work.

#### Validation for Offline Capture

- [ ] After an online setup visit, capture works when the app opens without network access.
- [ ] A pending capture survives reload and application closure on supported target devices.
- [ ] Reconnection delivers each capture once, including when the server response was lost after a successful write.
- [ ] Expired sessions retain pending captures and resume delivery after the original account authenticates.
- [ ] Account changes cannot expose or deliver another account's pending captures.
- [ ] Storage failures leave input recoverable and never report a successful local save.
- [ ] Service worker updates preserve pending captures.
- [ ] Foreground retries work without browser background synchronization support.

#### Decisions Before Implementation

- Which capture fields are necessary beyond the label?
- Should capture default to Inbox or Today?
- Which phones and browsers must support the first release?
- How should logout, device retention, and unsynchronized sensitive text be handled?

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

Gleanmo has installation scaffolding, while task actions still require server access.
See `src/tech/jgood/gleanmo/app/task.clj` for the current task mutation flow.
Notification subscriptions, server delivery, and reminder scheduling need separate investigation before claiming working notification support.

## Open Questions

- Resolve the offline capture decisions above after the task adoption check.
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
