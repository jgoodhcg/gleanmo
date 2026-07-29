// Compact E2E coverage for the workout session lifecycle.
// Usage: npm run test:workout

import { chromium, expect, Page } from '@playwright/test';
import { authenticateForDev } from './auth.js';

const BASE_URL = process.env.BASE_URL || 'http://localhost:8080';

async function capture(page: Page, name: string) {
  const phase = process.env.SCREENSHOT_PHASE;
  const prefix = phase ? `${phase}-` : '';
  await page.screenshot({ path: `screenshots/${prefix}workout-${name}.png` });
}

async function createExercise(page: Page, label: string) {
  await page.goto(`${BASE_URL}/app/crud/form/exercise/new`, { waitUntil: 'networkidle' });
  const form = page.locator('#exercise-new-form');
  await form.locator('[name="exercise/label"]').fill(label);
  await form.evaluate((node: HTMLFormElement) => {
    node.setAttribute('action', node.getAttribute('hx-post') || '/app/crud/exercise');
    node.setAttribute('method', 'POST');
    node.submit();
  });
  await page.waitForLoadState('networkidle');
}

async function expectNoHorizontalOverflow(page: Page) {
  expect(await page.evaluate(
    () => document.documentElement.scrollWidth <= document.documentElement.clientWidth
  )).toBe(true);
}

async function main() {
  const browser = await chromium.launch({ headless: true });
  const page = await browser.newPage({ viewport: { width: 390, height: 844 } });

  try {
    const stamp = Date.now();
    const exercise = `Leg swing all (flexion,extension,adduction,abduction) ${stamp}`;
    const location = `E2E Gym ${stamp}`;

    await authenticateForDev(page, `e2e-workout-${stamp}@localhost`);
    await createExercise(page, exercise);

    // Session creation, location, and session-duration correction.
    await page.goto(`${BASE_URL}/app/exercise/session`, { waitUntil: 'networkidle' });
    await page.locator('[name="location"]').fill(location);
    await page.getByRole('button', { name: 'Start session', exact: true }).click();
    await page.waitForLoadState('networkidle');
    await expect(page.getByText(location, { exact: true })).toBeVisible();

    const sessionAdjust = page.locator(
      'form[action*="/exercise/session/"][action$="/adjust"]'
    );
    await sessionAdjust.getByRole('button', { name: 'Add one minute' }).click();
    await page.waitForLoadState('networkidle');
    await expect(page.locator('[data-fmt="session"]')).toHaveText('1 min');
    await sessionAdjust.getByRole('button', { name: 'Subtract one minute' }).click();
    await page.waitForLoadState('networkidle');
    await expect(page.locator('[data-fmt="session"]')).toHaveText('0 min');

    // One running set covers timer adjustment/restart and three-digit reps.
    await page.getByRole('button', { name: 'Start set', exact: true }).click();
    await page.waitForLoadState('networkidle');
    const setAdjust = page.locator('form[action*="/exercise/set/"][action$="/adjust"]');
    await setAdjust.getByRole('button', { name: 'Add one minute' }).click();
    await page.waitForLoadState('networkidle');
    await expect(page.locator('[data-epoch-ms]:not([data-fmt])')).toHaveText(/^1:0\d$/);
    await page.getByRole('button', { name: 'Restart timer', exact: true }).click();
    await page.waitForLoadState('networkidle');
    await expect(page.locator('[data-epoch-ms]:not([data-fmt])')).toHaveText(/^0:0\d$/);

    const reps = page.locator('[name="reps"]');
    await reps.fill('100');
    await expect(reps).toHaveValue('100');
    await page.locator('#wk-log-primary').click();
    await page.waitForLoadState('networkidle');
    await expect(
      page.locator('a[href*="/exercise-line/edit/"] span').filter({ hasText: exercise })
    ).toBeVisible();
    await expect(page.getByText('100 reps', { exact: true })).toBeVisible();
    await expectNoHorizontalOverflow(page);
    await capture(page, '01-logged-set');

    // Finished-session card covers suggestions, readable timestamp, and totals.
    await page.getByRole('button', { name: 'End session', exact: true }).click();
    await page.waitForLoadState('networkidle');
    await expect(page.locator('#wk-locations option')).toHaveAttribute('value', location);
    const recent = page.locator('a[href*="/summary"]').first();
    await expect(recent).toContainText(location);
    await expect(recent).toContainText('1 set');
    await expect(recent).toContainText('100 reps');
    expect(await recent.innerText()).toMatch(/[A-Z][a-z]{2} \d{1,2}, \d{4} · \d{1,2}:\d{2}/);
    await capture(page, '02-recent-session');

    console.log('Workout E2E passed');
  } catch (error) {
    await page.screenshot({ path: 'screenshots/workout-error.png', fullPage: true });
    throw error;
  } finally {
    await browser.close();
  }
}

main();
