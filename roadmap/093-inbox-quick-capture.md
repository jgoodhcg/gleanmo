---
title: "Inbox Quick Capture"
status: draft
description: "A capture screen as light as today's quick-add that puts tasks into the inbox, with optional project and notes and room to enter several in a row"
created: 2026-10-04
updated: 2026-10-04
tags: [tasks, ux, mobile, capture]
priority: medium
---

# Inbox Quick Capture

## Intent

Make Gleanmo the place to write down "this should become a task later", so that Apple Reminders can go back to scheduled and geofenced alerts only.

Today, Reminders works as a capture queue because nothing in Gleanmo is quick enough for that job.
The fastest entry point is the today page's quick-add (`POST /app/task/quick-add-today`).
It creates the task as `:now`, with today's focus date, so it skips the inbox completely.
The full task form reaches the inbox, but it has too many fields for capturing a thought.
That is worse when the user wants to capture several items at once.

The `inbox` state exists for exactly this.
What is missing is an entry point that reaches it.

## Specification

A dedicated capture screen, for example `/app/task/capture`:

- A label input, focused on load.
  Submitting creates a task with `:task/state :inbox` and no focus date.
- Optional **project** (Choices.js searchable select, per the UI rules) and **notes** fields.
  They are visible but never required.
- After a submit, the form clears and keeps focus so the next item can be typed right away.
  A short list of items captured in this visit confirms what was saved.
- Project selection should probably stay set between submits, since a batch of captures often shares a project.
- Reachable in one tap from the mobile tab bar or the sidebar Quick Add section, and from the task pages.

Follow the mutation rule in `AGENTS.md`.
Either do a plain form post that `303`s back to the capture page,
or refresh the snapshot before rendering a fragment, as `quick-add-today!` does.

## Validation

- [ ] Unit test: capture creates an `:inbox` task with no focus date; project and notes are stored when given.
- [ ] E2E: capture three tasks in a row without leaving the page; all three appear in the inbox.
- [ ] E2E smoke covers the new route; `e2e/scripts/manifest.ts` lists it.
- [ ] Manual, on the phone PWA: open, capture, done in a few seconds.

## Scope

Not included:

- Offline capture.
  It is covered by [043-pwa-experience.md](./043-pwa-experience.md#offline-task-capture-proposed-follow-up), which this screen would later become the natural page for.
- An inbox processing or triage view.
- Changes to today's quick-add, beyond possibly linking to the capture screen.

## Context

- Today quick-add: `quick-add-today!` in `src/tech/jgood/gleanmo/app/task.clj`; routes at the bottom of that file.
- Task schema and states: `src/tech/jgood/gleanmo/schema/task_schema.clj`.
- Related: [040-today-ux-polish.md](./040-today-ux-polish.md) (project selection in today's quick-add),
  [043-pwa-experience.md](./043-pwa-experience.md) (offline capture; "should capture default to Inbox or Today?" — this unit answers Inbox),
  [061-ai-assistance.md](./061-ai-assistance.md) (agent-assisted processing of what lands in the inbox).
- Origin: 2026-10-04 Reminders cleanup, after finding Reminders had become a capture queue.

## Open Questions (draft only)

- Separate route, or a mode of the today page?
- Which other optional fields, if any, besides project and notes (domain? sensitive?)
- Where exactly does the entry point go on mobile: tab bar, home quick actions, or both?

## Notes
