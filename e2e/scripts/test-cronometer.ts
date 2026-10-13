// E2E test for the Cronometer status page (roadmap/056-cronometer-integration.md)
// Usage: npm run test:cronometer
//
// This test:
// 1. Opens the account page and follows its link to /app/cronometer
// 2. Verifies the page renders for a user with no connection and no synced
//    data: the unconfigured notice when CREDENTIALS_KEY is absent (CI), or
//    the connect form with an empty password field when it is set
// 3. Captures the page at desktop and mobile widths
// 4. With the dev-only fake Cronometer API on (FAKE_CRONOMETER=true) and
//    CREDENTIALS_KEY set: connects a second account with made-up credentials,
//    makes the fake reject the next session once (exercising re-login), presses
//    Sync now, and checks that the counts and macros fill in
//
// The sync never talks to the real Cronometer: step 4 runs only when
// GET /dev/cronometer answers, which is the fake's own route, and the same
// flag points the server's client at it. Without the fake the step is skipped
// locally and fails in CI, which sets both variables (validate.yml).

import { chromium, Page, expect } from '@playwright/test';
import { authenticateForDev } from './auth.js';

const BASE_URL = process.env.BASE_URL || 'http://localhost:8080';

async function captureScreenshot(page: Page, name: string) {
  const phase = process.env.SCREENSHOT_PHASE;
  const prefix = phase ? `${phase}-` : '';
  const filepath = `screenshots/${prefix}cronometer-${name}.png`;
  await page.screenshot({ path: filepath, fullPage: true });
  console.log(`  [screenshot] ${filepath}`);
}

function check(condition: boolean, message: string) {
  if (!condition) throw new Error(message);
  console.log(`  [ok] ${message}`);
}

async function syncFromFake(page: Page) {
  // Invented credentials: the fake accepts any email, and this one is its
  // own account so the step never touches the empty-state user above.
  const cronometerEmail = 'e2e-fake@example.com';
  await page.setViewportSize({ width: 1280, height: 900 });
  await authenticateForDev(page, 'e2e-cronometer-sync@localhost');
  await page.goto(`${BASE_URL}/app/cronometer`, { waitUntil: 'networkidle' });
  await expect(page.locator('[data-testid="cronometer-fake-notice"]')).toBeVisible();
  check(true, 'the page says the fake is on');

  const form = page.locator('#cronometer-connect-form');
  if (!(await form.isVisible())) {
    await page.locator('summary', { hasText: 'Change credentials' }).click();
  }
  await page.fill('#cronometer-email', cronometerEmail);
  await page.fill('#cronometer-password', 'any-password');
  await form.locator('button[type="submit"]').click();
  await page.waitForURL('**/app/cronometer?status=connected');
  await expect(page.locator('[data-testid="cronometer-connected-email"]'))
    .toHaveText(cronometerEmail);
  check(true, 'connects with made-up credentials');

  const reject = await page.request.post(`${BASE_URL}/dev/cronometer/reject-next-session`, {
    data: { email: cronometerEmail },
  });
  check(reject.status() === 200, 'the fake will reject the next session once');

  await page.locator('[data-testid="cronometer-sync-now"]').click();
  await page.waitForURL((url) => url.searchParams.get('status') === 'synced', {
    timeout: 60000,
  });
  await page.waitForLoadState('networkidle');
  await expect(page.locator('[data-testid="cronometer-flash"]')).toContainText('Synced 7 days');
  check(true, 'Sync now reports seven days, despite the rejected session');

  const counts = await page.locator('[data-testid="cronometer-count"]').allInnerTexts();
  for (const text of counts) {
    const n = Number(text.match(/(\d+)\s*$/)?.[1] ?? 0);
    check(n > 0, `count filled: ${text.replace(/\s+/g, ' ')}`);
  }
  // The fake gives every day an untimed Daily Activity and most days a timed
  // walk, so a 7-day sync yields at least 7 activities and some sessions.
  const countOf = async (label: string) => {
    const row = page.locator('[data-testid="cronometer-count"]', { hasText: label });
    return Number((await row.innerText()).match(/(\d+)\s*$/)?.[1] ?? 0);
  };
  const activities = await countOf('Activities');
  check(activities >= 7, `activities cover every synced day: ${activities}`);
  const sessions = await countOf('Timed activity sessions');
  check(sessions > 0 && sessions < activities, `timed sessions imported: ${sessions}`);

  await expect(page.locator('[data-testid="cronometer-status"]')).not.toContainText('never');
  check(true, 'last sync is set');

  const macros = page.locator('[data-testid="cronometer-macros"]');
  await expect(macros).toBeVisible();
  const energy = await macros.locator('.text-lg').first().innerText();
  check(Number(energy.match(/[\d.]+/)?.[0] ?? 0) > 500, `latest day's energy is plausible: ${energy}`);
  await captureScreenshot(page, '04-synced-desktop');

  await page.setViewportSize({ width: 390, height: 844 });
  await page.reload({ waitUntil: 'networkidle' });
  await captureScreenshot(page, '05-synced-mobile');
  const overflow = await page.evaluate(
    () => document.documentElement.scrollWidth > window.innerWidth,
  );
  check(!overflow, 'no horizontal scroll at phone width when synced');
}

