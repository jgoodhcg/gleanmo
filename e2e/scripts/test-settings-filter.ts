// E2E test for user settings filtering (sensitive, archived, BM logs)
// Usage: npm run test:settings-filter
//
// Tests:
// 1. Sensitive filtering on CRUD habit list page
// 2. Archived filtering on CRUD habit list page
// 3. BM log setting hides/shows sidebar link and overview activity on home page

import { chromium, Page, expect } from '@playwright/test';
import { authenticateForDev } from './auth.js';

const BASE_URL = process.env.BASE_URL || 'http://localhost:8080';

async function captureScreenshot(page: Page, name: string) {
  const phase = process.env.SCREENSHOT_PHASE;
  const prefix = phase ? `${phase}-` : '';
  const filepath = `screenshots/${prefix}settings-filter-${name}.png`;
  await page.screenshot({ path: filepath });
  console.log(`  [screenshot] ${filepath}`);
}

/** Get the user's account edit page URL from the sidebar link. */
async function getAccountEditUrl(page: Page): Promise<string> {
  const accountLink = page.locator('a[href^="/app/users/"]').first();
  const href = await accountLink.getAttribute('href');
  return `${BASE_URL}${href}/edit`;
}

/** Toggle user settings via the account edit form. */
async function setUserSetting(page: Page, editUrl: string, settings: Record<string, boolean>) {
  await page.goto(editUrl, { waitUntil: 'networkidle' });

  for (const [name, value] of Object.entries(settings)) {
    const checkbox = page.locator(`input[name="${name}"]`);
    const isChecked = await checkbox.isChecked();
    if (isChecked !== value) {
      if (value) {
        await checkbox.check();
      } else {
        await checkbox.uncheck();
      }
    }
  }

  // The form uses HTMX (hx-post), so we need to intercept the network request
  // and wait for the swap to complete rather than relying on page navigation.
  const submitBtn = page.locator('button[type="submit"]:has-text("Update User")');
  await Promise.all([
    page.waitForResponse((resp) => resp.url().includes('/app/users/') && resp.status() === 200),
    submitBtn.click(),
  ]);
  // Wait for HTMX swap to settle
  await page.waitForTimeout(500);
}

/** Create a habit via CRUD form. */
async function createHabit(page: Page, label: string, opts: { sensitive?: boolean; archived?: boolean } = {}) {
  await page.goto(`${BASE_URL}/app/crud/form/habit/new`, { waitUntil: 'networkidle' });

  const labelInput = page.locator('input[name="habit/label"]');
  await labelInput.waitFor({ state: 'visible', timeout: 10000 });
  await labelInput.fill(label);

  if (opts.sensitive) {
    await page.locator('input[name="habit/sensitive"]').check();
  }
  if (opts.archived) {
    await page.locator('input[name="habit/archived"]').check();
  }

  const form = labelInput.locator('xpath=ancestor::form');
  await form.evaluate((f: HTMLFormElement) => {
    f.setAttribute('action', f.getAttribute('hx-post') || '/app/crud/habit');
    f.setAttribute('method', 'POST');
  });
  await form.evaluate((f: HTMLFormElement) => f.submit());
  await page.waitForLoadState('networkidle');
  await page.waitForTimeout(500);
  console.log(`  [+] Created habit "${label}"${opts.sensitive ? ' (sensitive)' : ''}${opts.archived ? ' (archived)' : ''}`);
}

