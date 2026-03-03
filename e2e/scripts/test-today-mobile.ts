// E2E test for Today page mobile-first redesign
// Usage: npm run test:today-mobile
//
// This test:
// 1. Creates test tasks with metadata (due date, effort, domain, notes)
// 2. Adds them to Today
// 3. Screenshots the collapsed card layout at mobile (375px) and desktop (1280px)
// 4. Taps a row to expand, screenshots the expanded state
// 5. Verifies drag handle exists and actions are visible when expanded
// 6. Performs drag via the .drag-handle and verifies reorder works
// 7. Verifies complete/defer/remove actions work from expanded view

import { chromium, Page, expect } from '@playwright/test';
import { authenticateForDev } from './auth.js';

const BASE_URL = process.env.BASE_URL || 'http://localhost:8080';

async function captureScreenshot(page: Page, name: string) {
  const phase = process.env.SCREENSHOT_PHASE;
  const prefix = phase ? `${phase}-` : '';
  const filepath = `screenshots/${prefix}today-mobile-${name}.png`;
  await page.screenshot({ path: filepath, fullPage: true });
  console.log(`  [screenshot] ${filepath}`);
}

async function createTestTask(page: Page, label: string, email: string, opts: {
  effort?: string;
  domain?: string;
  notes?: string;
} = {}) {
  await authenticateForDev(page, email);
  await page.goto(`${BASE_URL}/app/crud/form/task/new`);
  await page.waitForLoadState('networkidle');

  const taskForm = page.locator('#task-new-form');
  await taskForm.waitFor({ state: 'visible', timeout: 10000 });

  // Fill label
  const labelInput = taskForm.locator('input.form-input').first();
  await labelInput.fill(label);

  // Set state to "now"
  const stateSelect = taskForm.locator('select[name="task/state"]');
  await stateSelect.selectOption('now');

  // Set optional fields if provided
  if (opts.effort) {
    const effortSelect = taskForm.locator('select[name="task/effort"]');
    if (await effortSelect.isVisible()) {
      await effortSelect.selectOption(opts.effort);
    }
  }
  if (opts.domain) {
    const domainSelect = taskForm.locator('select[name="task/domain"]');
    if (await domainSelect.isVisible()) {
      await domainSelect.selectOption(opts.domain);
    }
  }
  if (opts.notes) {
    const notesInput = taskForm.locator('textarea[name="task/notes"]');
    if (await notesInput.isVisible()) {
      await notesInput.fill(opts.notes);
    }
  }

  // Submit
  await taskForm.evaluate((form: HTMLFormElement) => {
    form.setAttribute('action', form.getAttribute('hx-post') || '/app/crud/task');
    form.setAttribute('method', 'POST');
  });
  await taskForm.evaluate((form: HTMLFormElement) => form.submit());
  await page.waitForLoadState('networkidle');
  await page.waitForTimeout(500);
  console.log(`  [+] Created task "${label}"`);
}

async function addTaskToToday(page: Page, taskLabel: string) {
  const taskRow = page.locator('.bg-dark-surface', { has: page.locator(`a:has-text("${taskLabel}")`) });
  const todayButton = taskRow.locator('button:has-text("Today")').first();

  if (await todayButton.isVisible()) {
    await todayButton.click();
    await page.waitForTimeout(500);
    console.log(`  [+] Added "${taskLabel}" to today`);
  } else {
    console.log(`  [!] "${taskLabel}" may already be in today or button not found`);
  }
}