async function main() {
  console.log('\n=== Cronometer Status Page Test ===\n');

  const browser = await chromium.launch({ headless: true });
  const context = await browser.newContext({ viewport: { width: 1280, height: 900 } });
  const page = await context.newPage();

  try {
    // A dedicated account that never connects, so the page is always empty.
    const testEmail = 'e2e-cronometer@localhost';

    console.log('1. Following the account page link...');
    await authenticateForDev(page, testEmail);
    await page.goto(`${BASE_URL}/app/my-user`, { waitUntil: 'networkidle' });
    await captureScreenshot(page, '01-account');
    const link = page.locator('a[href="/app/cronometer"]');
    await expect(link).toBeVisible({ timeout: 10000 });
    check(true, 'account page links to the Cronometer page');
    await link.click();
    await page.waitForURL('**/app/cronometer');
    await page.waitForLoadState('networkidle');

    console.log('2. Checking the empty state...');
    await captureScreenshot(page, '02-desktop');
    await expect(page.locator('h1', { hasText: 'Cronometer' })).toBeVisible();
    check(true, 'page header renders');

    const unconfigured = page.locator('[data-testid="cronometer-unconfigured"]');
    const connectForm = page.locator('#cronometer-connect-form');
    if (await unconfigured.isVisible()) {
      check(true, 'without CREDENTIALS_KEY the page says so');
      check((await connectForm.count()) === 0, 'and offers no connect form');
    } else {
      await expect(connectForm).toBeVisible();
      check(true, 'with CREDENTIALS_KEY the connect form renders');
      check(
        (await page.inputValue('#cronometer-password')) === '',
        'the password field is empty',
      );
      check(
        (await page.locator('[data-testid="cronometer-sync-now"]').count()) === 0,
        'Sync now is not offered before connecting',
      );
    }

    const status = page.locator('[data-testid="cronometer-status"]');
    await expect(status).toBeVisible();
    await expect(status).toContainText('never');
    check(true, 'status shows no sync yet');
    const counts = page.locator('[data-testid="cronometer-count"]');
    check((await counts.count()) > 0, 'row counts render');
    for (const text of await counts.allInnerTexts()) {
      check(/\b0\b/.test(text), `count is zero: ${text.replace(/\s+/g, ' ')}`);
    }

    console.log('3. Mobile...');
    await page.setViewportSize({ width: 390, height: 844 });
    await page.reload({ waitUntil: 'networkidle' });
    await captureScreenshot(page, '03-mobile');
    const overflow = await page.evaluate(
      () => document.documentElement.scrollWidth > window.innerWidth,
    );
    check(!overflow, 'no horizontal scroll at phone width');

    console.log('4. Syncing from the dev fake...');
    const fake = await page.request.get(`${BASE_URL}/dev/cronometer`);
    const configured = !(await unconfigured.isVisible());
    if (fake.status() !== 200 || !configured) {
      const reason = `fake ${fake.status() === 200 ? 'on' : 'off'}, ` +
        `CREDENTIALS_KEY ${configured ? 'set' : 'unset'}`;
      if (process.env.CI) {
        throw new Error(`CI must run the fake sync (${reason}); see validate.yml`);
      }
      console.log(`  [skip] sync flow needs FAKE_CRONOMETER=true and CREDENTIALS_KEY (${reason})`);
    } else {
      await syncFromFake(page);
    }

    console.log('\n=== Cronometer Status Page Test PASSED ===\n');
  } catch (error) {
    await captureScreenshot(page, 'error-state');
    console.error('\nTest failed:', error);
    process.exitCode = 1;
  } finally {
    await browser.close();
  }
}

main();
