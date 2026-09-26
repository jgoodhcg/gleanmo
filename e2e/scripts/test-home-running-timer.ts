// E2E test for the home overview's "Running now" strip.
//
// The elapsed time used to be rendered server-side and then sat frozen at
// whatever it was when the fragment loaded (roadmap/008-backlog.md, "Home Page
// Active Timers Don't Tick"). It now ticks client-side off data-epoch-ms, so
// this test asserts the number actually advances — a regression here is
// invisible in a screenshot, which is why it needs a test.
//
// Usage: npm run test:home-running-timer

import { chromium, Page, Locator, expect } from '@playwright/test';
import { authenticateForDev } from './auth.js';

const BASE_URL = process.env.BASE_URL || 'http://localhost:8080';

async function captureScreenshot(page: Page, name: string) {
  const phase = process.env.SCREENSHOT_PHASE;
  const prefix = phase ? `${phase}-` : '';
  const filepath = `screenshots/${prefix}home-running-timer-${name}.png`;
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

async function main() {
  console.log('\n=== Home Running-Timer Tick Test ===\n');

  const browser = await chromium.launch({ headless: true });
  const page = await browser.newPage({ viewport: { width: 1280, height: 900 } });

  try {
    const email = `e2e-home-timer-${Date.now()}@localhost`;
    const projectLabel = `Home Tick Test ${Date.now()}`;

    console.log('1. Creating project...');
    await authenticateForDev(page, email);
    await page.goto(`${BASE_URL}/app/crud/form/project/new`);
    await page.waitForLoadState('networkidle');
    const form = page.locator('#project-new-form');
    await expect(form).toBeVisible({ timeout: 10000 });
    await form.locator('input[name="project/label"]').fill(projectLabel);
    await submitHtmxForm(form, '/app/crud/project');
    await page.waitForLoadState('networkidle');
    await page.waitForTimeout(300);
    console.log(`  [+] Created project "${projectLabel}"`);

    console.log('\n2. Starting a timer...');
    await page.goto(`${BASE_URL}/app/timer/project-log`);
    await page.waitForLoadState('networkidle');
    const startButton = page.locator('button:has-text("Start Timer")').first();
    await expect(startButton).toBeVisible({ timeout: 10000 });
    await startButton.click();
    await page.waitForLoadState('networkidle');
    console.log('  [✓] Timer running');

    console.log('\n3. Loading the home overview...');
    await page.goto(`${BASE_URL}/app`);
    await page.waitForLoadState('networkidle');
    // The overview hydrates via HTMX; the strip only exists once it swaps in.
    await expect(page.locator('text=Running now').first()).toBeVisible({ timeout: 15000 });
    await expect(page.locator(`text=${projectLabel}`).first()).toBeVisible({ timeout: 10000 });
    await captureScreenshot(page, '01-running-now');

    const elapsed = page.locator('[data-epoch-ms]').first();
    await expect(elapsed).toBeVisible({ timeout: 10000 });

    console.log('\n4. Verifying the elapsed time ticks...');
    const first = (await elapsed.textContent())?.trim() ?? '';
    console.log(`  [i] t=0:  "${first}"`);

    if (!first || first === '…') {
      throw new Error(
        `Elapsed never rendered (still "${first}") — the tick script did not run.`
      );
    }

    // The "session" format is seconds under a minute, so ~3s is enough to see
    // the value move without making the test slow.
    await page.waitForTimeout(3500);
    const second = (await elapsed.textContent())?.trim() ?? '';
    console.log(`  [i] t=3s: "${second}"`);

    if (first === second) {
      throw new Error(
        `Elapsed time is frozen at "${first}" — it should advance every second.`
      );
    }
    console.log('  [✓] Elapsed time advanced');
    await captureScreenshot(page, '02-after-tick');

    // Workout and bouldering sessions have their own screens rather than a
    // Timers page section, so the strip has to find them separately
    // (roadmap/008-backlog.md, "Running Workouts Missing From Home").
    console.log('\n5. Starting a workout and a bouldering session...');
    const stamp = Date.now();
    const workoutLocation = `Home Strip Gym ${stamp}`;
    const boulderGym = `Home Strip Wall ${stamp}`;
    await page.goto(`${BASE_URL}/app/exercise/session`, { waitUntil: 'networkidle' });
    await page.locator('[name="location"]').fill(workoutLocation);
    await page.getByRole('button', { name: 'Start session', exact: true }).click();
    await page.waitForLoadState('networkidle');
    await page.goto(`${BASE_URL}/app/boulder/session`, { waitUntil: 'networkidle' });
    await page.locator('[name="gym"]').fill(boulderGym);
    await page.getByRole('button', { name: 'Start session', exact: true }).click();
    await page.waitForLoadState('networkidle');

    console.log('\n6. Checking both sessions on the home strip...');
    await page.goto(`${BASE_URL}/app`);
    await page.waitForLoadState('networkidle');
    await expect(page.locator('text=Running now').first()).toBeVisible({ timeout: 15000 });
    await expect(page.locator('text=3 active').first()).toBeVisible({ timeout: 10000 });
    const workoutCard = page.locator('a[href="/app/exercise/session"]', { hasText: workoutLocation });
    const boulderCard = page.locator('a[href="/app/boulder/session"]', { hasText: boulderGym });
    await expect(workoutCard).toBeVisible({ timeout: 10000 });
    await expect(boulderCard).toBeVisible({ timeout: 10000 });
    console.log('  [✓] Workout and bouldering sessions shown, linking to their screens');
    await captureScreenshot(page, '03-sessions');

    await workoutCard.click();
    await page.waitForLoadState('networkidle');
    if (!page.url().includes('/app/exercise/session')) {
      throw new Error(`Workout card led to ${page.url()}, not the workout screen.`);
    }
    await expect(page.getByText(workoutLocation, { exact: true })).toBeVisible();
    console.log('  [✓] Workout card opens the running session');

    console.log('\n7. Checking the Timers page does not list them...');
    await page.goto(`${BASE_URL}/app/timers`, { waitUntil: 'networkidle' });
    await expect(page.locator(`text=${projectLabel}`).first()).toBeVisible({ timeout: 10000 });
    for (const text of [workoutLocation, boulderGym]) {
      if (await page.locator(`text=${text}`).count() > 0) {
        throw new Error(`Timers page lists "${text}"; sessions belong only on home.`);
      }
    }
    console.log('  [✓] Timers page unchanged');

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