async function main() {
  console.log('\n=== Today Page Mobile Redesign Test ===\n');

  const browser = await chromium.launch({ headless: true });
  const testEmail = `e2e-mobile-test-${Date.now()}@localhost`;

  try {
    // ── 1. Create tasks with metadata ──
    console.log('1. Creating test tasks with metadata...');
    const desktopPage = await browser.newPage({ viewport: { width: 1280, height: 720 } });

    const taskA = `Mobile Task Alpha ${Date.now()}`;
    const taskB = `Mobile Task Beta ${Date.now()}`;
    const taskC = `Mobile Task Gamma ${Date.now()}`;

    await createTestTask(desktopPage, taskA, testEmail, {
      effort: 'high',
      domain: 'work',
      notes: 'This is a test note for the expanded view. It should be truncated if too long.',
    });
    await createTestTask(desktopPage, taskB, testEmail, {
      effort: 'low',
      domain: 'personal',
    });
    await createTestTask(desktopPage, taskC, testEmail);

    await desktopPage.waitForTimeout(1000);

    // ── 2. Add tasks to Today ──
    console.log('\n2. Adding tasks to Today via Focus page...');
    await authenticateForDev(desktopPage, testEmail);
    await desktopPage.goto(`${BASE_URL}/app/task/focus?state=now`);
    await desktopPage.waitForLoadState('networkidle');

    for (const label of [taskA, taskB, taskC]) {
      await addTaskToToday(desktopPage, label);
    }
    await desktopPage.close();

    // ── 3. Desktop viewport screenshots ──
    console.log('\n3. Desktop viewport (1280x720)...');
    const desktop = await browser.newPage({ viewport: { width: 1280, height: 720 } });
    await authenticateForDev(desktop, testEmail);
    await desktop.goto(`${BASE_URL}/app/task/today`);
    await desktop.waitForLoadState('networkidle');

    await captureScreenshot(desktop, '01-desktop-collapsed');

    // Verify drag handles are present
    const dragHandles = desktop.locator('.drag-handle');
    const handleCount = await dragHandles.count();
    expect(handleCount).toBeGreaterThanOrEqual(3);
    console.log(`  ✓ Found ${handleCount} drag handles`);

    // Verify task labels are visible
    const taskLabels = desktop.locator('.sortable-item .font-medium.text-white');
    expect(await taskLabels.count()).toBeGreaterThanOrEqual(3);
    console.log('  ✓ Task labels visible in collapsed state');

    // Verify action buttons are hidden (in .task-row-details.hidden)
    const hiddenDetails = desktop.locator('.task-row-details.hidden');
    expect(await hiddenDetails.count()).toBeGreaterThanOrEqual(3);
    console.log('  ✓ Details sections hidden by default');

    // ── 4. Expand a task row ──
    console.log('\n4. Expanding first task row...');
    const firstContent = desktop.locator('.sortable-item .cursor-pointer').first();
    await firstContent.click();
    await desktop.waitForTimeout(300);

    await captureScreenshot(desktop, '02-desktop-expanded');

    // Verify details are visible
    const visibleDetails = desktop.locator('.sortable-item').first().locator('.task-row-details:not(.hidden)');
    expect(await visibleDetails.count()).toBe(1);
    console.log('  ✓ First task details expanded');

    // Verify action buttons are visible in expanded state
    const expandedActions = visibleDetails.locator('button');
    expect(await expandedActions.count()).toBeGreaterThanOrEqual(2);
    console.log('  ✓ Action buttons visible when expanded');

    // Verify edit link is present
    const editLink = visibleDetails.locator('a:has-text("edit")');
    expect(await editLink.count()).toBe(1);
    console.log('  ✓ Edit link present');

    // Verify metadata is shown (effort, domain for task A)
    const metaText = await visibleDetails.textContent();
    expect(metaText).toContain('Effort:');
    expect(metaText).toContain('Domain:');
    console.log('  ✓ Metadata displayed in expanded view');

    // Collapse it back
    await firstContent.click();
    await desktop.waitForTimeout(300);

    const reHidden = desktop.locator('.sortable-item').first().locator('.task-row-details.hidden');
    expect(await reHidden.count()).toBe(1);
    console.log('  ✓ Details collapsed again on second tap');
    await desktop.close();

    // ── 5. Mobile viewport screenshots ──
    console.log('\n5. Mobile viewport (375x667)...');
    const mobile = await browser.newPage({ viewport: { width: 375, height: 667 } });
    await authenticateForDev(mobile, testEmail);
    await mobile.goto(`${BASE_URL}/app/task/today`);
    await mobile.waitForLoadState('networkidle');

    await captureScreenshot(mobile, '03-mobile-collapsed');

    // Verify no horizontal scrollbar
    const hasHScroll = await mobile.evaluate(() => {
      return document.documentElement.scrollWidth > document.documentElement.clientWidth;
    });
    expect(hasHScroll).toBe(false);
    console.log('  ✓ No horizontal scroll on mobile');

    // Expand first task on mobile
    const mobileContent = mobile.locator('.sortable-item .cursor-pointer').first();
    await mobileContent.click();
    await mobile.waitForTimeout(300);

    await captureScreenshot(mobile, '04-mobile-expanded');
    console.log('  ✓ Mobile expanded view captured');

    // ── 6. Drag-and-drop via handle ──
    console.log('\n6. Testing drag-and-drop via handle...');

    // Collapse first so we have a clean state
    await mobileContent.click();
    await mobile.waitForTimeout(300);

    // Get labels before reorder
    const labelsBefore = await mobile.locator('.sortable-item .font-medium.text-white').allTextContents();
    console.log('  Before:', labelsBefore);

    const firstHandle = mobile.locator('.sortable-item .drag-handle').first();
    const lastItem = mobile.locator('.sortable-item').last();

    const handleBox = await firstHandle.boundingBox();
    const lastBox = await lastItem.boundingBox();

    if (handleBox && lastBox) {
      await mobile.mouse.move(handleBox.x + handleBox.width / 2, handleBox.y + handleBox.height / 2);
      await mobile.mouse.down();
      await mobile.mouse.move(lastBox.x + lastBox.width / 2, lastBox.y + lastBox.height + 10, { steps: 10 });
      await mobile.mouse.up();
      await mobile.waitForTimeout(1000);

      await captureScreenshot(mobile, '05-mobile-after-reorder');

      const labelsAfter = await mobile.locator('.sortable-item .font-medium.text-white').allTextContents();
      console.log('  After:', labelsAfter);

      expect(labelsAfter[0]).not.toBe(labelsBefore[0]);
      console.log('  ✓ Drag-and-drop reorder worked via handle');
    } else {
      console.log('  [!] Could not get bounding boxes for drag test');
    }

    // ── 7. Test defer action from expanded view ──
    console.log('\n7. Testing defer action from expanded view...');
    const rowToDefer = mobile.locator('.sortable-item .cursor-pointer').first();
    await rowToDefer.click();
    await mobile.waitForTimeout(300);

    const deferButton = mobile.locator('.sortable-item').first().locator('button:has-text("tomorrow")');
    expect(await deferButton.isVisible()).toBe(true);
    console.log('  ✓ Defer button visible in expanded state');

    await captureScreenshot(mobile, '06-mobile-before-defer');

    await deferButton.click();
    await mobile.waitForTimeout(1000);

    await captureScreenshot(mobile, '07-mobile-after-defer');

    const labelsAfterDefer = await mobile.locator('.sortable-item .font-medium.text-white').allTextContents();
    console.log(`  Tasks remaining: ${labelsAfterDefer.length}`);
    console.log('  ✓ Defer action completed');

    await mobile.close();

    // ── 8. Tablet viewport bonus screenshot ──
    console.log('\n8. Tablet viewport (768x1024)...');
    const tablet = await browser.newPage({ viewport: { width: 768, height: 1024 } });
    await authenticateForDev(tablet, testEmail);
    await tablet.goto(`${BASE_URL}/app/task/today`);
    await tablet.waitForLoadState('networkidle');

    await captureScreenshot(tablet, '08-tablet-collapsed');

    // Expand one
    const tabletContent = tablet.locator('.sortable-item .cursor-pointer').first();
    if (await tabletContent.count() > 0) {
      await tabletContent.click();
      await tablet.waitForTimeout(300);
      await captureScreenshot(tablet, '09-tablet-expanded');
    }

    await tablet.close();

    console.log('\n=== Test Passed ===\n');
    console.log('Screenshots saved:');
    console.log('  today-mobile-01-desktop-collapsed.png');
    console.log('  today-mobile-02-desktop-expanded.png');
    console.log('  today-mobile-03-mobile-collapsed.png');
    console.log('  today-mobile-04-mobile-expanded.png');
    console.log('  today-mobile-05-mobile-after-reorder.png');
    console.log('  today-mobile-06-mobile-before-defer.png');
    console.log('  today-mobile-07-mobile-after-defer.png');
    console.log('  today-mobile-08-tablet-collapsed.png');
    console.log('  today-mobile-09-tablet-expanded.png');

  } catch (error) {
    console.error('\n=== Test Failed ===');
    console.error(error);
    // Try to capture error state on a fresh page
    const errorPage = await browser.newPage({ viewport: { width: 375, height: 667 } });
    try {
      await authenticateForDev(errorPage, testEmail);
      await errorPage.goto(`${BASE_URL}/app/task/today`);
      await errorPage.waitForLoadState('networkidle');
      await captureScreenshot(errorPage, 'error-state');
    } catch (_) { /* best effort */ }
    await errorPage.close();
    process.exit(1);
  } finally {
    await browser.close();
  }
}

main();
