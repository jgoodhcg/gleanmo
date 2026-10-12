// E2E test for goals (roadmap/081, roadmap/090, roadmap/091)
// Usage: npm run test:goals
//
// This test, on a fresh user:
// 1. Creates a book with totals and reading logs with positions
// 2. Creates a dated duration goal (ruler page) and a book-completion goal (detail card)
// 3. Checks each goal's page computes from the logs
// 4. Lists goals by family and opens a goal from its row
// 5. Persists the book chart measure preference
// 6. Edits a goal, changes its source data, and sees the page follow
// 7. Rejects an invalid goal without writing it
// 8. Archives and restores a goal
// 9. Captures desktop and mobile screenshots
// 10. Suggests a target from history
// 11. Moves today's values when today is logged; weekly goals drop pace
// 12. Pins a goal: it shows in the list, sidebar, and home until archived or unpinned

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

function goalIdFromUrl(page: Page) {
  const match = new URL(page.url()).pathname.match(/^\/app\/goal\/([0-9a-f-]+)$/);
  if (!match) throw new Error(`Not on a goal page: ${page.url()}`);
  return match[1];
}

async function openGoalFromList(page: Page, label: string) {
  await page.goto(`${BASE_URL}/app/goals`);
  await page.waitForLoadState('networkidle');
  await Promise.all([
    page.waitForURL(/\/app\/goal\/[0-9a-f-]+$/),
    page.locator('[data-goal-row]', { hasText: label }).locator('a[data-goal-link]').click(),
  ]);
  await page.waitForLoadState('networkidle');
  return goalIdFromUrl(page);
}

