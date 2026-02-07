// E2E test for Focus page Today filter
// Usage: npm run test:today-filter
//
// This test:
// 1. Creates two tasks with a shared prefix
// 2. Toggles one into Today from the Focus page
// 3. Verifies "Only today" filter shows the focused task
// 4. Verifies "Exclude today" filter shows the other task
// 5. Takes screenshots for visual validation

import { chromium, Page, expect } from '@playwright/test';
import { authenticateForDev } from './auth.js';

const BASE_URL = process.env.BASE_URL || 'http://localhost:8080';

async function captureScreenshot(page: Page, name: string) {
  const filepath = `screenshots/today-filter-${name}.png`;
  await page.screenshot({ path: filepath });
  console.log(`  [screenshot] ${filepath}`);
}

async function createTestTask(page: Page, label: string, email: string) {
  await authenticateForDev(page, email);

  await page.goto(`${BASE_URL}/app/crud/form/task/new`);
  await page.waitForLoadState('networkidle');

  const taskForm = page.locator('#task-new-form');
  await taskForm.waitFor({ state: 'visible', timeout: 10000 });

  const labelInput = taskForm.locator('input.form-input').first();
  await labelInput.fill(label);

  const stateSelect = taskForm.locator('select[name="task/state"]');
  await stateSelect.selectOption('now');

  await taskForm.evaluate((form: HTMLFormElement) => {
    form.setAttribute('action', form.getAttribute('hx-post') || '/app/crud/task');
    form.setAttribute('method', 'POST');
  });

  await taskForm.evaluate((form: HTMLFormElement) => form.submit());
  await page.waitForLoadState('networkidle');
  await page.waitForTimeout(500);
  console.log(`  [+] Created task "${label}"`);
}

async function taskRowForLabel(page: Page, label: string) {
  return page.locator('.bg-dark-surface', { has: page.locator(`a:has-text("${label}")`) });
}

async function main() {
  console.log('\n=== Today Filter Test ===\n');

  const browser = await chromium.launch({ headless: true });
  const page = await browser.newPage({ viewport: { width: 1280, height: 720 } });

  try {
    const testEmail = 'e2e-today-filter@localhost';
    const prefix = `Today Filter ${Date.now()}`;
    const labelToday = `${prefix} A`;
    const labelBacklog = `${prefix} B`;

    console.log('1. Creating test tasks...');
    await createTestTask(page, labelToday, testEmail);
    await createTestTask(page, labelBacklog, testEmail);

    console.log('\n2. Toggling one task into Today...');
    const searchParam = encodeURIComponent(prefix);
    await authenticateForDev(page, testEmail);
    await page.goto(`${BASE_URL}/app/task/focus?state=now&search=${searchParam}`);
    await page.waitForLoadState('networkidle');
    await captureScreenshot(page, '01-before-toggle');

    const todayRow = await taskRowForLabel(page, labelToday);
    await expect(todayRow).toBeVisible({ timeout: 10000 });
    await todayRow.locator('button:has-text("📌 Today")').first().click();
    await page.waitForTimeout(500);
    await captureScreenshot(page, '02-after-toggle');

    console.log('\n3. Verifying "Only today" filter...');
    await page.goto(`${BASE_URL}/app/task/focus?state=now&search=${searchParam}&today-filter=only`);
    await page.waitForLoadState('networkidle');
    await expect(page.locator(`a:has-text("${labelToday}")`)).toBeVisible({ timeout: 10000 });
    await expect(page.locator(`a:has-text("${labelBacklog}")`)).toHaveCount(0);
    await captureScreenshot(page, '03-only-today');

    console.log('\n4. Verifying "Exclude today" filter...');
    await page.goto(`${BASE_URL}/app/task/focus?state=now&search=${searchParam}&today-filter=exclude`);
    await page.waitForLoadState('networkidle');
    await expect(page.locator(`a:has-text("${labelToday}")`)).toHaveCount(0);
    await expect(page.locator(`a:has-text("${labelBacklog}")`)).toBeVisible({ timeout: 10000 });
    await captureScreenshot(page, '04-exclude-today');

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
