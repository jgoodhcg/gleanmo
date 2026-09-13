# Goals 10 validation

Checked September 13, 2026.
Version 09 was committed as `ebe7e4e` before this iteration.
Its committed screenshots provide the visual baseline.
The existing standalone-mockup application-series waiver remains applicable.

Validation command:

```sh
node mockups/2026-09-motivating-dashboards/codex/check-goals-10.cjs
```

Passed checks:

- All 18 displayed goal totals and 45 generated prior-year series reconcile.
- Existing timing filters, measurement filters, sorting, search, and empty results still work.
- Existing weekly duration, session counts, open-ended totals, and best-performance views still work.
- All three book goals switch between page, chapter, and audiobook positions.
- Each selected measure displays the latest recorded value and the matching book total.
- Solid lines connect consecutive measured logs; dotted lines bridge logs that omit the selected measure.
- Dotted links preserve the recorded point count and use a distinct dash pattern.
- Each goal remembers its selected measure when switching between goals.
- Keyboard activation works for the measure buttons.
- Deadline goals expose an optional even-pace guide; open-ended goals omit it.
- Book goals omit prior-year controls and comparisons.
- Reaching the chapter total without a finished flag leaves the goal in progress.
- A finished flag completes the goal even when its page position is below the book total.
- The data disclosure shows book totals and all six Odyssey reading logs.
- Every goal's main chart fits within a 1440 × 900 viewport.
- Mobile and tablet have no document-level horizontal overflow.
- No JavaScript errors or invalid numeric output remain.

Visually inspected open-ended and deadline completion views on desktop, and the completion view on mobile.
The chart controls, source labels, and supporting panels fit their available space.
The mockup preserves a horizontally scrolling table on mobile.
Screenshots use `review-shots/goals-10-*.png`.

Chromium required sandbox escalation to launch.
The checks blocked HTTP and HTTPS requests.
No application server, live database, dependency installation, or deployment was used.

Before the version 10 commit, `just validate` passed formatting, lint, and 112 tests with 775 assertions.
Existing Clojure lint warnings remained, with no errors.
Version 10 changes only standalone HTML, browser checks, and documentation.
Application E2E was not run because this iteration changes no application code.
No Clojure source changed.
