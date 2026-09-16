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

async function checkActivityKeyboard(page: Page) {
  const panel = page.locator('[data-goal-activity]');
  const cells = panel.locator('[data-activity-cell]');
  await expect(cells).toHaveCount(84);
  await expect(panel.locator('[data-activity-cell][tabindex="0"]')).toHaveCount(1);
  // Start with a pointer click, without relying on the page's Tab order.
  await cells.nth(40).click();
  await expect(cells.nth(40)).toBeFocused();
  await expect(cells.nth(40)).toHaveAttribute('tabindex', '0');
  await expect(panel.locator('[data-activity-readout]'))
    .toHaveText((await cells.nth(40).getAttribute('aria-label'))!);
  await expect(cells.nth(40)).not.toHaveCSS('box-shadow', 'none');
  await page.keyboard.press('ArrowRight');
  await expect(cells.nth(41)).toBeFocused();
  await page.keyboard.press('ArrowLeft');
  await expect(cells.nth(40)).toBeFocused();
  await cells.last().click();
  await expect(cells.last()).toBeFocused();
  await page.keyboard.press('ArrowLeft');
  await expect(cells.nth(82)).toBeFocused();
  await expect(panel.locator('[data-activity-readout]'))
    .toHaveText((await cells.nth(82).getAttribute('aria-label'))!);
  await page.keyboard.press('Home');
  await expect(cells.first()).toBeFocused();
  await page.keyboard.press('ArrowLeft');
  await expect(cells.first()).toBeFocused();
  await page.keyboard.press('ArrowRight');
  await expect(cells.nth(1)).toBeFocused();
  await page.keyboard.press('End');
  await expect(cells.last()).toBeFocused();
  await page.keyboard.press('ArrowRight');
  await expect(cells.last()).toBeFocused();
  await expect(panel.locator('[data-activity-cell][tabindex="0"]')).toHaveCount(1);
  await page.keyboard.press('Tab');
  await expect(cells.last()).not.toBeFocused();
  await page.keyboard.press('Shift+Tab');
  await expect(cells.last()).toBeFocused();
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
    // All source records and goals in this test use UTC.
    const today = new Date().toISOString().slice(0, 10);
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
    await setSelect(form.locator('[name="time-zone"]'), 'UTC');
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
    await expect(detail).not.toContainText('Coverage');
    await expect(detail).toContainText('12.9 min/day');
    await expect(detail).toContainText('16.5 min/day');
    const initialChart = JSON.parse((await page.locator('#goal-chart-data').textContent())!);
    expect(initialChart.series.map((series: { name: string }) => series.name))
      .toEqual(expect.arrayContaining(['Even pace', 'Required at day start']));
    const initialMarkers = initialChart.series[0].markLine.data;
    expect(initialMarkers.find((marker: { label: { formatter: string } }) => marker.label.formatter === 'Projection start').xAxis).toBe(today);
    console.log('  [+] Dashboard shows 1.5 h logged with a chart');

    // ── 4. Book completion goal ──
    console.log('\n3. Creating a book completion goal...');
    form = await openEditor(page);
    await setSelect(form.locator('[name="time-zone"]'), 'UTC');
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
    await checkActivityKeyboard(page);
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
    await expect(page.locator('#goal-detail')).toContainText('Average per completed day', { timeout: 10000 });
    await expect(page).toHaveURL(/goal=/);
    await checkActivityKeyboard(page);
    console.log('  [+] Filters, search, sort, and row selection work');
    await captureScreenshot(page, '03-dashboard');

    // ── 7. Edit a goal; change source data ──
    console.log('\n6. Editing the goal and adding a log...');
    const goalUrl = new URL(page.url());
    const goalId = goalUrl.searchParams.get('goal');
    // Edit an unselected book directly from its row, then return to the numeric goal.
    await page.getByRole('link', { name: 'Edit Finish The Odyssey', exact: true }).click();
    await expect(page.locator('#goal-editor-form [name="label"]')).toHaveValue('Finish The Odyssey');
    await page.goBack();
    await page.getByRole('link', { name: 'Edit Reading time', exact: true }).click();
    await expect(page).toHaveURL(new RegExp(`/app/goal/${goalId}/edit`));
    await page.goBack();
    await detail.locator('header a', { hasText: 'Edit' }).click();
    await expect(page).toHaveURL(new RegExp(`/app/goal/${goalId}/edit`));
    form = page.locator('#goal-editor-form');
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

    // Today's eligible interval updates totals, chart, book position, and activity.
    // Use seconds so this remains valid even during the first minute of a UTC day.
    console.log('\n8. Counting today while pace uses completed days...');
    const requestTime = new Date();
    const currentDay = requestTime.toISOString().slice(0, 10);
    const endTime = new Date(Math.floor(requestTime.getTime() / 1000) * 1000);
    const startTime = new Date(Math.max(
      Date.parse(`${currentDay}T00:00:00Z`), endTime.getTime() - 60000,
    ));
    const secondsToday = (endTime.getTime() - startTime.getTime()) / 1000;
    await createReadingLog(page, {
      book, beginning: startTime.toISOString().slice(0, 19),
      end: endTime.toISOString().slice(0, 19),
      fields: { 'reading-log/end-page': '250' },
    });
    // A future-ending interval must not contribute even its completed-day portion.
    await createReadingLog(page, {
      book, beginning: `${yesterday}T23:00`, end: `${shiftDate(currentDay, 1)}T12:00`,
      fields: { 'reading-log/end-page': '999' },
    });
    await page.goto(`${BASE_URL}/app/goals?goal=${goalId}`);
    await page.waitForLoadState('networkidle');
    const chart = JSON.parse((await page.locator('#goal-chart-data').textContent())!);
    const logged = chart.series.find((series: { name: string }) => series.name === 'Logged');
    expect(logged.data.at(-1)[1]).toBeCloseTo(3 + secondsToday / 3600, 5);
    expect(logged.data.at(-1)[0]).toContain(currentDay);
    const nowMarker = logged.markLine.data.find((marker: { label: { formatter: string } }) => marker.label.formatter === 'Now');
    const projectionMarker = logged.markLine.data.find((marker: { label: { formatter: string } }) => marker.label.formatter === 'Projection start');
    expect(nowMarker.xAxis).toBe(logged.data.at(-1)[0]);
    expect(projectionMarker).toBeUndefined(); // Reached goals have no required-pace projection.
    expect(new Date(logged.data.at(-1)[0]).getTime()).toBeLessThanOrEqual(new Date(chart.xAxis.max).getTime());
    expect(chart.series.some((series: { name: string }) => series.name === 'Even pace')).toBe(true);
    await expect(page.getByText('Totals include today · rates use completed days · each goal uses its saved time zone')).toBeVisible();
    await expect(detail).toContainText('Average per completed day');
    await expect(detail).not.toContainText('pace estimates are unavailable');
    await expect(detail).not.toContainText('Coverage unknown');
    await expect(detail.locator('header [data-goal-actions]')).toBeVisible();
    await expect(detail.locator('[data-goal-actions]')).toHaveCount(1);
    const cardBox = (await detail.boundingBox())!;
    const actionBox = (await detail.locator('[data-goal-actions]').boundingBox())!;
    expect(actionBox.y - cardBox.y).toBeLessThan(40);
    expect(cardBox.x + cardBox.width - actionBox.x - actionBox.width).toBeLessThan(40);
    await captureScreenshot(page, '05-count-today');
    await page.goto(`${BASE_URL}/app/goals?goal=${bookGoalId}`);
    await page.waitForLoadState('networkidle');
    await detail.locator('button[name="progress-measure"][value="pages"]').click();
    await page.waitForLoadState('networkidle');
    await expect(detail).toContainText('250');
    await expect(detail).not.toContainText('999');
    const bookChart = JSON.parse((await page.locator('#goal-chart-data').textContent())!);
    const positions = bookChart.series.find((series: { type: string }) => series.type === 'scatter');
    expect(positions.data.at(-1)[1]).toBe(250);
    expect(positions.data.at(-1)[0]).toContain(currentDay);
    console.log('  [+] Today updates numeric and book charts; future ends stay excluded');

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

    // Multi-year labels include years in both the table and chart heading.
    const year = Number(today.slice(0, 4));
    form = await openEditor(page, `/app/goal/${goalId}/edit`);
    await form.locator('[name="starts-on"]').fill(`${year - 2}-09-14`);
    await form.locator('[name="ends-on"]').fill(`${year + 1}-09-16`);
    await submitForm(form, `/app/goal/${goalId}`);
    const span = `Sep 14, ${year - 2} – Sep 16, ${year + 1}`;
    await expect(page.locator('tr[data-goal-row]', { hasText: 'Reading time' })).toContainText(span);
    await expect(detail).toContainText(`Cumulative progress · ${span}`);
    await captureScreenshot(page, '07-multi-year');

    // ── 10. Mobile ──
    console.log('\n9. Mobile layout...');
    await page.setViewportSize({ width: 390, height: 844 });
    await page.goto(`${BASE_URL}/app/goals?goal=${goalId}`);
    await page.waitForLoadState('networkidle');
    await page.waitForTimeout(800);
    await expect(page.getByRole('link', { name: 'Edit Reading time', exact: true })).toBeVisible();
    await checkActivityKeyboard(page);
    const overflow = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth);
    if (overflow > 1) throw new Error(`Page scrolls horizontally on mobile by ${overflow}px`);
    const mobileActions = (await detail.locator('header [data-goal-actions]').boundingBox())!;
    const mobileCard = (await detail.boundingBox())!;
    expect(mobileActions.y - mobileCard.y).toBeLessThan(40);
    expect(mobileCard.x + mobileCard.width - mobileActions.x - mobileActions.width).toBeLessThan(30);
    await captureScreenshot(page, '04-mobile', true);
    await detail.locator('header').evaluate((node) => node.scrollIntoView({ block: 'start', behavior: 'instant' }));
    await page.waitForTimeout(300);
    await captureScreenshot(page, '06-mobile-card');
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
