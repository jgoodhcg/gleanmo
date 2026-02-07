// E2E test for Today quick add
// Usage: npm run test:today-quick-add
//
// This test:
// 1. Navigates to Today page
// 2. Adds a task via the quick add form
// 3. Verifies it appears in the list
// 4. Takes screenshots for visual validation

import { chromium, Page, expect } from '@playwright/test';
import { authenticateForDev } from './auth.js';

const BASE_URL = process.env.BASE_URL || 'http://localhost:8080';

async function captureScreenshot(page: Page, name: string) {
  const filepath = `screenshots/today-quick-add-${name}.png`;
  await page.screenshot({ path: filepath });
  console.log(`  [screenshot] ${filepath}`);
}

async function main() {
  console.log('\n=== Today Quick Add Test ===\n');

  const browser = await chromium.launch({ headless: true });
  const page = await browser.newPage({ viewport: { width: 1280, height: 720 } });

  try {
    const testEmail = 'e2e-quick-add@localhost';
    const label = `Quick Add Task ${Date.now()}`;

    console.log('1. Navigating to Today page...');
    await authenticateForDev(page, testEmail);
    await page.goto(`${BASE_URL}/app/task/today`);
    await page.waitForLoadState('networkidle');
    await captureScreenshot(page, '01-before');

    console.log('\n2. Submitting quick add...');
    const input = page.locator('#today-quick-add-label');
    await expect(input).toBeVisible({ timeout: 10000 });
    await input.fill(label);
    await input.press('Enter');

    const taskLink = page.locator(`a:has-text("${label}")`);
    await expect(taskLink).toBeVisible({ timeout: 10000 });
    await captureScreenshot(page, '02-after');

    console.log('\n3. Verifying input cleared...');
    await expect(page.locator('#today-quick-add-label')).toHaveValue('');

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
