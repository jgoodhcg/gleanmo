// E2E test for Today page drag-and-drop reordering
// Usage: npm run test:today-reorder
//
// This test:
// 1. Creates test tasks via the Focus page
// 2. Adds them to Today
// 3. Verifies they appear in the Today list
// 4. Performs drag-and-drop reorder
// 5. Verifies the order changed
// 6. Takes screenshots for visual validation

import { chromium, Page, expect } from '@playwright/test';
import { authenticateForDev } from './auth.js';

const BASE_URL = process.env.BASE_URL || 'http://localhost:8080';

async function captureScreenshot(page: Page, name: string) {
  const filepath = `screenshots/today-reorder-${name}.png`;
  await page.screenshot({ path: filepath });
  console.log(`  [screenshot] ${filepath}`);
}

async function getTaskLabelsInOrder(page: Page): Promise<string[]> {
  // Get all task labels in the sortable list in DOM order
  const labels = await page.locator('.sortable-item a[href^="/app/crud/form/task/edit/"]').allTextContents();
  return labels;
}

async function addTaskToToday(page: Page, taskLabel: string) {
  // Find the task row containing this label and click its "📌 Today" button
  const taskRow = page.locator('.bg-dark-surface', { has: page.locator(`a:has-text("${taskLabel}")`) });
  const todayButton = taskRow.locator('button:has-text("Today")').first();

  if (await todayButton.isVisible()) {
    await todayButton.click();
    await page.waitForTimeout(500); // Wait for HTMX update
    console.log(`  [+] Added "${taskLabel}" to today`);
  } else {
    console.log(`  [!] "${taskLabel}" may already be in today or button not found`);
  }
}

async function createTestTask(page: Page, label: string, email: string) {
  // Re-authenticate before each task to ensure fresh session
  await authenticateForDev(page, email);

  await page.goto(`${BASE_URL}/app/crud/form/task/new`);
  await page.waitForLoadState('networkidle');

  // Wait for the task form to be fully rendered (form ID is task-new-form)
  const taskForm = page.locator('#task-new-form');
  await taskForm.waitFor({ state: 'visible', timeout: 10000 });

  // Fill in the task form - find the label input within this form
  const labelInput = taskForm.locator('input.form-input').first();
  await labelInput.fill(label);

  // Select state
  const stateSelect = taskForm.locator('select[name="task/state"]');
  await stateSelect.selectOption('now');

  // Set form action attribute for native submission (HTMX forms don't have action by default)
  await taskForm.evaluate((form: HTMLFormElement) => {
    form.setAttribute('action', form.getAttribute('hx-post') || '/app/crud/task');
    form.setAttribute('method', 'POST');
  });

  // Submit using native form submission
  await taskForm.evaluate((form: HTMLFormElement) => form.submit());

  // Wait for navigation after form submission
  await page.waitForLoadState('networkidle');
  await page.waitForTimeout(500);
  console.log(`  [+] Created task "${label}"`);
}

async function main() {
  console.log('\n=== Today Page Reorder Test ===\n');

  const browser = await chromium.launch({ headless: true });
  const page = await browser.newPage({ viewport: { width: 1280, height: 720 } });

  try {
    // 1. Authenticate
    console.log('1. Authenticating...');
    await authenticateForDev(page, 'e2e-reorder-test@localhost');

    // 2. Create test tasks
    console.log('\n2. Creating test tasks...');
    const testEmail = 'e2e-reorder-test@localhost';
    const testTasks = [
      `Test Task Alpha ${Date.now()}`,
      `Test Task Beta ${Date.now()}`,
      `Test Task Gamma ${Date.now()}`
    ];

    for (const label of testTasks) {
      await createTestTask(page, label, testEmail);
    }

    // Wait for database sync
    await page.waitForTimeout(1000);

    // 3. Go to Focus page and add tasks to today
    console.log('\n3. Adding tasks to Today...');
    await authenticateForDev(page, testEmail);
    await page.goto(`${BASE_URL}/app/task/focus?state=now`);
    await page.waitForLoadState('networkidle');

    for (const label of testTasks) {
      await addTaskToToday(page, label);
    }

    // 4. Go to Today page
    console.log('\n4. Navigating to Today page...');
    await authenticateForDev(page, testEmail);
    await page.goto(`${BASE_URL}/app/task/today`);
    await page.waitForLoadState('networkidle');
    await captureScreenshot(page, '01-before-reorder');

    // 5. Verify tasks are present
    console.log('\n5. Verifying tasks are present...');
    const initialLabels = await getTaskLabelsInOrder(page);
    console.log('  Initial order:', initialLabels);

    // Assert we have at least 2 tasks to reorder
    expect(initialLabels.length).toBeGreaterThanOrEqual(2);
    console.log(`  ✓ Found ${initialLabels.length} tasks`);

    // 6. Perform drag-and-drop (move first item to last position)
    console.log('\n6. Performing drag-and-drop...');
    const sortableItems = page.locator('.sortable-item');
    const firstItem = sortableItems.first();
    const lastItem = sortableItems.last();

    // Get bounding boxes
    const firstBox = await firstItem.boundingBox();
    const lastBox = await lastItem.boundingBox();

    if (firstBox && lastBox) {
      // Drag first item below the last item
      await page.mouse.move(firstBox.x + firstBox.width / 2, firstBox.y + firstBox.height / 2);
      await page.mouse.down();
      await page.mouse.move(lastBox.x + lastBox.width / 2, lastBox.y + lastBox.height + 10, { steps: 10 });
      await page.mouse.up();

      // Wait for HTMX update
      await page.waitForTimeout(1000);
      console.log('  ✓ Drag-and-drop performed');
    } else {
      throw new Error('Could not get bounding boxes for drag-and-drop');
    }

    await captureScreenshot(page, '02-after-reorder');

    // 7. Verify order changed
    console.log('\n7. Verifying order changed...');
    const finalLabels = await getTaskLabelsInOrder(page);
    console.log('  Final order:', finalLabels);

    // The first item should now be at a different position
    const firstTaskMoved = initialLabels[0] !== finalLabels[0];
    expect(firstTaskMoved).toBe(true);
    console.log('  ✓ Order changed successfully');

    // 8. Refresh and verify persistence
    console.log('\n8. Verifying persistence after refresh...');
    await page.reload();
    await page.waitForLoadState('networkidle');

    const persistedLabels = await getTaskLabelsInOrder(page);
    console.log('  Persisted order:', persistedLabels);

    // Order should match what we had after reorder
    expect(persistedLabels).toEqual(finalLabels);
    console.log('  ✓ Order persisted correctly');

    await captureScreenshot(page, '03-after-refresh');

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
