// E2E test for Today toggle on Focus page
// Usage: npm run test:today-toggle
//
// This test:
// 1. Creates a test task
// 2. Navigates to Focus page filtered to that task
// 3. Toggles "📌 Today" on and confirms it changes to "✓ Today"
// 4. Toggles "✓ Today" off and confirms it returns to "📌 Today"
// 5. Takes screenshots for visual validation

import { chromium, Page, expect } from '@playwright/test';
import { authenticateForDev } from './auth.js';

const BASE_URL = process.env.BASE_URL || 'http://localhost:8080';

async function captureScreenshot(page: Page, name: string) {
  const filepath = `screenshots/today-toggle-${name}.png`;
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

async function toggleTodayButton(page: Page, label: string, buttonText: string) {
  const taskRow = await taskRowForLabel(page, label);
  const button = taskRow.locator(`button:has-text("${buttonText}")`).first();
  await expect(button).toBeVisible({ timeout: 10000 });
  await button.click();
  await page.waitForTimeout(500);
}

async function main() {
  console.log('\n=== Today Toggle Test ===\n');

  const browser = await chromium.launch({ headless: true });
  const page = await browser.newPage({ viewport: { width: 1280, height: 720 } });

  try {
    const testEmail = 'e2e-toggle-test@localhost';
    const taskLabel = `Toggle Test Task ${Date.now()}`;

    console.log('1. Creating test task...');
    await createTestTask(page, taskLabel, testEmail);

    console.log('\n2. Navigating to Focus page...');
    const searchParam = encodeURIComponent(taskLabel);
    await authenticateForDev(page, testEmail);
    await page.goto(`${BASE_URL}/app/task/focus?state=now&search=${searchParam}`);
    await page.waitForLoadState('networkidle');

    const taskRow = await taskRowForLabel(page, taskLabel);
    await expect(taskRow).toBeVisible({ timeout: 10000 });
    await captureScreenshot(page, '01-before-toggle');

    console.log('\n3. Toggling on (📌 Today -> ✓ Today)...');
    await toggleTodayButton(page, taskLabel, '📌 Today');
    await expect(taskRow.locator('button:has-text("✓ Today")')).toBeVisible({ timeout: 10000 });
    await captureScreenshot(page, '02-after-toggle-on');

    console.log('\n4. Toggling off (✓ Today -> 📌 Today)...');
    await toggleTodayButton(page, taskLabel, '✓ Today');
    await expect(taskRow.locator('button:has-text("📌 Today")')).toBeVisible({ timeout: 10000 });
    await captureScreenshot(page, '03-after-toggle-off');

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
