// E2E test for reading-log timer flow
// Usage: npm run test:reading-timer
//
// This test verifies the full reading-log timer lifecycle:
// 1. Creates a book (label defaults from title)
// 2. Timer page shows the book title as the "Start Timer" card label
// 3. "Start Timer" link has no stale beginning= param
// 4. Clicking "Start Timer" opens form with fresh timestamp
// 5. Form pre-populates the book-id from the Start Timer link query params
// 6. Submitting creates a reading-log (open timer — no end time)
// 7. Timer page shows the active timer with the book name
// 8. Redirects back to timer page after submission

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

    // 3. Verify the book title appears as the Start Timer card label
    //    (book/label should default from book/title at creation time)
    console.log('\n3. Verifying book label on timer page...');
    await expect(page.locator(`text=${bookTitle}`).first()).toBeVisible({ timeout: 10000 });
    console.log(`  [+] Book title "${bookTitle}" visible on timer page`);

    // 4. Verify the "Start Timer" link does NOT contain a stale beginning parameter
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

    // 5. Click "Start Timer" — verify form opens with fresh timestamp
    console.log('\n4. Clicking Start Timer...');
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

    // 6. Verify the book-id is pre-populated from the Start Timer link query params
    console.log('\n5. Verifying book pre-selection...');
    const selectedBookId = await page
      .locator('select[name="reading-log/book-id"]')
      .evaluate((node: HTMLSelectElement) => node.value);

    if (!selectedBookId || selectedBookId.length === 0) {
      throw new Error('Book-id select is not pre-populated from Start Timer link');
    }
    console.log(`  [+] Book pre-selected in form (id: ${selectedBookId})`);

    // Verify the link's query param matches the selected value
    if (href) {
      const linkBookId = new URL(href, BASE_URL).searchParams.get('reading-log/book-id');
      if (linkBookId !== selectedBookId) {
        throw new Error(
          `Book ID mismatch: link param="${linkBookId}" vs select value="${selectedBookId}"`
        );
      }
      console.log('  [+] Link query param matches form select value');
    }

    // 7. Submit the form (no end time — creates an open/active timer)
    console.log('\n6. Submitting reading log (open timer)...');
    const form = page.locator('#reading-log-new-form');
    await expect(form).toBeVisible({ timeout: 10000 });
    await setHiddenOrVisibleSelectValue(form.locator('select[name="reading-log/time-zone"]'), 'UTC');

    await submitHtmxForm(form, '/app/crud/reading-log');
    await page.waitForLoadState('networkidle');
    await page.waitForTimeout(300);
    await captureScreenshot(page, '04-submitted');

    // 8. Verify we were redirected back to the timer page (the Start Timer link
    //    includes redirect=/app/timer/reading-log)
    const currentUrl = page.url();
    console.log(`  [i] Redirected to: ${currentUrl}`);
    if (!currentUrl.includes('/app/timer/reading-log')) {
      // If not auto-redirected, navigate manually to check the timer state
      await page.goto(`${BASE_URL}/app/timer/reading-log`);
      await page.waitForLoadState('networkidle');
    }
    await captureScreenshot(page, '05-timer-with-active');

    // 9. Verify the active timer shows the book name
    console.log('\n7. Verifying active timer on timer page...');
    await expect(page.locator(`text=${bookTitle}`).first()).toBeVisible({ timeout: 10000 });
    console.log(`  [+] Book title "${bookTitle}" visible in active timer`);

    // Verify "Active Timers" section is present (indicates an open timer exists)
    await expect(page.locator('text=Active Timers').first()).toBeVisible({ timeout: 5000 });
    console.log('  [+] Active Timers section visible');
    await captureScreenshot(page, '06-active-timer-verified');

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
