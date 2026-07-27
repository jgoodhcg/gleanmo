// E2E test for stop timer (End Session) functionality
// Usage: npm run test:timer-stop
//
// This test verifies the full timer lifecycle with stop-in-place behavior:
// 1. Creates a book
// 2. Starts a timer via the one-tap Start Timer button (no form)
// 3. Clicks "End Session" on the active timer
// 4. Verifies we stay on the timer page (no forced edit-form round trip)
// 5. Verifies the timer is no longer active and appears in recent logs
// 6. Opens the recent-log edit link (the opt-in annotation path) and checks
//    the end time was set

import { chromium, Page, Locator, expect } from '@playwright/test';
import { authenticateForDev } from './auth.js';

const BASE_URL = process.env.BASE_URL || 'http://localhost:8080';

async function captureScreenshot(page: Page, name: string) {
  const phase = process.env.SCREENSHOT_PHASE;
  const prefix = phase ? `${phase}-` : '';
  const filepath = `screenshots/${prefix}timer-stop-${name}.png`;
  await page.screenshot({ path: filepath });
  console.log(`  [screenshot] ${filepath}`);
}

async function submitHtmxForm(form: Locator, fallbackAction: string) {
  await form.evaluate((node: HTMLElement, action: string) => {
    const formEl = node as HTMLFormElement;
    formEl.setAttribute('action', formEl.getAttribute('hx-post') || action);
    formEl.setAttribute('method', 'POST');
  }, fallbackAction);
  await form.evaluate((node: HTMLElement) => (node as HTMLFormElement).submit());
}

async function createBook(page: Page, title: string) {
  await page.goto(`${BASE_URL}/app/crud/form/book/new`);
  await page.waitForLoadState('networkidle');

  const form = page.locator('#book-new-form');
  await expect(form).toBeVisible({ timeout: 10000 });
  await form.locator('textarea[name="book/title"]').fill(title);

  await submitHtmxForm(form, '/app/crud/book');
  await page.waitForLoadState('networkidle');
  await page.waitForTimeout(300);
  console.log(`  [+] Created book "${title}"`);
}

async function main() {
  console.log('\n=== Timer Stop (End Session) Test ===\n');

  const browser = await chromium.launch({ headless: true });
  const page = await browser.newPage({ viewport: { width: 1280, height: 720 } });

  try {
    const email = `e2e-timer-stop-${Date.now()}@localhost`;
    const bookTitle = `Stop Test ${Date.now()}`;

    // 1. Create a book
    console.log('1. Creating book...');
    await authenticateForDev(page, email);
    await createBook(page, bookTitle);

    // 2. Navigate to timer page, start via one-tap POST
    console.log('\n2. Starting timer...');
    await page.goto(`${BASE_URL}/app/timer/reading-log`);
    await page.waitForLoadState('networkidle');

    const startButton = page.locator('button:has-text("Start Timer")').first();
    await expect(startButton).toBeVisible({ timeout: 10000 });
    await startButton.click();
    await page.waitForLoadState('networkidle');
    if (page.url().includes('/app/crud/form/')) {
      throw new Error(`Start bounced to a CRUD form: ${page.url()}`);
    }
    await captureScreenshot(page, '01-active-timer');

    // 3. Verify active timer is present
    console.log('\n3. Verifying active timer...');
    await expect(page.locator('text=Active Timers').first()).toBeVisible({ timeout: 10000 });
    await expect(page.locator(`text=${bookTitle}`).first()).toBeVisible({ timeout: 10000 });
    console.log(`  [+] Active timer visible for "${bookTitle}"`);

    // 4. Click "End Session" — must return to the timer page, not an edit form
    console.log('\n4. Clicking End Session...');
    const endSessionLink = page.locator('a:has-text("End Session")').first();
    await expect(endSessionLink).toBeVisible({ timeout: 10000 });
    const stopHref = await endSessionLink.getAttribute('href');
    console.log(`  [i] End Session href: ${stopHref}`);
    await endSessionLink.click();
    await page.waitForLoadState('networkidle');
    await captureScreenshot(page, '02-after-stop');

    // 5. Verify stop-in-place: back on the timer page, no edit form
    console.log('\n5. Verifying stop stayed on the timer page...');
    const currentUrl = page.url();
    console.log(`  [i] URL after stop: ${currentUrl}`);
    if (currentUrl.includes('/app/crud/form/')) {
      throw new Error(`Stop bounced to an edit form: ${currentUrl}`);
    }
    if (!currentUrl.includes('/app/timer/reading-log')) {
      throw new Error(`Expected to stay on /app/timer/reading-log, got: ${currentUrl}`);
    }
    console.log('  [+] Stayed on the timer page');

    // 6. Verify no active timers remain
    const activeTimerCards = page.locator('a:has-text("End Session")');
    const activeCount = await activeTimerCards.count();
    if (activeCount > 0) {
      throw new Error(`Expected 0 active timers after stop, found ${activeCount}`);
    }
    console.log('  [+] No active timers (timer was stopped successfully)');

    // 7. Verify the completed session appears in recent logs
    console.log('\n6. Verifying completed session in recent logs...');
    await expect(page.locator(`text=${bookTitle}`).first()).toBeVisible({ timeout: 10000 });
    console.log(`  [+] Completed session for "${bookTitle}" visible in recent logs`);

    // 8. Opt-in annotation path: open the recent-log edit link, check end time
    console.log('\n7. Opening recent-log edit link...');
    const editLink = page.locator('a[href*="/app/crud/form/reading-log/edit/"]').first();
    await expect(editLink).toBeVisible({ timeout: 10000 });
    await editLink.click();
    await page.waitForLoadState('networkidle');
    await captureScreenshot(page, '03-edit-form-from-recent');

    const endInput = page.locator('input[name="reading-log/end"]');
    await expect(endInput).toBeVisible({ timeout: 10000 });
    const endValue = await endInput.inputValue();
    console.log(`  [i] End field value: ${endValue}`);
    if (!endValue) {
      throw new Error('End field is empty — stop timer should have set it');
    }
    console.log('  [+] End time is populated');

    const beginningInput = page.locator('input[name="reading-log/beginning"]');
    const beginningValue = await beginningInput.inputValue();
    if (!beginningValue) {
      throw new Error('Beginning field is empty on edit form');
    }
    console.log('  [+] Beginning time is populated');

    console.log('\n=== Test Passed ===\n');
  } catch (error) {
    console.error('\n=== Test Failed ===');
    console.error(error);
    await captureScreenshot(page, 'error-state');
    process.exit(1);
  } finally {
    await browser.close();
  }
}

main();
