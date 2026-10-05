---
title: "PWA Experience"
status: draft
description: "Verify existing PWA support, show paths in standalone mode, and plan push delivery for reminders; offline capture moved to 093"
created: 2026-03-01
updated: 2026-10-05
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

### Offline Task Capture (moved to 093, 2026-10-05)

The offline capture design now lives in [093-inbox-quick-capture.md](./093-inbox-quick-capture.md).
The user chose to build the capture page offline-first from the start, because a page served from the cache also opens instantly when online.
That unit also answers the old open question here: captures default to Inbox.

### Reminders and Notifications (decided 2026-10-05)

Replacing Apple Reminders' scheduled alerts is planned in three milestones, recorded in [086-recurring-tasks.md](./086-recurring-tasks.md#reminder-milestones-2026-10-05).
Push notifications are the third milestone, not the first.
The user checks the app daily, so in-app reminders come first.

When push is built, it needs:

- A time-of-day on tasks (a new attribute, since `task/due-on` is a date).
- VAPID keys as App Platform environment variables, a subscribe step started by a user tap (iOS requires that), and a stored subscription per device.
- A Java web-push library and a chime job that sends due reminders once each.
  Chime runs per container. That is acceptable while production is one App Platform instance; [077-scheduled-work-multi-instance.md](./077-scheduled-work-multi-instance.md) applies before scaling out.
- iOS and Android both. On iOS, web push only reaches PWAs installed on the home screen, and it cannot use Time Sensitive or Critical alerts.

**Geofences are on hold.**
A PWA has no background location or geofencing.
The single location-based reminder stays in Apple Reminders.

### Visible Paths in Standalone Mode (Proposed, 2026-10-04)

The installed PWA should be the normal way to use Gleanmo.
Standalone mode hides the address bar, which hides the current path.
The user still wants paths, for two reasons:

- To name routes and endpoints when discussing the app, including with agents.
- To open a few pages as browser tabs when multitasking on desktop.

Proposed shape, to be refined:

- When `display-mode: standalone` is active, show the current path somewhere unobtrusive.
  Candidates are the page shell footer, a long-press on the page title, or a small info control.
- Provide **Copy link** for the full URL.
- Provide **Open in browser**, which opens the current URL outside the installed app.
  Platform behavior varies here, especially on iOS, so verify it on each device.
- The regular browser tab keeps working as it does today.

`isStandalone` detection already exists in `resources/public/js/main.js`.

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

- Offline capture decisions now live in [093-inbox-quick-capture.md](./093-inbox-quick-capture.md).
- What push notification use cases should we prioritize beyond task reminders? (medication reminders, habit prompts, etc.)
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
