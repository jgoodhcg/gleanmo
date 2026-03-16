# E2E Tests

Standalone Playwright scripts for end-to-end testing of the Gleanmo web app. Each test is a self-contained TypeScript file with a `main()` function — no test runner framework.

## Prerequisites

- Dev server running (`biff dev`)
- Browsers installed: `just e2e-install`

## Running Tests

```sh
just e2e-test-smoke              # Verify key pages load
just e2e-test-reading-crud       # Book + reading-log CRUD flow
just e2e-test-reading-timer      # Reading-log timer lifecycle (start)
just e2e-test-timer-stop         # Timer stop (End Session) lifecycle
just e2e-test-timer-overlap      # Overlap-aware timer stats
just e2e-test-timer-start        # Timer start timestamp freshness
just e2e-test-reorder            # Today page drag reorder
just e2e-test-today-toggle       # Today page task toggle
just e2e-test-today-quick-add    # Today page quick add
just e2e-test-today-filter       # Focus page Today filter
just e2e-test-today-canceled     # Canceled-state behavior
just e2e-test-today-mobile       # Today page mobile layout
```

Or run directly: `cd e2e && npm run test:smoke`

## Authentication

Tests authenticate via a **dev-only endpoint** that bypasses email verification:

```
GET /auth/e2e-login?email=<email>
```

This endpoint (`dev/tech/jgood/gleanmo/e2e_auth.clj`) is only loaded in dev mode and is never included in production builds. It either finds an existing user by email or creates a new one, sets the session cookie, and redirects to `/app`.

The shared helper in `scripts/auth.ts` wraps this:

```typescript
import { authenticateForDev } from './auth.js';

await authenticateForDev(page, 'e2e-mytest@localhost');
```

Each test uses a **unique email** (typically with `Date.now()` suffix) to get an isolated user with no pre-existing data. This avoids test interference without needing database cleanup.

## Test Patterns

### Structure

Each test file follows the same pattern:

```typescript
import { chromium, Page, expect } from '@playwright/test';
import { authenticateForDev } from './auth.js';

const BASE_URL = process.env.BASE_URL || 'http://localhost:8080';

async function captureScreenshot(page: Page, name: string) { ... }

async function main() {
  const browser = await chromium.launch({ headless: true });
  const page = await browser.newPage({ viewport: { width: 1280, height: 720 } });
  try {
    // test steps...
    console.log('\n=== Test Passed ===\n');
  } catch (error) {
    console.error('\n=== Test Failed ===');
    await captureScreenshot(page, 'error-state');
    process.exit(1);
  } finally {
    await browser.close();
  }
}

main();
```

### HTMX Form Submission

Forms use `hx-post` instead of standard `action`/`method`, so Playwright can't submit them natively. The workaround copies the `hx-post` value to `action`, sets `method="POST"`, and calls `form.submit()`:

```typescript
async function submitHtmxForm(form: Locator, fallbackAction: string) {
  await form.evaluate((node: HTMLElement, action: string) => {
    const formEl = node as HTMLFormElement;
    formEl.setAttribute('action', formEl.getAttribute('hx-post') || action);
    formEl.setAttribute('method', 'POST');
  }, fallbackAction);
  await form.evaluate((node: HTMLElement) => (node as HTMLFormElement).submit());
}
```

### Choices.js Select Values

Select dropdowns enhanced with Choices.js hide the native `<select>`. To set values programmatically:

```typescript
async function setHiddenOrVisibleSelectValue(select: Locator, value: string) {
  await select.evaluate((node: HTMLElement, nextValue: string) => {
    const selectEl = node as HTMLSelectElement;
    selectEl.value = nextValue;
    selectEl.dispatchEvent(new Event('input', { bubbles: true }));
    selectEl.dispatchEvent(new Event('change', { bubbles: true }));
  }, value);
}
```

### Screenshots

Screenshots are saved to `e2e/screenshots/`. Use the `SCREENSHOT_PHASE` env var to prefix filenames for before/after comparison:

```sh
SCREENSHOT_PHASE=before just e2e-test-today-mobile
# ... make changes ...
SCREENSHOT_PHASE=after just e2e-test-today-mobile
```

## File Layout

```
e2e/
  scripts/
    auth.ts              # Shared authenticateForDev() helper
    screenshot.ts        # Standalone screenshot utility
    flow.ts              # UI flow runner
    test-smoke.ts        # Smoke test: key pages load
    test-reading-crud.ts # Book + reading-log CRUD
    test-reading-timer.ts# Reading-log timer (start + active)
    test-timer-stop.ts   # Timer stop lifecycle (any entity)
    test-timer-overlap.ts# Timer overlap stats
    test-timer-start.ts  # Timer timestamp freshness
    test-today-*.ts      # Today page tests
    test-settings-*.ts   # Settings tests
  screenshots/           # Test output screenshots
  package.json           # Test scripts and dependencies
```
