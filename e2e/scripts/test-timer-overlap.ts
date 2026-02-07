// E2E test for overlap-aware timer stats on project timers
// Usage: npm run test:timer-overlap
//
// This test:
// 1. Creates two projects
// 2. Creates two fully overlapping one-hour project logs
// 3. Verifies stats show unique vs raw durations correctly
// 4. Verifies per-project raw breakdown is visible

import { chromium, Page, Locator, expect } from '@playwright/test';
import { authenticateForDev } from './auth.js';

const BASE_URL = process.env.BASE_URL || 'http://localhost:8080';

async function captureScreenshot(page: Page, name: string) {
  const filepath = `screenshots/timer-overlap-${name}.png`;
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

async function createProject(page: Page, email: string, label: string) {
  await authenticateForDev(page, email);
  await page.goto(`${BASE_URL}/app/crud/form/project/new`);
  await page.waitForLoadState('networkidle');

  const form = page.locator('#project-new-form');
  await expect(form).toBeVisible({ timeout: 10000 });
  await form.locator('input[name="project/label"]').fill(label);
  await submitHtmxForm(form, '/app/crud/project');
  await page.waitForLoadState('networkidle');
  await page.waitForTimeout(300);
  console.log(`  [+] Created project "${label}"`);
}

async function getProjectIdByLabel(page: Page, email: string, label: string) {
  await authenticateForDev(page, email);
  await page.goto(`${BASE_URL}/app/crud/form/project-log/new`);
  await page.waitForLoadState('networkidle');

  const values = await page
    .locator('select[name="project-log/project-id"] option')
    .evaluateAll((nodes, targetLabel) =>
      nodes
        .filter((node) => node.textContent?.trim() === targetLabel)
        .map((node) => (node as HTMLOptionElement).value),
    label);

  const value = values.find((v) => v && v.length > 0);
  if (!value) {
    throw new Error(`Missing option value for project "${label}"`);
  }
  return value;
}

async function getUserTodayDate(page: Page, email: string) {
  await authenticateForDev(page, email);
  await page.goto(`${BASE_URL}/app/crud/form/project-log/new`);
  await page.waitForLoadState('networkidle');

  const beginning = await page.locator('input[name="project-log/beginning"]').inputValue();
  if (!beginning || beginning.length < 10) {
    throw new Error(`Could not read beginning default value: "${beginning}"`);
  }
  return beginning.slice(0, 10);
}

async function createProjectLog(
  page: Page,
  email: string,
  data: { projectId: string; beginning: string; end: string }
) {
  await authenticateForDev(page, email);
  await page.goto(`${BASE_URL}/app/crud/form/project-log/new`);
  await page.waitForLoadState('networkidle');

  const form = page.locator('#project-log-new-form');
  await expect(form).toBeVisible({ timeout: 10000 });
  await setHiddenOrVisibleSelectValue(form.locator('select[name="project-log/project-id"]'), data.projectId);
  await form.locator('input[name="project-log/beginning"]').fill(data.beginning);
  await form.locator('input[name="project-log/end"]').fill(data.end);
  await setHiddenOrVisibleSelectValue(form.locator('select[name="project-log/time-zone"]'), 'UTC');

  await submitHtmxForm(form, '/app/crud/project-log');
  await page.waitForLoadState('networkidle');
  await page.waitForTimeout(300);
  console.log(`  [+] Created project log ${data.beginning} -> ${data.end}`);
}

async function expectMetricValue(page: Page, label: string, expected: string) {
  const labelNode = page.locator('p', { hasText: label }).first();
  await expect(labelNode).toBeVisible({ timeout: 10000 });
  const valueNode = labelNode.locator('xpath=following-sibling::p[1]');
  await expect(valueNode).toHaveText(expected);
}

async function expectProjectRawRow(page: Page, projectLabel: string, expected: string) {
  const statsCard = page.locator('div.bg-dark-surface', {
    has: page.locator('p:has-text("Active (unique)")')
  }).first();
  const row = statsCard.locator('div.flex.items-center.justify-between', {
    has: page.locator(`span:has-text("${projectLabel}")`)
  }).first();
  await expect(row).toBeVisible({ timeout: 10000 });
  await expect(row.locator('span').nth(1)).toHaveText(expected);
}

async function main() {
  console.log('\n=== Timer Overlap Metrics Test ===\n');

  const browser = await chromium.launch({ headless: true });
  const page = await browser.newPage({ viewport: { width: 1280, height: 720 } });

  try {
    const email = `e2e-timer-overlap-${Date.now()}@localhost`;
    const prefix = `Overlap Test ${Date.now()}`;
    const projectA = `${prefix} A`;
    const projectB = `${prefix} B`;

    console.log('1. Creating projects...');
    await createProject(page, email, projectA);
    await createProject(page, email, projectB);

    const projectAId = await getProjectIdByLabel(page, email, projectA);
    const projectBId = await getProjectIdByLabel(page, email, projectB);
    const date = await getUserTodayDate(page, email);

    const beginning = `${date}T09:00`;
    const end = `${date}T10:00`;

    console.log('\n2. Creating overlapping project logs...');
    await createProjectLog(page, email, { projectId: projectAId, beginning, end });
    await createProjectLog(page, email, { projectId: projectBId, beginning, end });

    console.log('\n3. Verifying overlap-aware stats...');
    await authenticateForDev(page, email);
    await page.goto(`${BASE_URL}/app/timer/project-log`);
    await page.waitForLoadState('networkidle');
    await captureScreenshot(page, '01-project-log-stats');

    await expectMetricValue(page, 'Active (unique)', '1h 0m');
    await expectMetricValue(page, 'Logged (raw)', '2h 0m');
    await expectMetricValue(page, 'Overlap removed', '1h 0m');
    await expect(page.locator('p', { hasText: 'By Project (raw)' })).toBeVisible();
    await expectProjectRawRow(page, projectA, '1h 0m');
    await expectProjectRawRow(page, projectB, '1h 0m');

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
