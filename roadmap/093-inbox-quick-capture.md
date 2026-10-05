---
title: "Inbox Quick Capture"
status: draft
description: "An offline-first capture page that opens instantly from the device cache, saves tasks locally, and syncs them into the inbox, so Reminders stops being a capture queue"
created: 2026-10-04
updated: 2026-10-05
tags: [tasks, ux, mobile, capture, pwa, offline]
priority: medium
---

# Inbox Quick Capture

## Intent

Make Gleanmo the place to write down "this should become a task later", so that Apple Reminders can go back to scheduled and geofenced alerts only.

Today, Reminders works as a capture queue because nothing in Gleanmo is quick enough for that job.
The user's own words: they capture in Reminders "without waiting for my pwa to load".
So the problem is load time as much as form weight.

The fastest entry point is the today page's quick-add (`POST /app/task/quick-add-today`).
It creates the task as `:now`, with today's focus date, so it skips the inbox completely.
The full task form reaches the inbox, but it has too many fields for capturing a thought.
That is worse when the user wants to capture several items at once.

The `inbox` state exists for exactly this.
What is missing is an entry point that reaches it.

## Decisions (2026-10-05)

- **Offline-first from the start.**
  The capture page is served cache-first by the service worker, so it opens instantly with or without network.
  Offline support and fast opening are the same feature here, so offline is no longer a later follow-up.
  This unit now owns the offline capture design that used to sit in [043-pwa-experience.md](./043-pwa-experience.md).
- **Captures default to Inbox**, not Today. This answers 043's open question.
- **iOS Shortcut: maybe later, and only if trivial.**
  The user is not keen on it.
  If the scoped agent API in [061-ai-assistance.md](./061-ai-assistance.md) makes "post a label to the inbox" a thin client, a Shortcut can be added then.
  It is not part of this unit.

## Specification

### Capture page

A dedicated capture screen, for example `/app/task/capture`:

- A label input, focused on load.
  Submitting creates a task with `:task/state :inbox` and no focus date.
- Optional **project** (Choices.js searchable select, per the UI rules) and **notes** fields.
  They are visible but never required.
  When offline, the project list comes from the last cached copy.
- After a submit, the form clears and keeps focus so the next item can be typed right away.
  A list of items captured in this visit shows each one's state: saved on device, waiting for login, or synced.
- Project selection should probably stay set between submits, since a batch of captures often shares a project.
- Reachable in one tap from the mobile tab bar or the sidebar Quick Add section, and from the task pages.

### Offline-first delivery

1. The service worker (`resources/public/sw.js`) caches the capture page and its static assets during an online visit, and serves them cache-first.
2. Each capture is written to IndexedDB before any delivery attempt.
3. Delivery retries when the app opens or reconnects.
   Background sync is an optional extra, not a requirement.
4. A pending entry is removed only after the server confirms it stored the task.

The client generates a UUID per capture, and the server uses it as the task's `:xt/id`.
If a retry arrives for an id that already exists, the server confirms it without writing again.
This covers the case where the write succeeded but the response was lost.

Authentication and anti-forgery:

- The cached page carries a stale anti-forgery token, so delivery must not reuse it.
  Either fetch a fresh token at sync time, or post to a JSON endpoint that authenticates by session alone.
- When the session has expired, pending entries stay on the device and resume after the same account logs in.
- Pending entries are tied to the account that made them and are never delivered to a different account.

Follow the mutation rule in `AGENTS.md` for the online submit path.
Either do a plain form post that `303`s back to the capture page,
or refresh the snapshot before rendering a fragment, as `quick-add-today!` does.

### Launch path on iOS

iOS opens the installed PWA at the manifest `start_url` and ignores manifest shortcuts.
So an instant capture screen either has to be the launch page or sit one tap from it.
Decide this during implementation.

## Validation

- [ ] Unit test: capture creates an `:inbox` task with no focus date; project and notes are stored when given.
- [ ] Unit test: a repeated delivery with the same client id creates one task.
- [ ] E2E: capture three tasks in a row without leaving the page; all three appear in the inbox.
- [ ] E2E smoke covers the new route; `e2e/scripts/manifest.ts` lists it.
- [ ] After an online setup visit, capture works when the app opens without network access.
- [ ] A pending capture survives reload and closing the app on the phone.
- [ ] Reconnection delivers each capture once, including when the server response was lost after a successful write.
- [ ] Expired sessions keep pending captures and resume delivery after the original account logs in.
- [ ] Account changes cannot expose or deliver another account's pending captures.
- [ ] Storage failures leave the input recoverable and never report a successful local save.
- [ ] Service worker updates keep pending captures.
- [ ] Manual, on the phone PWA: open, capture, done in a few seconds, online and in airplane mode.

## Scope

Not included:

- Offline browsing of the backlog, editing existing tasks offline, and cross-device conflict resolution.
- An inbox processing or triage view.
- Changes to today's quick-add, beyond possibly linking to the capture screen.
- An iOS Shortcut (see Decisions).

## Context

- Today quick-add: `quick-add-today!` in `src/tech/jgood/gleanmo/app/task.clj`; routes at the bottom of that file.
- Task schema and states: `src/tech/jgood/gleanmo/schema/task_schema.clj`.
- PWA scaffolding: `resources/public/manifest.json`, `resources/public/sw.js` (push display only, no caching yet), registration in `resources/public/js/main.js`.
- Anti-forgery middleware: `src/tech/jgood/gleanmo/middleware.clj`.
- Related: [040-today-ux-polish.md](./040-today-ux-polish.md) (project selection in today's quick-add),
  [043-pwa-experience.md](./043-pwa-experience.md) (the rest of the PWA work),
  [061-ai-assistance.md](./061-ai-assistance.md) (agent-assisted processing of what lands in the inbox, and the API a Shortcut would use).
- Origin: 2026-10-04 Reminders cleanup, after finding Reminders had become a capture queue.
  Offline-first decided 2026-10-05.

## Open Questions (draft only)

- Separate route, or a mode of the today page?
- Which other optional fields, if any, besides project and notes (domain? sensitive?)
- Where exactly does the entry point go on mobile: tab bar, home quick actions, `start_url`, or a combination?
- Which phones and browsers must the first release support?
- How should logout, device retention, and unsynced sensitive text be handled?

## Notes