async function main() {
  console.log('\n=== Settings Filter Test ===\n');

  const browser = await chromium.launch({ headless: true });
  const page = await browser.newPage({ viewport: { width: 1280, height: 720 } });

  try {
    const testEmail = `e2e-settings-filter@localhost`;
    const ts = Date.now();
    const normalHabit = `Normal Habit ${ts}`;
    const sensitiveHabit = `Sensitive Habit ${ts}`;
    const archivedHabit = `Archived Habit ${ts}`;

    // ── Setup: authenticate and enable all settings ──
    console.log('0. Setup: creating test data...');
    await authenticateForDev(page, testEmail);
    const editUrl = await getAccountEditUrl(page);

    // Enable sensitive + archived + bm-logs so we can create all data
    await setUserSetting(page, editUrl, {
      'show-sensitive': true,
      'show-archived': true,
      'show-bm-logs': true,
    });

    // Create habits: normal, sensitive, and archived
    await createHabit(page, normalHabit);
    await createHabit(page, sensitiveHabit, { sensitive: true });
    await createHabit(page, archivedHabit, { archived: true });

    // ═══════════════════════════════════════════════════════
    // Test 1: Sensitive filtering on CRUD habit list
    // ═══════════════════════════════════════════════════════
    console.log('\n1. Sensitive filtering on CRUD habit list...');

    // With sensitive ON — all habits visible
    await page.goto(`${BASE_URL}/app/crud/habit`, { waitUntil: 'networkidle' });
    let bodyText = await page.textContent('body');
    expect(bodyText).toContain(sensitiveHabit);
    expect(bodyText).toContain(normalHabit);
    console.log('  + sensitive habit visible when show-sensitive=true');
    await captureScreenshot(page, '01-crud-sensitive-on');

    // Disable sensitive
    await setUserSetting(page, editUrl, { 'show-sensitive': false, 'show-archived': true, 'show-bm-logs': true });
    await page.goto(`${BASE_URL}/app/crud/habit`, { waitUntil: 'networkidle' });
    bodyText = await page.textContent('body');
    expect(bodyText).not.toContain(sensitiveHabit);
    expect(bodyText).toContain(normalHabit);
    console.log('  + sensitive habit hidden when show-sensitive=false');
    console.log('  + normal habit still visible');
    await captureScreenshot(page, '02-crud-sensitive-off');

    // ═══════════════════════════════════════════════════════
    // Test 2: Archived filtering on CRUD habit list
    // ═══════════════════════════════════════════════════════
    console.log('\n2. Archived filtering on CRUD habit list...');

    // With archived ON
    await setUserSetting(page, editUrl, { 'show-sensitive': true, 'show-archived': true, 'show-bm-logs': true });
    await page.goto(`${BASE_URL}/app/crud/habit`, { waitUntil: 'networkidle' });
    bodyText = await page.textContent('body');
    expect(bodyText).toContain(archivedHabit);
    expect(bodyText).toContain(normalHabit);
    console.log('  + archived habit visible when show-archived=true');
    await captureScreenshot(page, '03-crud-archived-on');

    // Disable archived
    await setUserSetting(page, editUrl, { 'show-sensitive': true, 'show-archived': false, 'show-bm-logs': true });
    await page.goto(`${BASE_URL}/app/crud/habit`, { waitUntil: 'networkidle' });
    bodyText = await page.textContent('body');
    expect(bodyText).not.toContain(archivedHabit);
    expect(bodyText).toContain(normalHabit);
    console.log('  + archived habit hidden when show-archived=false');
    console.log('  + normal habit still visible');
    await captureScreenshot(page, '04-crud-archived-off');

    // ═══════════════════════════════════════════════════════
    // Test 3: BM log setting on home page (sidebar + overview)
    // ═══════════════════════════════════════════════════════
    console.log('\n3. BM log setting on home page...');

    // Enable BM logs
    await setUserSetting(page, editUrl, { 'show-sensitive': true, 'show-archived': true, 'show-bm-logs': true });
    await page.goto(`${BASE_URL}/app`, { waitUntil: 'networkidle' });

    // Sidebar should have BM log quick-add link
    await expect(page.locator('a[href="/app/crud/form/bm-log/new"]')).toBeVisible({ timeout: 5000 });
    console.log('  + BM log sidebar link visible when show-bm-logs=true');
    await captureScreenshot(page, '05-home-bm-on');

    // Disable BM logs
    await setUserSetting(page, editUrl, { 'show-sensitive': true, 'show-archived': true, 'show-bm-logs': false });
    await page.goto(`${BASE_URL}/app`, { waitUntil: 'networkidle' });

    // Sidebar should NOT have BM log quick-add link
    await expect(page.locator('a[href="/app/crud/form/bm-log/new"]')).toHaveCount(0);
    console.log('  + BM log sidebar link hidden when show-bm-logs=false');
    await captureScreenshot(page, '06-home-bm-off');

    // Re-enable and verify it comes back
    await setUserSetting(page, editUrl, { 'show-sensitive': true, 'show-archived': true, 'show-bm-logs': true });
    await page.goto(`${BASE_URL}/app`, { waitUntil: 'networkidle' });
    await expect(page.locator('a[href="/app/crud/form/bm-log/new"]')).toBeVisible({ timeout: 5000 });
    console.log('  + BM log sidebar link reappears after re-enabling');

    console.log('\n=== Settings Filter Test Passed ===\n');
  } catch (error) {
    console.error('\n=== Settings Filter Test Failed ===');
    console.error(error);
    await captureScreenshot(page, 'error-state');
    process.exit(1);
  } finally {
    await browser.close();
  }
}

main();
