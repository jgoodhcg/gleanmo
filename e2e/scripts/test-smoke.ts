// E2E smoke test: basic regression check across key pages
// Usage: npm run test:smoke
//
// Verifies that major pages load without errors:
// 1. Home/overview (with HTMX lazy-loaded fragments)
// 2. Today page
// 3. Task Focus page
// 4. CRUD list pages (tasks, habits, projects)
// 5. CRUD new form pages
// 6. Sidebar renders on each page

import { chromium, Page, expect } from '@playwright/test';
import { authenticateForDev } from './auth.js';

const BASE_URL = process.env.BASE_URL || 'http://localhost:8080';

async function captureScreenshot(page: Page, name: string) {
  const phase = process.env.SCREENSHOT_PHASE;
  const prefix = phase ? `${phase}-` : '';
  const filepath = `screenshots/${prefix}smoke-${name}.png`;
  await page.screenshot({ path: filepath });
  console.log(`  [screenshot] ${filepath}`);
}

/** Collect any console errors during page load. */
function trackConsoleErrors(page: Page): string[] {
  const errors: string[] = [];
  page.on('console', (msg) => {
    if (msg.type() === 'error') errors.push(msg.text());
  });
  return errors;
}

/** Assert the sidebar is present and has expected nav links. */
async function assertSidebar(page: Page, label: string) {
  const todayLink = page.locator('a[href="/app/task/today"]');
  await expect(todayLink).toBeVisible({ timeout: 5000 });
  const homeLink = page.locator('a[href="/app"]');
  await expect(homeLink).toBeVisible({ timeout: 5000 });
  console.log(`  + sidebar OK (${label})`);
}

/** Load a page, assert no HTTP errors, return console errors. */
async function loadPage(page: Page, path: string, label: string): Promise<string[]> {
  const errors = trackConsoleErrors(page);
  const response = await page.goto(`${BASE_URL}${path}`, { waitUntil: 'networkidle' });
  expect(response?.status()).toBeLessThan(400);
  console.log(`  + ${label}: ${response?.status()} OK`);
  return errors;
}

async function main() {
  console.log('\n=== Smoke Test ===\n');

  const browser = await chromium.launch({ headless: true });
  const page = await browser.newPage({ viewport: { width: 1280, height: 720 } });

  try {
    const testEmail = 'e2e-smoke@localhost';
    await authenticateForDev(page, testEmail);

    // ── 1. Home / Overview ──
    console.log('1. Home page (overview with HTMX fragments)...');
    await loadPage(page, '/app', 'home');
    await assertSidebar(page, 'home');

    // Wait for HTMX lazy-loaded sections to hydrate
    await expect(page.locator('#overview-stats')).toBeVisible({ timeout: 15000 });
    await expect(page.locator('#overview-recent')).toBeVisible({ timeout: 15000 });
    await expect(page.locator('#overview-events')).toBeVisible({ timeout: 15000 });

    // Verify stats section loaded (not still showing placeholder)
    await page.waitForFunction(
      (sel) => {
        const el = document.querySelector(sel);
        return el && !el.textContent?.includes('Loading stats');
      },
      '#overview-stats',
      { timeout: 15000 }
    );
    console.log('  + HTMX fragments loaded');
    await captureScreenshot(page, '01-home');

    // ── 2. Today page ──
    console.log('\n2. Today page...');
    await loadPage(page, '/app/task/today', 'today');
    await assertSidebar(page, 'today');
    await expect(page.locator('h1:has-text("Today")')).toBeVisible({ timeout: 5000 });
    await expect(page.locator('#today-quick-add')).toBeVisible({ timeout: 5000 });
    await captureScreenshot(page, '02-today');

    // ── 3. Task Focus page ──
    console.log('\n3. Task Focus page...');
    await loadPage(page, '/app/task/focus', 'task-focus');
    await assertSidebar(page, 'task-focus');
    await captureScreenshot(page, '03-task-focus');

    // ── 4. CRUD list pages ──
    const crudEntities = [
      { path: '/app/crud/task', label: 'tasks' },
      { path: '/app/crud/habit', label: 'habits' },
      { path: '/app/crud/project', label: 'projects' },
      { path: '/app/crud/habit-log', label: 'habit-logs' },
      { path: '/app/crud/meditation-log', label: 'meditation-logs' },
      { path: '/app/crud/book', label: 'books' },
      { path: '/app/crud/reading-log', label: 'reading-logs' },
    ];

    console.log('\n4. CRUD list pages...');
    for (const entity of crudEntities) {
      await loadPage(page, entity.path, entity.label);
    }
    await captureScreenshot(page, '04-crud-lists');

    // ── 5. CRUD new form pages ──
    const formPages = [
      { path: '/app/crud/form/task/new', label: 'new-task-form' },
      { path: '/app/crud/form/habit/new', label: 'new-habit-form' },
      { path: '/app/crud/form/project/new', label: 'new-project-form' },
      { path: '/app/crud/form/book/new', label: 'new-book-form' },
    ];

    console.log('\n5. CRUD new form pages...');
    for (const form of formPages) {
      await loadPage(page, form.path, form.label);
      // Verify the form is present
      const formEl = page.locator('form').first();
      await expect(formEl).toBeVisible({ timeout: 5000 });
    }
    await captureScreenshot(page, '05-crud-forms');

    // ── 6. Calendar page (no sidebar — full-width layout) ──
    console.log('\n6. Calendar page...');
    await loadPage(page, '/app/calendar/year', 'calendar-year');
    await captureScreenshot(page, '06-calendar');

    // ── 7. Reading-log timer page ──
    console.log('\n7. Reading-log timer page...');
    await loadPage(page, '/app/timer/reading-log', 'reading-log-timer');
    await assertSidebar(page, 'reading-log-timer');
    await captureScreenshot(page, '07-reading-log-timer');

    console.log('\n=== Smoke Test Passed ===\n');
  } catch (error) {
    console.error('\n=== Smoke Test Failed ===');
    console.error(error);
    await captureScreenshot(page, 'error-state');
    process.exit(1);
  } finally {
    await browser.close();
  }
}

main();
