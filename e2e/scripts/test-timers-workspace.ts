// E2E test for the unified timer workspace (/app/timers)
// Usage: npm run test:timers-workspace
//
// Verifies the workspace flows from roadmap/unified-timer-page.md:
// 1. Search-to-start: typing filters the combined parent list
// 2. One-tap start for project + reading (no CRUD form page)
// 3. Current location: a persisted global setting; starts stamp it; the
//    active-card select sets/clears per-log location via HTMX on change;
//    switching it with running timers offers to restart them at the new
//    location (confirm/dismiss)
// 4. Active timers across types visible with elapsed time
// 5. Stop returns to /app/timers (stop-in-place)
// 6. Recent-log edit links redirect back to /app/timers
// 7. Meditation: first start falls back to the CRUD form (required fields,
//    no prior log to copy), later starts are one-tap

import { chromium, Page, Locator, expect } from '@playwright/test';
import { authenticateForDev } from './auth.js';

const BASE_URL = process.env.BASE_URL || 'http://localhost:8080';

async function captureScreenshot(page: Page, name: string) {
  const phase = process.env.SCREENSHOT_PHASE;
  const prefix = phase ? `${phase}-` : '';
  const filepath = `screenshots/${prefix}timers-workspace-${name}.png`;
  await page.screenshot({ path: filepath });
  console.log(`  [screenshot] ${filepath}`);
}

async function submitHtmxForm(form: Locator, fallbackAction: string) {
  await form.evaluate((node: HTMLElement, action: string) => {
    const formEl = node as HTMLFormElement;
    formEl.setAttribute('action', formEl.getAttribute('hx-post') || action);
    formEl.setAttribute('method', 'POST');
  }, fallbackAction);
  await form.evaluate((node: HTMLElement) => (node as HTMLFormElement).submit());
}

async function setHiddenOrVisibleSelectValue(select: Locator, value: string) {
  await select.evaluate((node: HTMLElement, nextValue: string) => {
    const selectEl = node as HTMLSelectElement;
    selectEl.value = nextValue;
    selectEl.dispatchEvent(new Event('input', { bubbles: true }));
    selectEl.dispatchEvent(new Event('change', { bubbles: true }));
  }, value);
}

// Set a (possibly Choices-hidden) select by option label; returns the value.
async function setSelectValueByLabel(select: Locator, label: string): Promise<string> {
  return select.evaluate((node: HTMLElement, optionLabel: string) => {
    const selectEl = node as HTMLSelectElement;
    const option = Array.from(selectEl.options).find(
      (opt) => opt.textContent?.trim() === optionLabel
    );
    if (!option) throw new Error(`No option labeled "${optionLabel}"`);
    selectEl.value = option.value;
    selectEl.dispatchEvent(new Event('input', { bubbles: true }));
    selectEl.dispatchEvent(new Event('change', { bubbles: true }));
    return option.value;
  }, label);
}

async function createEntity(
  page: Page,
  entity: string,
  fieldName: string,
  fieldSelector: string,
  value: string
) {
  await page.goto(`${BASE_URL}/app/crud/form/${entity}/new`);
  await page.waitForLoadState('networkidle');

  const form = page.locator(`#${entity}-new-form`);
  await expect(form).toBeVisible({ timeout: 10000 });
  await form.locator(`${fieldSelector}[name="${fieldName}"]`).fill(value);

  await submitHtmxForm(form, `/app/crud/${entity}`);
  await page.waitForLoadState('networkidle');
  await page.waitForTimeout(300);
  console.log(`  [+] Created ${entity} "${value}"`);
}

function startRow(page: Page, label: string): Locator {
  return page.locator(`#start-timer-list div[data-filter-text="${label}"]`);
}

function startLocationSelect(page: Page): Locator {
  return page.locator('#start-location-select');
}

// Change the global current-location picker and wait for it to persist.
async function setCurrentLocation(page: Page, label: string): Promise<string> {
  const response = page.waitForResponse((r) =>
    r.url().includes('/app/timers/current-location')
  );
  const value = await setSelectValueByLabel(startLocationSelect(page), label);
  await response;
  await page.waitForTimeout(200);
  return value;
}

