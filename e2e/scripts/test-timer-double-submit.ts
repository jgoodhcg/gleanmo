// E2E test for the timer start double-submit guard.
//
// Starting a timer is a plain form POST with no feedback on a slow round trip.
// A tap that looks like it did nothing invites a second tap, and before this
// guard that produced two running timers on one project.
//
// Two independent halves are covered:
//   - server: a repeat POST inside the window writes nothing. This is the
//     guard. It holds regardless of how the duplicate arrived, and it is the
//     half that catches the failure that actually happened — a second tap
//     after the page had already come back, which is a legitimately separate
//     request that no client-side lock can see.
//   - client: the submitter is marked in-flight and the form refuses further
//     submits. Its real contribution is the feedback, not the blocking:
//     Chromium already discards a second submit while a navigation is
//     pending, so the lock only matters once that navigation has finished.
//
// Also asserts the two things the guard must NOT do: block a start on a
// different project, and swallow the parent-id payload — the Start button
// carries it as name/value, so a guard that disabled the button would break
// start outright rather than merely fail to guard.
//
// Usage: npm run test:timer-double-submit

import { chromium, Page, Locator, expect } from '@playwright/test';
import { authenticateForDev } from './auth.js';

const BASE_URL = process.env.BASE_URL || 'http://localhost:8080';

