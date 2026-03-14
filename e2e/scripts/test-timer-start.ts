// E2E test for timer start timestamp freshness
// Verifies that the "Start Timer" link does NOT embed a stale timestamp,
// so the new-form's default "now" value is used instead.
//
// Usage: npm run test:timer-start

import { chromium, Page, expect } from '@playwright/test';
import { authenticateForDev } from './auth.js';

const BASE_URL = process.env.BASE_URL || 'http://localhost:8080';

async function captureScreenshot(page: Page, name: string) {
  const phase = process.env.SCREENSHOT_PHASE;
  const prefix = phase ? `${phase}-` : '';
  const filepath = `screenshots/${prefix}timer-start-${name}.png`;
  await page.screenshot({ path: filepath });
  console.log(`  [screenshot] ${filepath}`);
}

async function createProject(page: Page, label: string) {
  await page.goto(`${BASE_URL}/app/crud/form/project/new`);
  await page.waitForLoadState('networkidle');

  const form = page.locator('#project-new-form');
  await expect(form).toBeVisible({ timeout: 10000 });
  await form.locator('input[name="project/label"]').fill(label);

  // Submit via standard form action (HTMX workaround)
  await form.evaluate((node: HTMLElement, action: string) => {
    const formEl = node as HTMLFormElement;
    formEl.setAttribute('action', formEl.getAttribute('hx-post') || action);
    formEl.setAttribute('method', 'POST');
  }, '/app/crud/project');
  await form.evaluate((node: HTMLElement) => (node as HTMLFormElement).submit());
  await page.waitForLoadState('networkidle');
  await page.waitForTimeout(300);
  console.log(`  [+] Created project "${label}"`);
}

async function main() {
  console.log('\n=== Timer Start Timestamp Freshness Test ===\n');

  const browser = await chromium.launch({ headless: true });
  const page = await browser.newPage({ viewport: { width: 1280, height: 720 } });

  try {
    const email = `e2e-timer-start-${Date.now()}@localhost`;
    const projectLabel = `Timer Start Test ${Date.now()}`;

    // 1. Create a project so the timer page has a "Start Timer" button
    console.log('1. Creating project...');
    await authenticateForDev(page, email);
    await createProject(page, projectLabel);

    // 2. Navigate to the project-log timer page
    console.log('\n2. Loading timer page...');
    await page.goto(`${BASE_URL}/app/timer/project-log`);
    await page.waitForLoadState('networkidle');
    await captureScreenshot(page, '01-timer-page');

    // 3. Verify the "Start Timer" link does NOT contain a beginning parameter
    const startLink = page.locator('a:has-text("Start Timer")').first();
    await expect(startLink).toBeVisible({ timeout: 10000 });
    const href = await startLink.getAttribute('href');
    console.log(`  [i] Start Timer href: ${href}`);

    if (href && href.includes('beginning=')) {
      throw new Error(
        `Start Timer link still contains a pre-computed beginning timestamp!\n` +
        `  href: ${href}\n` +
        `  This will cause stale timestamps when the PWA is idle.`
      );
    }
    console.log('  [✓] No pre-computed beginning timestamp in link');

    // 4. Click "Start Timer" and verify the form gets a fresh timestamp
    const beforeClick = new Date();
    await startLink.click();
    await page.waitForLoadState('networkidle');
    await captureScreenshot(page, '02-new-form');

    // 5. Read the beginning field value from the form
    const beginningInput = page.locator('input[name="project-log/beginning"]');
    await expect(beginningInput).toBeVisible({ timeout: 10000 });
    const beginningValue = await beginningInput.inputValue();
    console.log(`  [i] Beginning field value: ${beginningValue}`);

    if (!beginningValue) {
      throw new Error('Beginning field is empty — expected a default "now" value');
    }

    // 6. Verify the timestamp is from today (the form renders in the user's
    // configured timezone which may differ from the test machine, so we just
    // check the date portion matches today in UTC or local)
    const formDate = beginningValue.slice(0, 10); // YYYY-MM-DD
    const todayUTC = beforeClick.toISOString().slice(0, 10);
    const pad = (n: number) => String(n).padStart(2, '0');
    const todayLocal = `${beforeClick.getFullYear()}-${pad(beforeClick.getMonth() + 1)}-${pad(beforeClick.getDate())}`;

    console.log(`  [i] Form date: ${formDate}, today UTC: ${todayUTC}, today local: ${todayLocal}`);

    if (formDate !== todayUTC && formDate !== todayLocal) {
      throw new Error(
        `Beginning date is not today.\n` +
        `  Form date:    ${formDate}\n` +
        `  Today (UTC):  ${todayUTC}\n` +
        `  Today (local): ${todayLocal}`
      );
    }
    console.log('  [✓] Beginning timestamp is from today (fresh)');

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
