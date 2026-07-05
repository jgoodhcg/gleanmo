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
  const phase = process.env.SCREENSHOT_PHASE;
  const prefix = phase ? `${phase}-` : '';
  const filepath = `screenshots/${prefix}today-reorder-${name}.png`;
  await page.screenshot({ path: filepath });
  console.log(`  [screenshot] ${filepath}`);
}

async function getTaskLabelsInOrder(page: Page): Promise<string[]> {
  // Get all task labels in the sortable list in DOM order
  const labels = await page.locator('.sortable-item .font-medium.text-white').allTextContents();
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

  // Surface browser-side diagnostics in CI logs
  page.on('console', msg => console.log(`  [browser:${msg.type()}] ${msg.text()}`));
  page.on('pageerror', err => console.log(`  [pageerror] ${err.message}`));
  page.on('requestfailed', req => {
    console.log(`  [reqfail] ${req.url()} - ${req.failure()?.errorText}`);
  });
  page.on('response', resp => {
    const s = resp.status();
    if (s >= 400) console.log(`  [resp-${s}] ${resp.url()}`);
  });
  page.on('request', req => {
    if (req.url().includes('/reorder-today')) {
      console.log(`  [req] ${req.method()} ${req.url()}`);
    }
  });

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

    // Wait for SortableJS to initialize the container before dragging.
    // In CI the CDN script can load late and the drag would silently no-op.
    await page.waitForFunction(() => {
      const c = document.querySelector('.sortable-list');
      return !!c && c.getAttribute('data-sortable-initialized') === 'true';
    }, { timeout: 10000 }).catch(() => {
      /* fall through; diagnostic below will report */
    });
    const sortState = await page.evaluate(`
      ({
        sortDefined: typeof window.Sortable,
        container: !!document.querySelector('.sortable-list'),
        initialized: document.querySelector('.sortable-list') && document.querySelector('.sortable-list').getAttribute('data-sortable-initialized'),
      })
    `);
    console.log('  [sort-state]', JSON.stringify(sortState));
    if (sortState.initialized !== 'true') {
      throw new Error(`Sortable not initialized: ${JSON.stringify(sortState)}`);
    }

    const sortableItems = page.locator('.sortable-item');
    const firstHandle = sortableItems.first().locator('.drag-handle');
    const lastItem = sortableItems.last();

    // Get bounding boxes
    const firstBox = await firstHandle.boundingBox();
    const lastBox = await lastItem.boundingBox();
    console.log('  [boxes]', JSON.stringify({ firstBox, lastBox }));

    if (firstBox && lastBox) {
      // Instrument mouse + sortable events so CI reveals whether they fire.
      // NB: pass a plain string, not a TS closure — tsx injects __name helpers
      // that don't exist in the browser context.
      await page.evaluate(`
        window.__dragLog = [];
        var rec = function (type) { return function (e) {
          window.__dragLog.push([type, Math.round(e.clientX), Math.round(e.clientY)]);
        }; };
        document.addEventListener('mousedown', rec('document:mousedown'), true);
        document.addEventListener('mousemove', rec('document:mousemove'), true);
        document.addEventListener('mouseup', rec('document:mouseup'), true);
        var h = document.querySelector('.drag-handle');
        if (h) h.addEventListener('mousedown', rec('handle:mousedown'));
      `);

      // Drag first item below the last item — use intermediate steps with small
      // delays so SortableJS throttled move handlers keep up.
      const startX = firstBox.x + firstBox.width / 2;
      const startY = firstBox.y + firstBox.height / 2;
      const endX = lastBox.x + lastBox.width / 2;
      const endY = lastBox.y + lastBox.height + 10;
      await page.mouse.move(startX, startY);
      await page.waitForTimeout(50);
      await page.mouse.down();
      // Move in 5 intermediate steps with brief pauses
      for (let i = 1; i <= 5; i++) {
        const t = i / 5;
        await page.mouse.move(
          startX + (endX - startX) * t,
          startY + (endY - startY) * t,
          { steps: 5 }
        );
        await page.waitForTimeout(30);
      }
      await page.mouse.up();

      // Wait for HTMX update
      await page.waitForTimeout(1000);

      const dragLog = await page.evaluate('window.__dragLog');
      console.log('  [drag-log-length]', dragLog?.length);
      console.log('  [drag-log-sample]', JSON.stringify(dragLog?.slice(0, 5)));
      console.log('  [drag-log-tail]', JSON.stringify(dragLog?.slice(-5)));
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
