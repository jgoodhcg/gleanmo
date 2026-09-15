// E2E test for the goals dashboard (roadmap/081-goals-dashboard.md)
// Usage: npm run test:goals
//
// This test, on a fresh user:
// 1. Creates a book with totals and reading logs with positions
// 2. Creates a dated duration goal and a book-completion goal in the editor
// 3. Checks the redirected dashboard computes both from the logs
// 4. Filters, searches, and selects goals in the table
// 5. Persists the book chart measure preference
// 6. Edits a goal, changes its source data, and sees the dashboard follow
// 7. Rejects an invalid goal without writing it
// 8. Archives and restores a goal
// 9. Captures desktop and mobile screenshots

import { chromium, Page, Locator, expect } from '@playwright/test';
import { authenticateForDev } from './auth.js';

const BASE_URL = process.env.BASE_URL || 'http://localhost:8080';

async function captureScreenshot(page: Page, name: string, fullPage = false) {
  const phase = process.env.SCREENSHOT_PHASE;
  const prefix = phase ? `${phase}-` : '';
  const filepath = `screenshots/${prefix}goals-${name}.png`;
  await page.screenshot({ path: filepath, fullPage });
  console.log(`  [screenshot] ${filepath}`);
}

async function submitForm(form: Locator, fallbackAction: string) {
  await form.evaluate((node: HTMLElement, action: string) => {
    const formEl = node as HTMLFormElement;
    formEl.setAttribute('action', formEl.getAttribute('hx-post') || formEl.getAttribute('action') || action);
    formEl.setAttribute('method', 'POST');
  }, fallbackAction);
  await Promise.all([
    form.page().waitForLoadState('networkidle'),
    form.evaluate((node: HTMLElement) => (node as HTMLFormElement).submit()),
  ]);
  await form.page().waitForTimeout(300);
}

async function setSelect(select: Locator, value: string) {
  await select.evaluate((node: HTMLElement, next: string) => {
    const el = node as HTMLSelectElement;
    el.value = next;
    el.dispatchEvent(new Event('input', { bubbles: true }));
    el.dispatchEvent(new Event('change', { bubbles: true }));
  }, value);
}

function shiftDate(isoDate: string, days: number) {
  const d = new Date(`${isoDate}T12:00:00Z`);
  d.setUTCDate(d.getUTCDate() + days);
  return d.toISOString().slice(0, 10);
}

async function todayFor(page: Page) {
  await page.goto(`${BASE_URL}/app/crud/form/reading-log/new`);
  await page.waitForLoadState('networkidle');
  const beginning = await page.locator('input[name="reading-log/beginning"]').inputValue();
  return beginning.slice(0, 10);
}

async function createBook(page: Page, title: string) {
  await page.goto(`${BASE_URL}/app/crud/form/book/new`);
  await page.waitForLoadState('networkidle');
  const form = page.locator('#book-new-form');
  await form.locator('[name="book/title"]').fill(title);
  await form.locator('[name="book/total-pages"]').fill('300');
  await form.locator('[name="book/total-chapters"]').fill('20');
  await form.locator('[name="book/audiobook-duration-seconds"]').fill('10:00:00');
  await submitForm(form, '/app/crud/book');
}

async function bookId(page: Page) {
  await page.goto(`${BASE_URL}/app/crud/form/reading-log/new`);
  await page.waitForLoadState('networkidle');
  const values = await page
    .locator('select[name="reading-log/book-id"] option')
    .evaluateAll((nodes) => nodes.map((n) => (n as HTMLOptionElement).value).filter((v) => v));
  if (!values.length) throw new Error('No book options in reading-log form');
  return values[0];
}

async function createReadingLog(
  page: Page,
  data: { book: string; beginning: string; end: string; fields?: Record<string, string>; finished?: boolean },
) {
  await page.goto(`${BASE_URL}/app/crud/form/reading-log/new`);
  await page.waitForLoadState('networkidle');
  const form = page.locator('#reading-log-new-form');
  await setSelect(form.locator('select[name="reading-log/book-id"]'), data.book);
  await form.locator('input[name="reading-log/beginning"]').fill(data.beginning);
  await form.locator('input[name="reading-log/end"]').fill(data.end);
  await setSelect(form.locator('select[name="reading-log/time-zone"]'), 'UTC');
  for (const [name, value] of Object.entries(data.fields || {})) {
    await form.locator(`[name="${name}"]`).fill(value);
  }
  if (data.finished) await form.locator('input[name="reading-log/finished?"]').check();
  await submitForm(form, '/app/crud/reading-log');
}