async function main() {
  console.log('\n=== Goals Test ===\n');

  const browser = await chromium.launch({ headless: true });
  const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
  const email = `e2e-goals-${Date.now()}@localhost`;
  const errors: string[] = [];
  page.on('pageerror', (err) => errors.push(err.message));

  // The cumulative ruler page and the detail card other kinds keep.
  const cumulative = page.locator('[data-goal-page="cumulative"]');
  const detail = page.locator('#goal-detail');
  const key = (k: string) => page.locator(`[data-goal-key] [data-key="${k}"]`);
  const keyNumber = async (k: string) =>
    Number((await key(k).textContent())!.replace('−', '-').replace(/[^\d.-]/g, ''));
  const rows = page.locator('[data-goal-row]');

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

    // ── 2. Empty list offers creation ──
    await page.goto(`${BASE_URL}/app/goals`);
    await page.waitForLoadState('networkidle');
    await expect(page.locator('text=Create your first goal')).toBeVisible();
    console.log('  [+] Empty state offers creation');

    // ── 3. Duration goal: the ruler page ──
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
    const goalId = goalIdFromUrl(page);
    await expect(cumulative).toContainText('Reading time');
    await expect(cumulative).toContainText('1.5 h');
    await expect(cumulative.locator('#goal-chart canvas')).toBeVisible({ timeout: 10000 });
    await expect(cumulative.locator('[data-goal-ruler]')).toBeVisible();
    // 90 min over 7 elapsed days; 510 min still to go over 31 days.
    await expect(key('average')).toHaveText('12.9');
    await expect(key('required')).toHaveText('16.5');
    await expect(key('today')).toHaveText('0');
    // Even pace is 600 min over 38 days; tonight's even line is 8 days of it,
    // 126.3 rounded up to 127, so 37 more than the 90 logged.
    await expect(key('to-even')).toHaveText('37');
    for (const kind of ['you', 'keep', 'even']) {
      await expect(cumulative.locator(`[data-ruler-mark="${kind}"]`)).toHaveCount(1);
    }
    await expect(cumulative.locator('[data-goal-log]'))
      .toHaveAttribute('href', '/app/crud/form/reading-log/new');
    await expect(cumulative).not.toContainText('to stay on pace');
    await expect(cumulative.locator('details')).toHaveCount(0);
    const initialChart = JSON.parse((await page.locator('#goal-chart-data').textContent())!);
    expect(initialChart.series.map((series: { name: string }) => series.name))
      .toEqual(expect.arrayContaining(['Even pace', 'Needed from today']));
    const initialMarkers = initialChart.series[0].markLine.data;
    expect(initialMarkers.find((marker: { label: { formatter: string } }) => marker.label.formatter === 'Projection start').xAxis).toBe(today);
    console.log('  [+] Goal page shows 1.5 h logged, the ruler, key numbers, and the chart');

    // ── 4. Book completion goal: the detail card ──
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
    const bookGoalId = goalIdFromUrl(page);
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

    // ── 6. The list ──
    console.log('\n5. Listing goals by family...');
    await page.goto(`${BASE_URL}/app/goals`);
    await page.waitForLoadState('networkidle');
    await expect(rows).toHaveCount(2);
    await expect(page.locator('[data-goal-group="cumulative"] [data-goal-row]')).toContainText('Reading time');
    await expect(page.locator('[data-goal-group="other"] [data-goal-row]')).toContainText('Finish The Odyssey');
    await expect(page.locator('[data-goal-group="periodic"]')).toHaveCount(0);
    const readingRow = rows.filter({ hasText: 'Reading time' });
    await expect(readingRow).toContainText('today 0/17');
    await expect(readingRow).toContainText('31 days left');
    // Columns line up across groups.
    const nameX = await page.locator('[data-goal-row] a[data-goal-link]').evaluateAll((nodes) =>
      nodes.map((n) => Math.round(n.getBoundingClientRect().left)));
    expect(new Set(nameX).size).toBe(1);
    // A click anywhere on the row opens the goal.
    await Promise.all([
      page.waitForURL(new RegExp(`/app/goal/${goalId}$`)),
      readingRow.locator('.bg-dark').first().click(),
    ]);
    await expect(cumulative).toBeVisible();
    await page.goto(`${BASE_URL}/app/goals?goal=${goalId}`);
    await expect(page).toHaveURL(new RegExp(`/app/goal/${goalId}$`));
    console.log('  [+] Groups, aligned columns, row navigation, and the old ?goal= URL work');
    await page.goto(`${BASE_URL}/app/goals`);
    await captureScreenshot(page, '03-goals-page');

    // ── 7. Edit a goal; change source data ──
    console.log('\n6. Editing the goal and adding a log...');
    await page.goto(`${BASE_URL}/app/goal/${goalId}`);
    await cumulative.locator('[data-goal-actions] a', { hasText: 'Edit' }).click();
    await expect(page).toHaveURL(new RegExp(`/app/goal/${goalId}/edit`));
    form = page.locator('#goal-editor-form');
    await form.locator('[name="target"]').fill('2');
    await submitForm(form, `/app/goal/${goalId}`);
    await expect(cumulative).toContainText('2 h');
    await createReadingLog(page, {
      book, beginning: `${yesterday}T12:00`, end: `${yesterday}T13:00`,
      fields: { 'reading-log/end-page': '300' }, finished: false,
    });
    await page.goto(`${BASE_URL}/app/goal/${goalId}`);
    await page.waitForLoadState('networkidle');
    await expect(cumulative).toContainText('2.5 h');
    await expect(key('required')).toHaveText('—');
    await expect(cumulative.locator('[data-goal-ruler]')).toHaveCount(0);
    await page.goto(`${BASE_URL}/app/goals`);
    await expect(rows.filter({ hasText: 'Reading time' })).toContainText('Target reached');
    console.log('  [+] Page follows the edit and the new log; a reached goal has no ruler');

    // 100% of pages without the finished flag is still in progress.
    await openGoalFromList(page, 'Finish The Odyssey');
    await expect(detail).toContainText('In progress');
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
    await openGoalFromList(page, 'Finish The Odyssey');
    await expect(detail).toContainText('Completed');
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
    await expect(rows).toHaveCount(2);
    console.log('  [+] Field error shown; nothing written');

    // Today's eligible interval updates totals, chart, book position, and activity.
    // Use seconds so this remains valid even during the first minute of a UTC day.
    console.log('\n8. Counting today while pace uses elapsed days...');
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
    // A future-ending interval must not contribute even its elapsed-day portion.
    await createReadingLog(page, {
      book, beginning: `${yesterday}T23:00`, end: `${shiftDate(currentDay, 1)}T12:00`,
      fields: { 'reading-log/end-page': '999' },
    });
    await page.goto(`${BASE_URL}/app/goal/${goalId}`);
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
    await expect(key('average')).toBeVisible();
    await expect(cumulative.locator('[data-goal-actions]')).toHaveCount(1);
    await expect(cumulative.locator('[data-goal-actions]')).toBeVisible();
    await captureScreenshot(page, '05-count-today');
    await page.goto(`${BASE_URL}/app/goal/${bookGoalId}`);
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
    await page.goto(`${BASE_URL}/app/goal/${goalId}`);
    await page.waitForLoadState('networkidle');
    await Promise.all([
      page.waitForURL(`${BASE_URL}/app/goals`),
      cumulative.locator('button:has-text("Archive")').click(),
    ]);
    await expect(rows).toHaveCount(1);
    await expect(page.locator('text=Archived goals (1)')).toBeVisible();
    await page.goto(`${BASE_URL}/app/goal/${goalId}`);
    await expect(page).toHaveURL(`${BASE_URL}/app/goals`); // An archived goal has no page.
    await page.locator('summary:has-text("Archived goals")').click();
    await Promise.all([
      page.waitForURL(new RegExp(`/app/goal/${goalId}$`)),
      page.locator('button:has-text("Restore")').click(),
    ]);
    await page.goto(`${BASE_URL}/app/goals`);
    await expect(rows).toHaveCount(2);
    console.log('  [+] Archive hides the goal; restore brings it back');

    // Multi-year dates carry their years in the list and on the page.
    const year = Number(today.slice(0, 4));
    form = await openEditor(page, `/app/goal/${goalId}/edit`);
    await form.locator('[name="starts-on"]').fill(`${year - 2}-09-14`);
    await form.locator('[name="ends-on"]').fill(`${year + 1}-09-16`);
    await submitForm(form, `/app/goal/${goalId}`);
    await expect(cumulative).toContainText(`Sep 14, ${year - 2} – Sep 16, ${year + 1}`);
    await page.goto(`${BASE_URL}/app/goals`);
    await expect(rows.filter({ hasText: 'Reading time' })).toContainText(`ends Sep 16, ${year + 1}`);
    await captureScreenshot(page, '07-multi-year');

    // ── 10. Mobile ──
    console.log('\n9. Mobile layout...');
    await page.setViewportSize({ width: 390, height: 844 });
    form = await openEditor(page, `/app/goal/${goalId}/edit`);
    await form.locator('[name="target"]').fill('400');
    await submitForm(form, `/app/goal/${goalId}`);
    await page.waitForLoadState('networkidle');
    await page.waitForTimeout(800);
    await expect(cumulative.locator('[data-goal-ruler]')).toBeVisible();
    for (const path of [`/app/goal/${goalId}`, '/app/goals']) {
      await page.goto(`${BASE_URL}${path}`);
      await page.waitForLoadState('networkidle');
      const overflow = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth);
      if (overflow > 1) throw new Error(`${path} scrolls horizontally on mobile by ${overflow}px`);
    }
    await captureScreenshot(page, '13-goals-page-mobile', true);
    await page.goto(`${BASE_URL}/app/goal/${goalId}`);
    await page.waitForLoadState('networkidle');
    await page.waitForTimeout(500);
    // On a phone Log sits under the key numbers, above the quieter context.
    const keyBox = (await page.locator('[data-goal-key]').boundingBox())!;
    const logBox = (await page.locator('[data-goal-log]').boundingBox())!;
    const extraBox = (await page.locator('.goal-extra').boundingBox())!;
    expect(logBox.y).toBeGreaterThanOrEqual(keyBox.y + keyBox.height - 1);
    expect(extraBox.y).toBeGreaterThanOrEqual(logBox.y + logBox.height - 1);
    await captureScreenshot(page, '04-mobile', true);
    await page.setViewportSize({ width: 1440, height: 900 });
    await page.goto(`${BASE_URL}/app/goal/${goalId}`);
    await page.waitForLoadState('networkidle');
    const deskKey = (await page.locator('[data-goal-key]').boundingBox())!;
    const deskLog = (await page.locator('[data-goal-log]').boundingBox())!;
    expect(deskLog.x).toBeGreaterThan(deskKey.x + deskKey.width);
    await captureScreenshot(page, '14-goal-page-desktop', true);
    console.log('  [+] No horizontal scroll; Log below the key numbers on a phone, beside them on desktop');

    // ── 11. Suggested target ──
    console.log('\n10. Suggested target from history...');
    await createReadingLog(page, {
      book, beginning: `${shiftDate(today, -40)}T09:00`, end: `${shiftDate(today, -40)}T09:30`,
    });
    form = await openEditor(page);
    const suggestion = page.locator('#goal-suggestion');
    await expect(suggestion).toContainText('Suggested target', { timeout: 10000 });
    await expect(suggestion).toContainText('plus 15%');
    await expect(suggestion).toContainText('With no end date yet');
    const suggested = (await suggestion.textContent())!.match(/Suggested target: ([\d.,]+) hours/)![1];
    const endsOn = form.locator('[name="ends-on"]');
    const start = await form.locator('[name="starts-on"]').inputValue();
    await captureScreenshot(page, '08-suggestion');
    await suggestion.getByRole('button', { name: 'Use', exact: true }).click();
    await expect(form.locator('[name="target"]')).toHaveValue(suggested.replace(/,/g, ''));
    await expect(endsOn).toHaveValue(shiftDate(start, 364));
    await expect(suggestion).not.toContainText('With no end date yet', { timeout: 10000 });
    // A typed end date is never overwritten by Use.
    await endsOn.fill(shiftDate(start, 89));
    await endsOn.dispatchEvent('change');
    await expect(suggestion).toContainText('over 90 days', { timeout: 10000 });
    await suggestion.getByRole('button', { name: 'Use', exact: true }).click();
    await expect(endsOn).toHaveValue(shiftDate(start, 89));
    await refreshField(page, form.locator('select[name="measurement"]'),
      'reading-log/book-completion-completion');
    await expect(suggestion).toHaveCount(0);
    await refreshField(page, form.locator('select[name="measurement"]'),
      'reading-log/duration-total');
    await expect(suggestion).toContainText('Suggested target', { timeout: 10000 });
    await suggestion.getByRole('button', { name: 'Dismiss suggestion' }).click();
    await expect(suggestion).toHaveCount(0);
    console.log(`  [+] Editor suggests ${suggested} hours; Use fills the target and only an empty end date; dismiss removes it`);

    // ── 12. Today's values (roadmap/090-goal-live-today.md) ──
    console.log('\n11. Today moves the page when it is logged...');
    form = await openEditor(page, `/app/goal/${goalId}/edit`);
    await form.locator('[name="target"]').fill('40');
    await form.locator('[name="starts-on"]').fill(shiftDate(today, -7));
    await form.locator('[name="ends-on"]').fill(shiftDate(today, 30));
    await submitForm(form, `/app/goal/${goalId}`);
    await expect(cumulative.locator('[data-goal-ruler]')).toBeVisible();
    const readPage = async () => {
      const pageChart = JSON.parse((await page.locator('#goal-chart-data').textContent())!);
      const projected = pageChart.series.find((series: { name: string }) =>
        series.name.startsWith('At your average'));
      return {
        today: await keyNumber('today'), average: await keyNumber('average'),
        required: await keyNumber('required'), gap: await keyNumber('gap'),
        keep: Number(await cumulative.locator('[data-ruler-mark="keep"]').getAttribute('data-value')),
        projected: projected.data.at(-1)[1],
      };
    };
    const before = await readPage();
    // Minute precision: datetime-local rejects seconds without a step attribute.
    const liveEnd = new Date(Math.floor(Date.now() / 60000) * 60000);
    const liveDayStart = Date.parse(`${liveEnd.toISOString().slice(0, 10)}T00:00:00Z`);
    const liveStart = new Date(Math.max(liveDayStart, liveEnd.getTime() - 10 * 60000));
    if (liveStart.getTime() === liveEnd.getTime()) {
      console.log('  [i] First minute of a UTC day: no room for a log today, skipping');
    } else {
      await createReadingLog(page, {
        book, beginning: liveStart.toISOString().slice(0, 16), end: liveEnd.toISOString().slice(0, 16),
      });
      await page.goto(`${BASE_URL}/app/goal/${goalId}`);
      await page.waitForLoadState('networkidle');
      const after = await readPage();
      console.log(`  [i] before ${JSON.stringify(before)}`);
      console.log(`  [i] after  ${JSON.stringify(after)}`);
      expect(after.today).toBeGreaterThan(before.today);
      expect(after.projected).toBeGreaterThan(before.projected);
      // Day-start values wait for midnight.
      expect(after.average).toBe(before.average);
      expect(after.required).toBe(before.required);
      expect(after.gap).toBe(before.gap);
      expect(after.keep).toBe(before.keep);
      console.log('  [+] Today and the projection moved; day-start values held');
    }
    await captureScreenshot(page, '09-live-today', true);

    form = await openEditor(page, `/app/goal/${goalId}/edit`);
    await refreshField(page, form.locator('[name="timing"]'), 'weekly');
    await submitForm(form, `/app/goal/${goalId}`);
    await expect(detail).toContainText('Weekly reset');
    await expect(cumulative).toHaveCount(0);
    const weeklyChart = JSON.parse((await page.locator('#goal-chart-data').textContent())!);
    const weeklySeries: string[] = weeklyChart.series.map((series: { name: string }) => series.name);
    for (const name of ['Even pace', 'Needed from today']) {
      expect(weeklySeries).not.toContain(name);
    }
    expect(weeklySeries.some((name) => name.startsWith('At your average'))).toBe(false);
    expect(weeklySeries).toContain('Target');
    const weeklyMarkers = weeklyChart.series[0].markLine?.data ?? [];
    expect(weeklyMarkers.some((marker: { label: { formatter: string } }) =>
      marker.label.formatter === 'Projection start')).toBe(false);
    await expect(detail).not.toContainText('Required from today');
    await expect(detail).toContainText('Still to go this week');
    const live = detail.locator('[data-goal-live]');
    await expect(live).toContainText('Today:');
    await expect(live).not.toContainText('to stay on pace');
    await captureScreenshot(page, '10-weekly');
    await page.goto(`${BASE_URL}/app/goals`);
    await expect(page.locator('[data-goal-group="periodic"] [data-goal-row]')).toContainText('last 4 weeks');
    console.log('  [+] Weekly goals keep the detail card and list as periodic, with no pace or projection');

    // ── 13. Pinned goals (roadmap/091-pinned-goals.md) ──
    console.log('\n12. Pinning a goal to the list, sidebar, and home...');
    const homeGoals = page.locator('[data-home-goals]');
    const sidebarPin = page.locator(`#sidebar [data-sidebar-goal="${goalId}"]`);
    const loadHome = async () => {
      await page.goto(`${BASE_URL}/app`);
      await page.waitForLoadState('networkidle');
      await expect(page.locator('#overview-recent')).toBeVisible({ timeout: 15000 });
      await expect(page.locator('text=Activity timeline').first()).toBeVisible({ timeout: 15000 });
      await page.waitForLoadState('networkidle');
    };
    await loadHome();
    await expect(homeGoals).toHaveCount(0);
    await expect(page.locator('#sidebar [data-sidebar-goal]')).toHaveCount(0);
    console.log('  [+] No home section or sidebar pins without pins');

    await page.goto(`${BASE_URL}/app/goals`);
    await page.waitForLoadState('networkidle');
    await expect(rows.filter({ hasText: 'Reading time' }).locator('[data-goal-pin]'))
      .toHaveAttribute('aria-pressed', 'false');
    await page.goto(`${BASE_URL}/app/goal/${goalId}`);
    await page.waitForLoadState('networkidle');
    await Promise.all([
      page.waitForURL(new RegExp(`/app/goal/${goalId}$`)),
      detail.locator('[data-goal-pin]').click(),
    ]);
    await expect(detail.locator('[data-goal-pin]')).toHaveText('★ Pinned');
    await expect(sidebarPin).toBeVisible();
    await expect(sidebarPin).toHaveText('Reading time');
    await expect(sidebarPin).toHaveClass(/text-neon-cyan/); // The current goal is lit.
    await page.reload();
    await expect(detail.locator('[data-goal-pin]')).toHaveText('★ Pinned');
    await page.goto(`${BASE_URL}/app/goals`);
    await page.waitForLoadState('networkidle');
    await expect(rows.filter({ hasText: 'Reading time' }).locator('[data-goal-pin]'))
      .toHaveAttribute('aria-pressed', 'true');
    await expect(page.locator(`[data-goal-row][data-goal-id="${goalId}"]`)).toBeVisible();
    await expect(sidebarPin).not.toHaveClass(/text-neon-cyan/);
    await Promise.all([
      page.waitForURL(new RegExp(`/app/goal/${goalId}$`)),
      sidebarPin.click(),
    ]);
    console.log('  [+] Pin persists, shows in the sidebar, and the sidebar link opens the goal');

    await loadHome();
    await expect(homeGoals).toBeVisible({ timeout: 10000 });
    const card = homeGoals.locator(`[data-home-goal="${goalId}"]`);
    await expect(card).toContainText('Reading time');
    await expect(homeGoals.locator('[data-home-goal]')).toHaveCount(1);
    const cardText = (await card.textContent())!;
    const headline = cardText.match(/([\d.,]+ (?:h|min)) of ([\d.,]+ (?:h|min))/)!;
    const gap = cardText.match(/([\d.,]+ (?:h|min)) to go this week/)!;
    await captureScreenshot(page, '11-home-pinned');
    await card.click();
    await page.waitForLoadState('networkidle');
    await expect(page).toHaveURL(new RegExp(`/app/goal/${goalId}$`));
    await expect(detail).toContainText(headline[1]);
    await expect(detail).toContainText(`of ${headline[2]}`);
    await expect(detail).toContainText(gap[1]);
    console.log(`  [+] Home card (${headline[0]}, ${gap[0]}) matches the goal page and links to it`);

    await Promise.all([
      page.waitForURL(`${BASE_URL}/app/goals`),
      detail.locator('button:has-text("Archive")').click(),
    ]);
    await expect(sidebarPin).toHaveCount(0);
    await loadHome();
    await expect(homeGoals).toHaveCount(0);
    await page.goto(`${BASE_URL}/app/goals`);
    await page.locator('summary:has-text("Archived goals")').click();
    await Promise.all([
      page.waitForURL(new RegExp(`/app/goal/${goalId}$`)),
      page.locator('button:has-text("Restore")').click(),
    ]);
    await expect(detail.locator('[data-goal-pin]')).toHaveText('★ Pinned');
    await expect(sidebarPin).toBeVisible();
    console.log('  [+] An archived pin leaves home and the sidebar; archiving keeps the pin');

    await page.setViewportSize({ width: 390, height: 844 });
    await loadHome();
    await expect(homeGoals).toBeVisible({ timeout: 10000 });
    const homeOverflow = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth);
    if (homeOverflow > 1) throw new Error(`Home scrolls horizontally on mobile by ${homeOverflow}px`);
    await captureScreenshot(page, '12-home-pinned-mobile');
    await page.setViewportSize({ width: 1440, height: 900 });

    // Unpin from the list's star: it stays on the list.
    await page.goto(`${BASE_URL}/app/goals`);
    await page.waitForLoadState('networkidle');
    await Promise.all([
      page.waitForURL(`${BASE_URL}/app/goals`),
      rows.filter({ hasText: 'Reading time' }).locator('[data-goal-pin]').click(),
    ]);
    await expect(rows.filter({ hasText: 'Reading time' }).locator('[data-goal-pin]'))
      .toHaveAttribute('aria-pressed', 'false');
    await expect(sidebarPin).toHaveCount(0);
    await loadHome();
    await expect(homeGoals).toHaveCount(0);
    console.log('  [+] Unpinning from the list removes the sidebar link and the home card');

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
