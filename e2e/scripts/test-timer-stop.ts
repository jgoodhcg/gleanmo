// E2E test for stop timer (End Session) functionality
// Usage: npm run test:timer-stop
//
// This test verifies the full timer lifecycle:
// 1. Creates a book
// 2. Starts a timer via the timer page
// 3. Clicks "End Session" on the active timer
// 4. Verifies redirect to edit form with end time populated
// 5. Submits the edit form (redirects back to timer page)
// 6. Verifies the timer is no longer active and appears in recent logs

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

async function setHiddenOrVisibleSelectValue(select: Locator, value: string) {
  await select.evaluate((node: HTMLElement, nextValue: string) => {
    const selectEl = node as HTMLSelectElement;
    selectEl.value = nextValue;
    selectEl.dispatchEvent(new Event('input', { bubbles: true }));
    selectEl.dispatchEvent(new Event('change', { bubbles: true }));
  }, value);
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

    // 2. Navigate to timer page, click Start Timer
    console.log('\n2. Starting timer...');
    await page.goto(`${BASE_URL}/app/timer/reading-log`);
    await page.waitForLoadState('networkidle');

    const startLink = page.locator('a:has-text("Start Timer")').first();
    await expect(startLink).toBeVisible({ timeout: 10000 });
    await startLink.click();
    await page.waitForLoadState('networkidle');

    // Submit the new reading-log form (open timer — no end time)
    const newForm = page.locator('#reading-log-new-form');
    await expect(newForm).toBeVisible({ timeout: 10000 });
    await setHiddenOrVisibleSelectValue(newForm.locator('select[name="reading-log/time-zone"]'), 'UTC');
    await submitHtmxForm(newForm, '/app/crud/reading-log');
    await page.waitForLoadState('networkidle');
    await page.waitForTimeout(300);

    // Should be redirected back to timer page
    if (!page.url().includes('/app/timer/reading-log')) {
      await page.goto(`${BASE_URL}/app/timer/reading-log`);
      await page.waitForLoadState('networkidle');
    }
    await captureScreenshot(page, '01-active-timer');

    // 3. Verify active timer is present
    console.log('\n3. Verifying active timer...');
    await expect(page.locator('text=Active Timers').first()).toBeVisible({ timeout: 10000 });
    await expect(page.locator(`text=${bookTitle}`).first()).toBeVisible({ timeout: 10000 });
    console.log(`  [+] Active timer visible for "${bookTitle}"`);

    // 4. Click "End Session"
    console.log('\n4. Clicking End Session...');
    const endSessionLink = page.locator('a:has-text("End Session")').first();
    await expect(endSessionLink).toBeVisible({ timeout: 10000 });
    const stopHref = await endSessionLink.getAttribute('href');
    console.log(`  [i] End Session href: ${stopHref}`);
    await endSessionLink.click();
    await page.waitForLoadState('networkidle');
    await captureScreenshot(page, '02-edit-form-after-stop');

    // 5. Verify we landed on the edit form
    console.log('\n5. Verifying edit form...');
    const currentUrl = page.url();
    console.log(`  [i] Redirected to: ${currentUrl}`);
    if (!currentUrl.includes('/app/crud/form/reading-log/edit/')) {
      throw new Error(`Expected redirect to reading-log edit form, got: ${currentUrl}`);
    }
    console.log('  [+] Redirected to edit form');

    // 6. Verify the end time field is now populated
    const endInput = page.locator('input[name="reading-log/end"]');
    await expect(endInput).toBeVisible({ timeout: 10000 });
    const endValue = await endInput.inputValue();
    console.log(`  [i] End field value: ${endValue}`);
    if (!endValue) {
      throw new Error('End field is empty — stop timer should have set it');
    }
    console.log('  [+] End time is populated');

    // Verify beginning is also populated
    const beginningInput = page.locator('input[name="reading-log/beginning"]');
    const beginningValue = await beginningInput.inputValue();
    console.log(`  [i] Beginning field value: ${beginningValue}`);
    if (!beginningValue) {
      throw new Error('Beginning field is empty on edit form');
    }
    console.log('  [+] Beginning time is populated');

    // 7. Submit the edit form (should redirect back to timer page via ?redirect param)
    console.log('\n6. Submitting edit form...');
    const editForm = page.locator('#reading-log-edit-form');
    await expect(editForm).toBeVisible({ timeout: 10000 });
    await submitHtmxForm(editForm, '/app/crud/reading-log');
    await page.waitForLoadState('networkidle');
    await page.waitForTimeout(300);
    await captureScreenshot(page, '03-after-edit-submit');

    // 8. Navigate to timer page and verify no active timers
    console.log('\n7. Verifying timer is stopped...');
    await authenticateForDev(page, email);
    await page.goto(`${BASE_URL}/app/timer/reading-log`);
    await page.waitForLoadState('networkidle');
    await captureScreenshot(page, '04-timer-page-after-stop');

    // The active timers section should be empty (no active timer cards)
    const activeTimerCards = page.locator('a:has-text("End Session")');
    const activeCount = await activeTimerCards.count();
    if (activeCount > 0) {
      throw new Error(`Expected 0 active timers after stop, found ${activeCount}`);
    }
    console.log('  [+] No active timers (timer was stopped successfully)');

    // 9. Verify the completed session appears in recent logs
    console.log('\n8. Verifying completed session in recent logs...');
    await expect(page.locator(`text=${bookTitle}`).first()).toBeVisible({ timeout: 10000 });
    console.log(`  [+] Completed session for "${bookTitle}" visible in recent logs`);

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
