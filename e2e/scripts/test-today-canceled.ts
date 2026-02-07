// E2E test for canceled task behavior across Focus and Today pages
// Usage: npm run test:today-canceled
//
// This test:
// 1. Creates a task in :now state
// 2. Focuses it for Today from Focus page
// 3. Verifies it appears on Today page
// 4. Cancels it from Focus page
// 5. Verifies it is excluded from Today and from "Not done"
// 6. Verifies it still appears in "Any state"

import { chromium, Page, expect } from '@playwright/test';
import { authenticateForDev } from './auth.js';

const BASE_URL = process.env.BASE_URL || 'http://localhost:8080';

async function captureScreenshot(page: Page, name: string) {
  const filepath = `screenshots/today-canceled-${name}.png`;
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
  console.log('\n=== Today Canceled State Test ===\n');

  const browser = await chromium.launch({ headless: true });
  const page = await browser.newPage({ viewport: { width: 1280, height: 720 } });

  try {
    const testEmail = 'e2e-today-canceled@localhost';
    const taskLabel = `Canceled Task ${Date.now()}`;
    const searchParam = encodeURIComponent(taskLabel);

    console.log('1. Creating test task...');
    await createTestTask(page, taskLabel, testEmail);

    console.log('\n2. Focusing task for Today...');
    await authenticateForDev(page, testEmail);
    await page.goto(`${BASE_URL}/app/task/focus?state=now&search=${searchParam}`);
    await page.waitForLoadState('networkidle');

    const taskRow = await taskRowForLabel(page, taskLabel);
    await expect(taskRow).toBeVisible({ timeout: 10000 });
    await taskRow.locator('button:has-text("📌 Today")').first().click();
    await page.waitForTimeout(500);
    await captureScreenshot(page, '01-focused');

    console.log('\n3. Verifying task appears on Today...');
    await page.goto(`${BASE_URL}/app/task/today`);
    await page.waitForLoadState('networkidle');
    await expect(page.locator(`a:has-text("${taskLabel}")`)).toBeVisible({ timeout: 10000 });
    await captureScreenshot(page, '02-on-today-before-cancel');

    console.log('\n4. Canceling task from Focus...');
    await page.goto(`${BASE_URL}/app/task/focus?state=any&search=${searchParam}`);
    await page.waitForLoadState('networkidle');
    const rowInAny = await taskRowForLabel(page, taskLabel);
    await expect(rowInAny).toBeVisible({ timeout: 10000 });
    await rowInAny.locator('button:has-text("Cancel")').first().click();
    await page.waitForTimeout(500);
    await expect(rowInAny.locator('span:has-text("Canceled")')).toBeVisible({ timeout: 10000 });
    await captureScreenshot(page, '03-after-cancel');

    console.log('\n5. Verifying canceled task is excluded from Today...');
    await page.goto(`${BASE_URL}/app/task/today`);
    await page.waitForLoadState('networkidle');
    await expect(page.locator(`a:has-text("${taskLabel}")`)).toHaveCount(0);
    await captureScreenshot(page, '04-today-after-cancel');

    console.log('\n6. Verifying canceled task is excluded from "Not done"...');
    await page.goto(`${BASE_URL}/app/task/focus?state=not-done&search=${searchParam}`);
    await page.waitForLoadState('networkidle');
    await expect(page.locator(`a:has-text("${taskLabel}")`)).toHaveCount(0);
    await captureScreenshot(page, '05-not-done');

    console.log('\n7. Verifying canceled task is present in "Any state"...');
    await page.goto(`${BASE_URL}/app/task/focus?state=any&search=${searchParam}`);
    await page.waitForLoadState('networkidle');
    await expect(page.locator(`a:has-text("${taskLabel}")`)).toBeVisible({ timeout: 10000 });
    await captureScreenshot(page, '06-any-state');

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