async function captureScreenshot(page: Page, name: string) {
  const phase = process.env.SCREENSHOT_PHASE;
  const prefix = phase ? `${phase}-` : '';
  const filepath = `screenshots/${prefix}timer-double-submit-${name}.png`;
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

async function createProject(page: Page, label: string) {
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

// Count active timer cards naming this project on /app/timers.
async function runningCount(page: Page, label: string): Promise<number> {
  await page.goto(`${BASE_URL}/app/timers`);
  await page.waitForLoadState('networkidle');
  return page.locator('#active-timers-section, [id*="active"]')
    .first()
    .getByText(label, { exact: true })
    .count();
}

// The parent-id the Start button submits, read off the button itself.
async function startButtonFor(page: Page, label: string) {
  const row = page.locator('[data-filter-text]', { hasText: label }).first();
  await expect(row).toBeVisible({ timeout: 10000 });
  const button = row.locator('button[name="parent-id"]');
  const parentId = await button.getAttribute('value');
  const action = await button.getAttribute('formaction');
  return { button, parentId, action };
}

async function main() {
  console.log('\n=== Timer Double-Submit Guard Test ===\n');

  const browser = await chromium.launch({ headless: true });
  const page = await browser.newPage({ viewport: { width: 390, height: 844 } });

  try {
    const stamp = Date.now();
    const email = `e2e-double-submit-${stamp}@localhost`;
    const projectA = `Double Submit A ${stamp}`;
    const projectB = `Double Submit B ${stamp}`;

    console.log('1. Setting up two projects...');
    await authenticateForDev(page, email);
    await createProject(page, projectA);
    await createProject(page, projectB);

    // ---------------------------------------------------------------
    console.log('\n2. Starting a timer once...');
    await page.goto(`${BASE_URL}/app/timers`);
    await page.waitForLoadState('networkidle');

    const { button, parentId, action } = await startButtonFor(page, projectA);
    if (!parentId) {
      throw new Error('Start button carries no parent-id value');
    }
    console.log(`  [i] parent-id on the button: ${parentId}`);

    await button.click();
    await page.waitForLoadState('networkidle');
    await captureScreenshot(page, '01-after-first-start');

    let count = await runningCount(page, projectA);
    if (count !== 1) {
      throw new Error(`Expected exactly 1 running timer after one tap, got ${count}`);
    }
    console.log('  [✓] one running timer');

    // ---------------------------------------------------------------
    console.log('\n3. Server guard: replaying the same POST...');
    // Bypasses the client entirely — this is the path a network retry or a
    // back-button re-POST would take. The anti-forgery token has to ride along
    // or Biff answers 403 and the replay proves nothing: a real double-tap
    // resubmits the same form with the same valid token.
    await page.goto(`${BASE_URL}/app/timers`);
    await page.waitForLoadState('networkidle');
    const csrf = await page
      .locator('#start-timer-form input[name="__anti-forgery-token"]')
      .first()
      .getAttribute('value');
    if (!csrf) {
      throw new Error('No anti-forgery token on the start form');
    }

    const replay = await page.request.post(`${BASE_URL}${action}`, {
      form: { 'parent-id': parentId, '__anti-forgery-token': csrf },
      maxRedirects: 0,
    });
    console.log(`  [i] replay status: ${replay.status()}`);
    if (replay.status() !== 303) {
      throw new Error(`Expected a 303 like any other start, got ${replay.status()}`);
    }
    console.log('  [✓] responded 303 — indistinguishable from success, no error surfaced');

    count = await runningCount(page, projectA);
    if (count !== 1) {
      throw new Error(`Replay created a duplicate: ${count} running timers`);
    }
    console.log('  [✓] still exactly one running timer — nothing was written');

    // ---------------------------------------------------------------
    console.log('\n4. Guard is scoped to the parent, not the user...');
    const b = await startButtonFor(page, projectB);
    await b.button.click();
    await page.waitForLoadState('networkidle');

    const countB = await runningCount(page, projectB);
    if (countB !== 1) {
      throw new Error(`A different project must still start: got ${countB}`);
    }
    console.log('  [✓] a second project starts normally while the first runs');

    // ---------------------------------------------------------------
    console.log('\n5. Client guard: feedback and a locked form...');
    // Not asserted here: "two clicks produce one request". That assertion was
    // written first and measured nothing — with the guard opted out via
    // data-allow-resubmit, Chromium still issues a single request, because it
    // discards a second submit while a navigation is already pending. It
    // passed with the guard removed, so it proved only that browsers work.
    //
    // What the guard actually contributes is checked instead: the in-flight
    // marking (the missing feedback that caused the second tap) and a form
    // that refuses a further submit. Both are false without the guard.
    await page.goto(`${BASE_URL}/app/timers`);
    await page.waitForLoadState('networkidle');

    const state = await page.evaluate((projectLabel: string) => {
      const rows = Array.from(document.querySelectorAll('[data-filter-text]'));
      const row = rows.find((r) => (r.textContent || '').includes(projectLabel));
      const btn = row?.querySelector('button[name="parent-id"]') as HTMLButtonElement | null;
      if (!btn) return { found: false } as Record<string, unknown>;

      btn.click();
      const form = btn.closest('form') as HTMLFormElement;

      // A dispatched SubmitEvent runs the handlers without performing a real
      // submission, so this reads the guard's verdict on a second attempt
      // without firing another request.
      const second = new SubmitEvent('submit', {
        bubbles: true,
        cancelable: true,
        submitter: btn,
      });
      form.dispatchEvent(second);

      return {
        found: true,
        marked: btn.classList.contains('is-submitting'),
        locked: form.dataset.submitting === 'true',
        secondBlocked: second.defaultPrevented,
        // Must never be `disabled`: that drops the button's name/value from
        // the POST, so parent-id would vanish and start would break outright.
        disabled: btn.disabled,
        stillCarriesParentId: btn.getAttribute('value'),
      };
    }, projectB);

    if (!state.found) throw new Error(`No start row for ${projectB}`);
    if (!state.marked) throw new Error('Submitter was not marked .is-submitting');
    if (!state.locked) throw new Error('Form was not locked after the first submit');
    if (!state.secondBlocked) throw new Error('Second submit was not prevented');
    if (state.disabled) {
      throw new Error(
        'Start button was disabled — that strips parent-id from the POST. ' +
        'The guard must stay cosmetic (class only).'
      );
    }
    if (!state.stillCarriesParentId) {
      throw new Error('parent-id was lost from the button');
    }
    console.log('  [✓] marked in-flight, form locked, second submit prevented');
    console.log('  [✓] button not disabled — parent-id still rides along');

    await page.waitForLoadState('networkidle');
    await captureScreenshot(page, '02-after-double-tap');

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
