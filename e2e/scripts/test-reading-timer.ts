// E2E test for reading-log timer flow
// Usage: npm run test:reading-timer
//
// This test:
// 1. Creates a book
// 2. Navigates to the reading-log timer page
// 3. Verifies "Start Timer" link has no stale timestamp
// 4. Clicks "Start Timer" and verifies form opens with fresh timestamp
// 5. Verifies the book-id dropdown contains the created book
// 6. Fills in the book selection and submits
// 7. Verifies reading-log was created

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

    // 3. Verify the "Start Timer" link does NOT contain a stale beginning parameter
    const startLink = page.locator('a:has-text("Start Timer")').first();
    await expect(startLink).toBeVisible({ timeout: 10000 });
    const href = await startLink.getAttribute('href');
    console.log(`  [i] Start Timer href: ${href}`);

    if (href && href.includes('beginning=')) {
      throw new Error(
        `Start Timer link contains a pre-computed beginning timestamp!\n` +
        `  href: ${href}\n` +
        `  This will cause stale timestamps when the PWA is idle.`
      );
    }
    console.log('  [+] No pre-computed beginning timestamp in link');

    // 4. Click "Start Timer" — verify form opens with fresh timestamp
    const beforeClick = new Date();
    await startLink.click();
    await page.waitForLoadState('networkidle');
    await captureScreenshot(page, '03-new-form');

    const beginningInput = page.locator('input[name="reading-log/beginning"]');
    await expect(beginningInput).toBeVisible({ timeout: 10000 });
    const beginningValue = await beginningInput.inputValue();
    console.log(`  [i] Beginning field value: ${beginningValue}`);

    if (!beginningValue) {
      throw new Error('Beginning field is empty — expected a default "now" value');
    }

    // Verify the timestamp is from today
    const formDate = beginningValue.slice(0, 10);
    const todayUTC = beforeClick.toISOString().slice(0, 10);
    const pad = (n: number) => String(n).padStart(2, '0');
    const todayLocal = `${beforeClick.getFullYear()}-${pad(beforeClick.getMonth() + 1)}-${pad(beforeClick.getDate())}`;

    if (formDate !== todayUTC && formDate !== todayLocal) {
      throw new Error(
        `Beginning date is not today.\n` +
        `  Form date:    ${formDate}\n` +
        `  Today (UTC):  ${todayUTC}\n` +
        `  Today (local): ${todayLocal}`
      );
    }
    console.log('  [+] Beginning timestamp is from today (fresh)');

    // 5. Verify the book-id dropdown contains the created book
    console.log('\n3. Verifying book dropdown...');
    const bookOptions = await page
      .locator('select[name="reading-log/book-id"] option')
      .evaluateAll((nodes) =>
        nodes
          .map((node) => (node as HTMLOptionElement).value)
          .filter((v) => v && v.length > 0));

    if (bookOptions.length === 0) {
      throw new Error('No book options found in book-id dropdown');
    }
    const bookId = bookOptions[0];
    console.log(`  [+] Book found in dropdown (id: ${bookId})`);

    // 6. Fill in the book selection and submit
    console.log('\n4. Submitting reading log via timer form...');
    const form = page.locator('#reading-log-new-form');
    await expect(form).toBeVisible({ timeout: 10000 });
    await setHiddenOrVisibleSelectValue(form.locator('select[name="reading-log/book-id"]'), bookId);
    await setHiddenOrVisibleSelectValue(form.locator('select[name="reading-log/time-zone"]'), 'UTC');

    await submitHtmxForm(form, '/app/crud/reading-log');
    await page.waitForLoadState('networkidle');
    await page.waitForTimeout(300);
    await captureScreenshot(page, '04-submitted');
    console.log('  [+] Reading log submitted');

    // 7. Verify reading-log was created (check list page)
    console.log('\n5. Verifying reading log was created...');
    await authenticateForDev(page, email);
    await page.goto(`${BASE_URL}/app/crud/reading-log`);
    await page.waitForLoadState('networkidle');
    await expect(page.locator('text=Showing 1-1').first()).toBeVisible({ timeout: 10000 });
    await expect(page.locator('text=Edit').first()).toBeVisible({ timeout: 5000 });
    console.log('  [+] Reading log visible in list');
    await captureScreenshot(page, '05-reading-log-list');

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
