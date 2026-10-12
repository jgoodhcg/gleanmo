// E2E test for the Cronometer status page (roadmap/056-cronometer-integration.md)
// Usage: npm run test:cronometer
//
// This test:
// 1. Opens the account page and follows its link to /app/cronometer
// 2. Verifies the page renders for a user with no connection and no synced
//    data: the unconfigured notice when CREDENTIALS_KEY is absent (CI), or
//    the connect form with an empty password field when it is set
// 3. Captures the page at desktop and mobile widths
//
// It cannot exercise a live sync: that needs real Cronometer credentials,
// which CI does not have and agents must not use. The sync itself is covered
// by test/tech/jgood/gleanmo/test/cronometer/sync_test.clj with a stubbed
// client.

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
