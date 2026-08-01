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

// Choices.js hides the underlying <select>, so drive the real element and
// fire the events the page's own recall/label wiring listens for.
async function pickExercise(page: Page, name: string, label: string) {
  await page.locator(`select[name="${name}"]`).evaluate(
    (node: HTMLSelectElement, optionLabel: string) => {
      const option = Array.from(node.options).find(
        (opt) => opt.textContent?.trim() === optionLabel
      );
      if (!option) throw new Error(`No option labeled "${optionLabel}"`);
      node.value = option.value;
      node.dispatchEvent(new Event('change', { bubbles: true }));
    },
    label
  );
}

function setCard(page: Page, n: number) {
  return page.locator(`[data-set-n="${n}"]`);
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

    const reps = page.locator('#wk-line-form [name="reps"]');
    await reps.fill('100');
    await expect(reps).toHaveValue('100');
    await page.locator('#wk-log-primary').click();
    await page.waitForLoadState('networkidle');
    await expect(setCard(page, 1).getByText(exercise, { exact: true })).toBeVisible();
    await expect(page.getByText('100 reps', { exact: true })).toBeVisible();
    await expectNoHorizontalOverflow(page);
    await capture(page, '01-logged-set');

    // ---- Time the set honestly first, describe it afterwards. ----
    // End set records nothing, so the interval never waits on the picker.
    await page.getByRole('button', { name: 'Start set', exact: true }).click();
    await page.waitForLoadState('networkidle');
    await page.getByRole('button', { name: 'End set', exact: true }).click();
    await page.waitForLoadState('networkidle');

    const bare = setCard(page, 2);
    await expect(bare).toBeVisible();
    await expect(bare.getByRole('button', { name: '+ Add exercise' })).toBeVisible();
    await capture(page, '03-bare-set');

    // Resume reopens that same set rather than starting a new one.
    await bare.getByRole('button', { name: 'resume' }).click();
    await page.waitForLoadState('networkidle');
    await expect(page.getByText('SET 2 · RECORDING')).toBeVisible();
    await expect(page.locator('[data-set-card]')).toHaveCount(1);
    await page.getByRole('button', { name: 'End set', exact: true }).click();
    await page.waitForLoadState('networkidle');

    // Fill the bare set in. The line must land on set 2, and set 2's
    // duration must survive being described after the fact — the whole point
    // of stopping the clock before reaching for the picker.
    const bareDuration = await setCard(page, 2).locator('.tabular-nums').first().innerText();
    const setTwoId = await setCard(page, 2).getAttribute('data-set-card');
    await setCard(page, 2).getByRole('button', { name: '+ Add exercise' }).click();
    await expect(page.locator(`#wk-set-mount-${setTwoId}`)).toContainText('EXERCISE');
    await pickExercise(page, 'line-exercise-id', exercise);
    await page.locator('[data-line-form] [name="reps"]').last().fill('7');
    await page.getByRole('button', { name: 'Add to set', exact: true }).click();
    await page.waitForLoadState('networkidle');

    await expect(setCard(page, 2).getByText(exercise, { exact: true })).toBeVisible();
    await expect(setCard(page, 2).getByText('7 reps', { exact: true })).toBeVisible();
    expect(await setCard(page, 2).locator('.tabular-nums').first().innerText())
      .toBe(bareDuration);
    await expectNoHorizontalOverflow(page);
    await capture(page, '04-filled-in-set');

    // ---- Correct a line in place, without bouncing to a CRUD form. ----
    const other = `E2E Second movement ${stamp}`;
    await createExercise(page, other);
    await page.goto(`${BASE_URL}/app/exercise/session`, { waitUntil: 'networkidle' });
    await setCard(page, 2).getByText(exercise, { exact: true }).click();
    await expect(page.locator('[data-line-form]').last()).toBeVisible();
    // Swapping the exercise must leave the reps alone — the edit form
    // deliberately carries no recall-on-select memory.
    await pickExercise(page, 'line-exercise-id', other);
    await expect(page.locator('[data-line-form] [name="reps"]').last()).toHaveValue('7');
    await page.getByRole('button', { name: 'Save', exact: true }).click();
    await page.waitForLoadState('networkidle');
    await expect(setCard(page, 2).getByText(other, { exact: true })).toBeVisible();
    await expect(setCard(page, 2).getByText('7 reps', { exact: true })).toBeVisible();

    // Only one editor open at a time, so the picker's DOM id stays unique.
    await setCard(page, 1).getByText(exercise, { exact: true }).click();
    await expect(page.locator('[data-line-form]').last()).toBeVisible();
    await setCard(page, 2).getByText(other, { exact: true }).click();
    await expect(page.locator('[data-line-form]')).toHaveCount(2); // session form + one
    await capture(page, '05-inline-line-edit');

    // Deleting the line leaves the set, and the set stays fillable.
    await page.getByRole('button', { name: 'Delete line', exact: true }).click();
    await page.waitForLoadState('networkidle');
    await expect(setCard(page, 2).getByText(other, { exact: true })).toHaveCount(0);
    await expect(setCard(page, 2).getByRole('button', { name: '+ Add exercise' })).toBeVisible();

    // Finished-session card covers suggestions, readable timestamp, and totals.
    await page.getByRole('button', { name: 'End session', exact: true }).click();
    await page.waitForLoadState('networkidle');
    await expect(page.locator('#wk-locations option')).toHaveAttribute('value', location);
    const recent = page.locator('a[href*="/summary"]').first();
    await expect(recent).toContainText(location);
    await expect(recent).toContainText('2 sets');
    await expect(recent).toContainText('100 reps');
    expect(await recent.innerText()).toMatch(/[A-Z][a-z]{2} \d{1,2}, \d{4} · \d{1,2}:\d{2}/);
    await capture(page, '02-recent-session');

    // A finished session is still fixable: the summary carries the same
    // inline editors, minus resume (the session is over).
    await recent.click();
    await page.waitForLoadState('networkidle');
    await expect(page.getByRole('button', { name: 'resume' })).toHaveCount(0);
    await setCard(page, 2).getByRole('button', { name: '+ Add exercise' }).click();
    await expect(page.locator('[data-line-form]')).toHaveCount(1);
    await pickExercise(page, 'line-exercise-id', other);
    await page.getByRole('button', { name: 'Add to set', exact: true }).click();
    await page.waitForLoadState('networkidle');
    // Redirected back to the summary, not bounced to the live workout screen.
    expect(page.url()).toContain('/summary');
    await expect(setCard(page, 2).getByText(other, { exact: true })).toBeVisible();
    await expect(page.getByText('LINES').locator('..')).toContainText('2');
    await expectNoHorizontalOverflow(page);
    await capture(page, '06-summary-edit');

    console.log('Workout E2E passed');
  } catch (error) {
    await page.screenshot({ path: 'screenshots/workout-error.png', fullPage: true });
    throw error;
  } finally {
    await browser.close();
  }
}

main();
