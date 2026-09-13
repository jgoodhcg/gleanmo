# Goals 09 validation

Checked September 13, 2026.

The version 08 browser suite passed before edits.
Its baseline screenshots use `review-shots/before-goals-09-*.png`.
The existing standalone-mockup application-series waiver remains applicable.

Validation command:

```sh
node mockups/2026-09-motivating-dashboards/codex/check-goals-09.cjs
```

Passed checks:

- All 15 displayed totals and 45 generated comparison series reconcile.
- Measurement and timing filters intersect correctly, including empty results.
- Open-ended goals omit required pace, pace markers, and ghost comparisons.
- Their recent-activity values match the last 28 completed days.
- Weekly reading duration and selected-meditation session targets use the correct remaining days.
- Selection, search, all sortable columns, and keyboard history navigation work.
- Prior-year selections persist through open-ended goal selection.
- Every selected goal's main graph fits within the 1440 × 900 viewport.
- Mobile and tablet have no document-level horizontal overflow.
- No JavaScript errors or invalid numeric values appear.

Desktop and mobile screenshots were visually inspected.
The supporting panels extend below the desktop fold.
Mobile uses a horizontally scrolling table with a bounded frozen goal column.
Screenshots cover open-ended, book-specific, weekly meditation, weekly reading, dated, and best-performance goals.
Final screenshots use `review-shots/goals-09-*.png`.

Chromium required sandbox escalation to launch.
The browser checks blocked HTTP and HTTPS requests.
No application server, live database, installation, or deployment was used.
Before committing, `just validate` passed formatting, lint, and 112 tests with 775 assertions.
Lint reported warnings in unchanged files, with no errors.
Application E2E was not run because only standalone mockup and documentation files changed.
No Clojure files changed, so Clojure lint was not required.
