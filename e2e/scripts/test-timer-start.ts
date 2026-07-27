// E2E test for one-tap timer start (no CRUD form bounce)
// Verifies that "Start Timer" on the per-entity page POSTs directly:
// no form page loads, the timer appears under Active Timers, and the
// created log has a fresh beginning timestamp.
//
// Usage: npm run test:timer-start

import { chromium, Page, Locator, expect } from '@playwright/test';
import { authenticateForDev } from './auth.js';

const BASE_URL = process.env.BASE_URL || 'http://localhost:8080';

async function captureScreenshot(page: Page, name: string) {
  const phase = process.env.SCREENSHOT_PHASE;
  const prefix = phase ? `${phase}-` : '';
  const filepath = `screenshots/${prefix}timer-start-${name}.png`;
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

async function createProject(page: Page, label: string) {
  await page.goto(`${BASE_URL}/app/crud/form/project/new`);
  await page.waitForLoadState('networkidle');

  const form = page.locator('#project-new-form');
  await expect(form).toBeVisible({ timeout: 10000 });
  await form.locator('input[name="project/label"]').fill(label);

  await submitHtmxForm(form, '/app/crud/project');
  await page.waitForLoadState('networkidle');
  await page.waitForTimeout(300);
  console.log(`  [+] Created project "${label}"`);
}

async function main() {
  console.log('\n=== One-Tap Timer Start Test ===\n');

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

    // 3. Start the timer — must be a direct POST, not a link to a form
    console.log('\n3. Clicking Start Timer...');
    const beforeClick = new Date();
    const startButton = page.locator('button:has-text("Start Timer")').first();
    await expect(startButton).toBeVisible({ timeout: 10000 });
    await startButton.click();
    await page.waitForLoadState('networkidle');
    await captureScreenshot(page, '02-after-start');

    // 4. Verify no CRUD form page was involved and we're back on the timer page
    const currentUrl = page.url();
    console.log(`  [i] URL after start: ${currentUrl}`);
    if (currentUrl.includes('/app/crud/form/')) {
      throw new Error(`Start bounced to a CRUD form: ${currentUrl}`);
    }
    if (!currentUrl.includes('/app/timer/project-log')) {
      throw new Error(`Expected to return to /app/timer/project-log, got: ${currentUrl}`);
    }
    console.log('  [✓] No form page — returned straight to the timer page');

    // 5. Verify the active timer card is present with elapsed time
    await expect(page.locator('text=Active Timer').first()).toBeVisible({ timeout: 10000 });
    await expect(page.locator(`text=${projectLabel}`).first()).toBeVisible({ timeout: 10000 });
    await expect(page.locator('text=Running for').first()).toBeVisible({ timeout: 10000 });
    console.log('  [✓] Active timer card visible with elapsed time');

    // 6. Open the active card's edit link and verify the beginning is fresh
    console.log('\n4. Verifying fresh beginning timestamp via edit link...');
    await page.locator('a[href*="/app/crud/form/project-log/edit/"]').first().click();
    await page.waitForLoadState('networkidle');
    await captureScreenshot(page, '03-edit-form');

    const beginningInput = page.locator('input[name="project-log/beginning"]');
    await expect(beginningInput).toBeVisible({ timeout: 10000 });
    const beginningValue = await beginningInput.inputValue();
    console.log(`  [i] Beginning field value: ${beginningValue}`);

    if (!beginningValue) {
      throw new Error('Beginning field is empty — direct start should have set it');
    }

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