async function openEditor(page: Page, path = '/app/goals/new') {
  await page.goto(`${BASE_URL}${path}`);
  await page.waitForLoadState('networkidle');
  const form = page.locator('#goal-editor-form');
  await expect(form).toBeVisible({ timeout: 10000 });
  return form;
}

async function refreshField(page: Page, select: Locator, value: string) {
  await setSelect(select, value);
  await page.waitForLoadState('networkidle');
  await page.waitForTimeout(400);
}

async function main() {
  console.log('\n=== Goals Dashboard Test ===\n');

  const browser = await chromium.launch({ headless: true });
  const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
  const email = `e2e-goals-${Date.now()}@localhost`;
  const errors: string[] = [];
  page.on('pageerror', (err) => errors.push(err.message));

  try {
    await authenticateForDev(page, email);

    // ── 1. Source data ──
    console.log('1. Creating a book and reading logs...');
    await createBook(page, 'The Odyssey');
    const book = await bookId(page);
    const today = await todayFor(page);
    const yesterday = shiftDate(today, -1);
    const twoDaysAgo = shiftDate(today, -2);
    await createReadingLog(page, {
      book, beginning: `${twoDaysAgo}T09:00`, end: `${twoDaysAgo}T09:30`,
      fields: { 'reading-log/end-chapter': '4' },
    });
    await createReadingLog(page, {
      book, beginning: `${yesterday}T09:00`, end: `${yesterday}T10:00`,
      fields: { 'reading-log/start-page': '0', 'reading-log/end-page': '120' },
    });
    console.log('  [+] Book and two reading logs created');

    // ── 2. Empty dashboard offers creation ──
    await page.goto(`${BASE_URL}/app/goals`);
    await page.waitForLoadState('networkidle');
    await expect(page.locator('text=Create your first goal')).toBeVisible();
    console.log('  [+] Empty state offers creation');

    // ── 3. Duration goal ──
    console.log('\n2. Creating a dated duration goal...');
    let form = await openEditor(page);
    const initialStart = await form.locator('[name="starts-on"]').inputValue();
    await refreshField(page, form.locator('[name="timing"]'), 'weekly');
    const weekday = new Date(initialStart + 'T12:00:00Z').getUTCDay();
    const monday = shiftDate(initialStart, -((weekday + 6) % 7));
    await expect(form.locator('[name="starts-on"]')).toHaveValue(monday);
    await expect(form.locator('[name="ends-on"]')).toHaveValue(shiftDate(monday, 6));
    await refreshField(page, form.locator('[name="timing"]'), 'dated');
    const customStart = shiftDate(initialStart, -2);
    const customEnd = shiftDate(initialStart, 19);
    await form.locator('[name="starts-on"]').fill(customStart);
    await form.locator('[name="ends-on"]').fill(customEnd);
    await refreshField(page, form.locator('[name="timing"]'), 'weekly');
    await expect(form.locator('[name="starts-on"]')).toHaveValue(customStart);
    await expect(form.locator('[name="ends-on"]')).toHaveValue(customEnd);
    await refreshField(page, form.locator('[name="timing"]'), 'dated');
    console.log('  [+] Weekly defaults use Monday/Sunday and preserve deliberate dates');
    await form.locator('[name="label"]').fill('Reading time');
    await form.locator('[name="target"]').fill('10');
    await form.locator('[name="starts-on"]').fill(shiftDate(today, -7));
    await form.locator('[name="ends-on"]').fill(shiftDate(today, 30));
    await captureScreenshot(page, '01-editor');
    await submitForm(form, '/app/goals');
    await expect(page).toHaveURL(/\/app\/goals\?goal=/);
    const detail = page.locator('#goal-detail');
    await expect(detail).toContainText('Reading time');
    await expect(detail).toContainText('1.5 h');
    await expect(detail.locator('#goal-chart canvas')).toBeVisible({ timeout: 10000 });
    console.log('  [+] Dashboard shows 1.5 h logged with a chart');

    // ── 4. Book completion goal ──
    console.log('\n3. Creating a book completion goal...');
    form = await openEditor(page);
    await form.locator('[name="label"]').fill('Finish The Odyssey');
    await refreshField(page, form.locator('select[name="measurement"]'),
      'reading-log/book-completion-completion');
    await expect(form.locator('[name="target"]')).toHaveCount(0);
    await setSelect(form.locator('select[name="relation-ids"]'), book);
    await refreshField(page, form.locator('select[name="timing"]'), 'open-ended');
    await expect(form.locator('[name="ends-on"]')).toHaveCount(0);
    await form.locator('[name="starts-on"]').fill(shiftDate(today, -7));
    await submitForm(form, '/app/goals');
    await expect(detail).toContainText('In progress');
    await expect(detail).toContainText('Latest pages');
    await expect(detail).toContainText('120');
    await expect(detail).toContainText('Recent reading');
    console.log('  [+] Book goal in progress at page 120 / 300');
    await captureScreenshot(page, '02-book-goal');

    // ── 5. Persist book measure preference ──
    console.log('\n4. Persisting the chart measure...');
    await Promise.all([
      page.waitForLoadState('networkidle'),
      detail.locator('button[name="progress-measure"][value="chapters"]').click(),
    ]);
    await page.waitForTimeout(300);
    await page.reload();
    await page.waitForLoadState('networkidle');
    await expect(page.locator('#goal-detail button[value="chapters"]'))
      .toHaveAttribute('aria-pressed', 'true');
    await expect(page.locator('#goal-detail')).toContainText('Latest chapters');
    console.log('  [+] Chapters preference persisted across reload');

    // ── 6. Table filters, search, and selection ──
    console.log('\n5. Filtering and selecting...');
    const count = page.locator('[data-goals-count]');
    await expect(count).toHaveText('2 / 2');
    await page.locator('[data-goal-kind="completion"]').click();
    await expect(count).toHaveText('1 / 2');
    await page.locator('[data-goal-kind="all"]').click();
    await page.locator('[data-goal-timing="open"]').click();
    await expect(count).toHaveText('1 / 2');
    await page.locator('[data-goal-timing="all"]').click();
    await page.locator('[data-goals-search]').fill('zzz');
    await expect(page.locator('[data-goals-empty]')).toBeVisible();
    await page.locator('[data-goals-search]').fill('');
    await expect(count).toHaveText('2 / 2');
    await page.locator('th[data-sort-key="progress"] button').click();
    await expect(page.locator('th[data-sort-key="progress"]')).toHaveAttribute('aria-sort', 'ascending');
    await page.locator('tr[data-goal-row]', { hasText: 'Reading time' }).locator('td').nth(2).click();
    await expect(page.locator('#goal-detail')).toContainText('Average so far', { timeout: 10000 });
    await expect(page).toHaveURL(/goal=/);
    console.log('  [+] Filters, search, sort, and row selection work');
    await captureScreenshot(page, '03-dashboard');

    // ── 7. Edit a goal; change source data ──
    console.log('\n6. Editing the goal and adding a log...');
    const goalUrl = new URL(page.url());
    const goalId = goalUrl.searchParams.get('goal');
    form = await openEditor(page, `/app/goal/${goalId}/edit`);
    await form.locator('[name="target"]').fill('2');
    await submitForm(form, `/app/goal/${goalId}`);
    await expect(page.locator('#goal-detail')).toContainText('of 2 h');
    await createReadingLog(page, {
      book, beginning: `${yesterday}T12:00`, end: `${yesterday}T13:00`,
      fields: { 'reading-log/end-page': '300' }, finished: false,
    });
    await page.goto(`${BASE_URL}/app/goals?goal=${goalId}`);
    await page.waitForLoadState('networkidle');
    await expect(page.locator('#goal-detail')).toContainText('2.5 h');
    await expect(page.locator('#goal-detail')).toContainText('Target reached');
    console.log('  [+] Dashboard follows the edit and the new log');

    // 100% of pages without the finished flag is still in progress.
    await page.goto(`${BASE_URL}/app/goals`);
    await page.locator('tr[data-goal-row]', { hasText: 'Finish The Odyssey' }).locator('a[data-goal-link]').click();
    await page.waitForLoadState('networkidle');
    await expect(page.locator('#goal-detail')).toContainText('In progress');
    const bookGoalId = new URL(page.url()).searchParams.get('goal');
    form = await openEditor(page, `/app/goal/${bookGoalId}/edit`);
    await refreshField(page, form.locator('[name="timing"]'), 'dated');
    await form.locator('[name="ends-on"]').fill(shiftDate(today, 30));
    await submitForm(form, `/app/goal/${bookGoalId}`);
    await page.locator('#book-even-pace').check();
    await page.waitForLoadState('networkidle');
    await page.reload();
    await expect(page.locator('#book-even-pace')).toBeChecked();
    await page.locator('#book-even-pace').uncheck();
    await page.waitForLoadState('networkidle');
    await page.reload();
    await expect(page.locator('#book-even-pace')).not.toBeChecked();
    console.log('  [+] Even-pace preference persists in both directions');
    await createReadingLog(page, {
      book, beginning: `${yesterday}T20:00`, end: `${yesterday}T20:30`, finished: true,
    });
    await page.goto(`${BASE_URL}/app/goals`);
    await page.locator('tr[data-goal-row]', { hasText: 'Finish The Odyssey' }).locator('a[data-goal-link]').click();
    await page.waitForLoadState('networkidle');
    await expect(page.locator('#goal-detail')).toContainText('Completed');
    console.log('  [+] Only the finished flag completes the book goal');

    // ── 8. Invalid goal is rejected without a write ──
    console.log('\n7. Rejecting an invalid goal...');
    form = await openEditor(page);
    await form.locator('[name="label"]').fill('No end date');
    await form.locator('[name="target"]').fill('5');
    await form.locator('[name="ends-on"]').fill('');
    await submitForm(form, '/app/goals');
    await expect(page.locator('text=A dated goal needs an end date.')).toBeVisible();
    await page.goto(`${BASE_URL}/app/goals`);
    await page.waitForLoadState('networkidle');
    await expect(page.locator('[data-goals-count]')).toHaveText('2 / 2');
    console.log('  [+] Field error shown; nothing written');

    // ── 9. Archive and restore ──
    console.log('\n8. Archiving and restoring...');
    await page.goto(`${BASE_URL}/app/goals?goal=${goalId}`);
    await page.waitForLoadState('networkidle');
    await Promise.all([
      page.waitForLoadState('networkidle'),
      page.locator('#goal-detail button:has-text("Archive")').click(),
    ]);
    await expect(page.locator('[data-goals-count]')).toHaveText('1 / 1');
    await expect(page.locator('text=Archived goals (1)')).toBeVisible();
    await page.locator('summary:has-text("Archived goals")').click();
    await Promise.all([
      page.waitForLoadState('networkidle'),
      page.locator('button:has-text("Restore")').click(),
    ]);
    await expect(page.locator('[data-goals-count]')).toHaveText('2 / 2');
    console.log('  [+] Archive hides the goal; restore brings it back');

    // ── 10. Mobile ──
    console.log('\n9. Mobile layout...');
    await page.setViewportSize({ width: 390, height: 844 });
    await page.goto(`${BASE_URL}/app/goals?goal=${goalId}`);
    await page.waitForLoadState('networkidle');
    await page.waitForTimeout(800);
    const overflow = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth);
    if (overflow > 1) throw new Error(`Page scrolls horizontally on mobile by ${overflow}px`);
    await captureScreenshot(page, '04-mobile', true);
    console.log('  [+] No page-level horizontal scroll; table scrolls inside its panel');

    if (errors.length) throw new Error(`Page errors: ${errors.join('; ')}`);
    console.log('\n=== Test Passed ===\n');
  } catch (error) {
    console.error('\n=== Test Failed ===');
    console.error(error);
    await captureScreenshot(page, 'error-state', true);
    process.exit(1);
  } finally {
    await browser.close();
  }
}

main();