function cardLocationSelect(page: Page): Locator {
  return page.locator('#active-timers-section select[name="location-id"]');
}

async function startFromWorkspace(page: Page, label: string) {
  await startRow(page, label).locator('button:has-text("Start")').click();
  await page.waitForLoadState('networkidle');
}

async function expectOnWorkspace(page: Page, context: string) {
  const url = page.url();
  if (url.includes('/app/crud/form/')) {
    throw new Error(`${context}: bounced to a CRUD form: ${url}`);
  }
  if (!/\/app\/timers\/?(\?|$)/.test(url)) {
    throw new Error(`${context}: expected /app/timers, got: ${url}`);
  }
}

async function stopFromWorkspace(page: Page, label: string) {
  await page.locator('a:has-text("End Session")').first().click();
  await page.waitForLoadState('networkidle');
  await expectOnWorkspace(page, `Stop (${label})`);
  console.log(`  [+] Stopped "${label}" and returned to /app/timers`);
}

async function main() {
  console.log('\n=== Unified Timer Workspace Test ===\n');

  const browser = await chromium.launch({ headless: true });
  const page = await browser.newPage({ viewport: { width: 1280, height: 720 } });

  try {
    const stamp = Date.now();
    const email = `e2e-timers-workspace-${stamp}@localhost`;
    const projectLabel = `Alpha Project ${stamp}`;
    const bookTitle = `Beta Book ${stamp}`;
    const meditationLabel = `Zen Session ${stamp}`;
    const locationLabel = `Quiet Room ${stamp}`;
    const secondLocationLabel = `Cafe Corner ${stamp}`;

    // 1. Create one parent entity per timer type (+ locations for the picker)
    console.log('1. Creating parent entities...');
    await authenticateForDev(page, email);
    await createEntity(page, 'project', 'project/label', 'input', projectLabel);
    await createEntity(page, 'book', 'book/title', 'textarea', bookTitle);
    await createEntity(page, 'meditation', 'meditation/label', 'input', meditationLabel);
    await createEntity(page, 'location', 'location/label', 'input', locationLabel);
    await createEntity(page, 'location', 'location/label', 'input', secondLocationLabel);

    // 2. Load the workspace
    console.log('\n2. Loading /app/timers...');
    await page.goto(`${BASE_URL}/app/timers`);
    await page.waitForLoadState('networkidle');
    await expect(page.locator('text=Start Timer').first()).toBeVisible({ timeout: 10000 });
    await expect(startRow(page, projectLabel)).toBeVisible({ timeout: 10000 });
    await expect(startRow(page, bookTitle)).toBeVisible({ timeout: 5000 });
    await expect(startRow(page, meditationLabel)).toBeVisible({ timeout: 5000 });
    console.log('  [+] All three parent types listed');

    // Location select: present, defaulting to "no location" (no logs yet)
    await expect(startLocationSelect(page)).toBeAttached({ timeout: 5000 });
    await expect(startLocationSelect(page)).toHaveValue('');
    console.log('  [+] Location select rendered, "no location" preselected');
    await captureScreenshot(page, '01-workspace');

    // 3. Filter: typing narrows the list
    console.log('\n3. Filtering the start list...');
    const filterInput = page.locator('input[data-filter-list]');
    await filterInput.fill('Alpha');
    await expect(startRow(page, projectLabel)).toBeVisible({ timeout: 5000 });
    await expect(startRow(page, bookTitle)).toBeHidden({ timeout: 5000 });
    await expect(startRow(page, meditationLabel)).toBeHidden({ timeout: 5000 });
    console.log('  [+] Filter hides non-matching rows');
    await captureScreenshot(page, '02-filtered');
    await filterInput.fill('');
    await expect(startRow(page, bookTitle)).toBeVisible({ timeout: 5000 });

    // 4. Start a project timer with a location — one tap, no form.
    //    Setting the picker persists it and shows no prompt (nothing running).
    console.log('\n4. Starting project timer with location...');
    const locationId = await setCurrentLocation(page, locationLabel);
    await expect(page.locator('#relocate-prompt')).toBeEmpty();
    await startFromWorkspace(page, projectLabel);
    await expectOnWorkspace(page, 'Project start');
    await expect(page.locator('text=Running for').first()).toBeVisible({ timeout: 10000 });
    await expect(
      page.locator('#active-timers-section').locator(`text=${projectLabel}`)
    ).toBeVisible({ timeout: 10000 });
    // The active card's location select shows the chosen location
    await expect(cardLocationSelect(page)).toHaveValue(locationId, { timeout: 10000 });
    console.log('  [+] Project timer active with elapsed time and location, no form page');
    await captureScreenshot(page, '03-project-active');

    // 5. Stop it — returns to the workspace; last-used location is now default
    console.log('\n5. Stopping project timer...');
    await stopFromWorkspace(page, projectLabel);
    await expect(page.locator('text=Nothing running.').first()).toBeVisible({ timeout: 10000 });
    await expect(startLocationSelect(page)).toHaveValue(locationId);
    console.log('  [+] Last-used location preselected for the next start');

    // 6. Recent logs: edit link redirects back; log carries the location
    console.log('\n6. Opening recent-log edit link...');
    const editLink = page.locator('a[href*="/app/crud/form/project-log/edit/"]').first();
    await expect(editLink).toBeVisible({ timeout: 10000 });
    await editLink.click();
    await page.waitForLoadState('networkidle');
    const editForm = page.locator('#project-log-edit-form');
    await expect(editForm).toBeVisible({ timeout: 10000 });

    const projectLogLocation = await editForm
      .locator('select[name="project-log/location-id"]')
      .evaluate((node: HTMLSelectElement) => node.value);
    if (!projectLogLocation) {
      throw new Error('Project log is missing the location chosen at start');
    }
    console.log(`  [+] Project log carries location (id: ${projectLogLocation})`);
    await captureScreenshot(page, '04-recent-edit-form');

    await submitHtmxForm(editForm, '/app/crud/project-log');
    await page.waitForLoadState('networkidle');
    await page.waitForTimeout(300);
    if (!page.url().includes('/app/timers')) {
      throw new Error(`Edit submit did not return to /app/timers: ${page.url()}`);
    }
    console.log('  [+] Edit link round-trips back to /app/timers');

    // 7. Reading: start without location, then set + clear it on the card
    console.log('\n7. Reading timer with the card location select...');
    await setCurrentLocation(page, 'no location');
    await startFromWorkspace(page, bookTitle);
    await expectOnWorkspace(page, 'Reading start');
    const activeSection = page.locator('#active-timers-section');
    await expect(activeSection.locator(`text=${bookTitle}`)).toBeVisible({ timeout: 10000 });
    await expect(cardLocationSelect(page)).toHaveValue('');
    console.log('  [+] Reading timer active without location');

    // Set location from the card select (HTMX change, no page reload).
    // Wait for the section refresh so the assertion reads server state,
    // not the value we just set programmatically.
    let refresh = page.waitForResponse((r) => r.url().includes('/app/timers/active'));
    await setSelectValueByLabel(cardLocationSelect(page), locationLabel);
    await refresh;
    await page.waitForTimeout(300);
    await expect(cardLocationSelect(page)).toHaveValue(locationId, { timeout: 10000 });
    console.log('  [+] Card select set the location in place');
    await captureScreenshot(page, '05-card-location-set');

    // Clear it via the "no location" option (optional on reading-log)
    refresh = page.waitForResponse((r) => r.url().includes('/app/timers/active'));
    await setSelectValueByLabel(cardLocationSelect(page), 'no location');
    await refresh;
    await page.waitForTimeout(300);
    await expect(cardLocationSelect(page)).toHaveValue('', { timeout: 10000 });
    console.log('  [+] "no location" cleared it in place');

    // 7b. Switching the global location with a running timer prompts to
    //     relocate. Dismiss first, then confirm.
    console.log('\n7b. Relocate prompt (dismiss, then confirm)...');
    await setCurrentLocation(page, secondLocationLabel);
    const prompt = page.locator('#relocate-prompt');
    await expect(prompt).toContainText('Restart 1 running timer', { timeout: 10000 });
    await expect(prompt).toContainText(secondLocationLabel);
    await captureScreenshot(page, '05b-relocate-prompt');

    await prompt.locator('button:has-text("Dismiss")').click();
    await expect(prompt).toBeEmpty();
    await expect(cardLocationSelect(page)).toHaveValue('');
    console.log('  [+] Dismiss keeps the running timer untouched');

    const quietRoomId = await setCurrentLocation(page, locationLabel);
    await expect(prompt).toContainText('Restart 1 running timer', { timeout: 10000 });
    refresh = page.waitForResponse((r) => r.url().includes('/app/timers/active'));
    await prompt.locator('button:has-text("Restart here")').click();
    await refresh;
    await page.waitForTimeout(300);
    await expect(prompt).toBeEmpty();
    // Still one running timer for the same book, now at the new location
    await expect(activeSection.locator(`text=${bookTitle}`)).toBeVisible({ timeout: 10000 });
    await expect(activeSection.locator('a:has-text("End Session")')).toHaveCount(1);
    await expect(cardLocationSelect(page)).toHaveValue(quietRoomId, { timeout: 10000 });
    console.log('  [+] Confirm ended the old segment and restarted at the new location');
    await captureScreenshot(page, '05c-relocated');
    await stopFromWorkspace(page, bookTitle);

    // 8. Meditation: first start falls back to the CRUD form (no prior log
    //    to copy required position from); the chosen location rides along
    console.log('\n8. Meditation first start (form fallback expected)...');
    await setCurrentLocation(page, locationLabel);
    await startFromWorkspace(page, meditationLabel);
    if (!page.url().includes('/app/crud/form/meditation-log/new')) {
      throw new Error(
        `Expected fallback to the meditation-log new form, got: ${page.url()}`
      );
    }
    console.log('  [+] Fell back to the new-form (first-ever meditation log)');
    const newForm = page.locator('#meditation-log-new-form');
    await expect(newForm).toBeVisible({ timeout: 10000 });

    // Location should be preselected from the chip that rode along
    const locationSelect = newForm.locator('select[name="meditation-log/location-id"]');
    const prefilled = await locationSelect.evaluate((node: HTMLSelectElement) => node.value);
    if (!prefilled) {
      throw new Error('Fallback form did not preselect the chosen location');
    }
    console.log('  [+] Fallback form preselected the chip location');
    await setHiddenOrVisibleSelectValue(
      newForm.locator('select[name="meditation-log/time-zone"]'),
      'UTC'
    );
    await captureScreenshot(page, '06-meditation-fallback-form');
    await submitHtmxForm(newForm, '/app/crud/meditation-log');
    await page.waitForLoadState('networkidle');
    await page.waitForTimeout(300);
    if (!page.url().includes('/app/timers')) {
      await page.goto(`${BASE_URL}/app/timers`);
      await page.waitForLoadState('networkidle');
    }
    await expect(
      page.locator('#active-timers-section').locator(`text=${meditationLabel}`)
    ).toBeVisible({ timeout: 10000 });
    console.log('  [+] Meditation timer active after fallback form');
    await stopFromWorkspace(page, meditationLabel);

    // 9. Meditation second start: one tap, copied from the previous log
    console.log('\n9. Meditation second start (one-tap expected)...');
    await startFromWorkspace(page, meditationLabel);
    await expectOnWorkspace(page, 'Meditation second start');
    await expect(
      page.locator('#active-timers-section').locator(`text=${meditationLabel}`)
    ).toBeVisible({ timeout: 10000 });
    console.log('  [+] One-tap meditation start using the previous log as template');
    await captureScreenshot(page, '07-meditation-one-tap');
    await stopFromWorkspace(page, meditationLabel);

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
