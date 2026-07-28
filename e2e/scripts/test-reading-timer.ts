// E2E test for reading-log timer flow
// Usage: npm run test:reading-timer
//
// This test verifies the reading-log one-tap timer lifecycle:
// 1. Creates a book (label defaults from title)
// 2. Timer page shows the book title on the Start Timer card
// 3. Clicking "Start Timer" POSTs directly — no CRUD new-form page
// 4. Timer page shows the active timer with the book name
// 5. The created log is linked to the book (verified via the edit link)

import { chromium, Page, Locator, expect } from '@playwright/test';
import { authenticateForDev } from './auth.js';

const BASE_URL = process.env.BASE_URL || 'http://localhost:8080';

async function captureScreenshot(page: Page, name: string) {
  const phase = process.env.SCREENSHOT_PHASE;
  const prefix = phase ? `${phase}-` : '';
  const filepath = `screenshots/${prefix}reading-timer-${name}.png`;
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
  await form.locator('[name="book/title"]').fill(title);

  await submitHtmxForm(form, '/app/crud/book');
  await page.waitForLoadState('networkidle');
  await page.waitForTimeout(300);
  console.log(`  [+] Created book "${title}"`);
}

async function main() {
  console.log('\n=== Reading Timer Test ===\n');

  const browser = await chromium.launch({ headless: true });
  const page = await browser.newPage({ viewport: { width: 1280, height: 720 } });

  try {
    const email = `e2e-reading-timer-${Date.now()}@localhost`;
    const bookTitle = `Timer Book ${Date.now()}`;

    // 1. Create a book so the timer page has data
    console.log('1. Creating book...');
    await authenticateForDev(page, email);
    await createBook(page, bookTitle);
    await captureScreenshot(page, '01-book-created');

    // 2. Navigate to the reading-log timer page
    console.log('\n2. Loading timer page...');
    await page.goto(`${BASE_URL}/app/timer/reading-log`);
    await page.waitForLoadState('networkidle');
    await captureScreenshot(page, '02-timer-page');

    // 3. Verify the book title appears on the Start Timer card
    //    (book/label should default from book/title at creation time)
    console.log('\n3. Verifying book label on timer page...');
    await expect(page.locator(`text=${bookTitle}`).first()).toBeVisible({ timeout: 10000 });
    console.log(`  [+] Book title "${bookTitle}" visible on timer page`);

    // 4. Click "Start Timer" — direct POST, no form page
    console.log('\n4. Clicking Start Timer...');
    const startButton = page.locator('button:has-text("Start Timer")').first();
    await expect(startButton).toBeVisible({ timeout: 10000 });
    await startButton.click();
    await page.waitForLoadState('networkidle');
    await captureScreenshot(page, '03-after-start');

    const currentUrl = page.url();
    console.log(`  [i] URL after start: ${currentUrl}`);
    if (currentUrl.includes('/app/crud/form/')) {
      throw new Error(`Start bounced to a CRUD form: ${currentUrl}`);
    }
    if (!currentUrl.includes('/app/timer/reading-log')) {
      throw new Error(`Expected to return to /app/timer/reading-log, got: ${currentUrl}`);
    }
    console.log('  [+] No form page — returned straight to the timer page');

    // 5. Verify the active timer shows the book name
    console.log('\n5. Verifying active timer on timer page...');
    await expect(page.locator('text=Active Timers').first()).toBeVisible({ timeout: 10000 });
    await expect(page.locator('text=Running for').first()).toBeVisible({ timeout: 10000 });
    await expect(page.locator(`text=${bookTitle}`).first()).toBeVisible({ timeout: 10000 });
    console.log(`  [+] Active timer running for "${bookTitle}"`);
    await captureScreenshot(page, '04-active-timer');

    // 6. Verify the log is linked to the book via the edit form
    console.log('\n6. Verifying book linkage on the log...');
    await page.locator('a[href*="/app/crud/form/reading-log/edit/"]').first().click();
    await page.waitForLoadState('networkidle');
    await captureScreenshot(page, '05-edit-form');

    const selectedBookId = await page
      .locator('select[name="reading-log/book-id"]')
      .evaluate((node: HTMLSelectElement) => node.value);
    if (!selectedBookId || selectedBookId.length === 0) {
      throw new Error('Book-id is not set on the log created by one-tap start');
    }
    console.log(`  [+] Log linked to book (id: ${selectedBookId})`);

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
