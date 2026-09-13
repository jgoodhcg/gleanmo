# Goals 08 validation

Checked September 13, 2026.

Command:

```sh
node mockups/2026-09-motivating-dashboards/codex/check-goals-08.cjs
```

Passed:

- All 12 current series and 36 prior-year series reconcile to their displayed totals.
- The history panel contains only the selected goal; every ghost comparison row uses that same goal.
- Multiple ghost years render together with distinct, matching colors across lines and comparison bars.
- Year selection survives goal and filter changes.
- Keyboard year toggles and the no-years-selected state work.
- All seven sortable columns change ordering in both directions.
- Each goal selects and renders without JavaScript errors or invalid numeric output.
- Duration, count, weekly, and achievement filters return their expected goal counts.
- Text filtering, empty results, and clearing the filter work.
- Weekly targets use five completed days and two remaining days.
- Achievements show a target line without a required-rate projection.
- Daily history supports arrow-key navigation and updates its readout.
- The complete main graph fits within a 1440 × 900 viewport.
- Mobile and tablet have no document-level horizontal overflow.
- HTTP and HTTPS requests are blocked during checks; the mockup works offline.

Visually inspected the updated desktop and mobile screenshots with all three ghost years selected.
Weekly and achievement views passed browser checks and have refreshed screenshots.
Tablet overflow was checked automatically and a screenshot was captured.
The attached lower panels extend below the fold at 1440 × 900.
On mobile, the goals table scrolls horizontally and the lower panels stack.

Screenshots:

- `review-shots/goals-08-desktop.png`
- `review-shots/goals-08-mobile.png`
- `review-shots/goals-08-tablet.png`
- `review-shots/goals-08-weekly.png`
- `review-shots/goals-08-achievement.png`

The application visual-series baseline was waived by the user.
Before committing, `just validate` passed formatting, lint, and 112 tests with 775 assertions.
Lint reported 24 warnings in unchanged Clojure files, with no errors.
Application E2E was not run because no application code changed.
Java is available, and local clj-kondo matches the CI pin, v2026.07.24.
No Clojure files changed, so Clojure lint was not required.
No application server, live database, dependency install, or deployment was used.

Before-change screenshots are preserved as `review-shots/before-ghost-years-*.png`.
