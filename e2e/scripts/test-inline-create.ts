// E2E test for inline related-entity creation.
// Verifies that a missing related entity can be created from inside the form
// that needs it: no page bounce, no lost form state, new entity auto-selected.
//
// Covers roadmap/inline-entity-creation.md Phase 1 validation:
//   - generic CRUD path (exercise-line -> exercise)
//   - timer-entity path (project-log -> project)
//   - validation failure re-renders the mini-form without creating
//
// Usage: npm run test:inline-create

import { chromium, Page, expect } from '@playwright/test';
import { authenticateForDev } from './auth.js';

const BASE_URL = process.env.BASE_URL || 'http://localhost:8080';

async function captureScreenshot(page: Page, name: string) {
  const phase = process.env.SCREENSHOT_PHASE;
  const prefix = phase ? `${phase}-` : '';
  const filepath = `screenshots/${prefix}inline-create-${name}.png`;
  await page.screenshot({ path: filepath });
  console.log(`  [screenshot] ${filepath}`);
}

// The mini-form container for a given parent field. Scoping to it matters:
// the enclosing CRUD form has its own "Create" submit button.
function miniForm(page: Page, fieldName: string) {
  const id = 'inline-mount-' + fieldName.replace(/[^A-Za-z0-9_-]/g, '-') + '-form';
  return page.locator(`#${id}`);
}

// Choices.js hides the underlying <select>; read its value directly.
async function selectedValue(page: Page, name: string): Promise<string> {
  return page.evaluate(
    (n) => {
      const el = document.querySelector(`select[name="${n}"]`) as HTMLSelectElement | null;
      return el ? el.value : '';
    },
    name
  );
}

async function selectedLabel(page: Page, name: string): Promise<string> {
  return page.evaluate(
    (n) => {
      const el = document.querySelector(`select[name="${n}"]`) as HTMLSelectElement | null;
      if (!el || el.selectedIndex < 0) return '';
      return el.options[el.selectedIndex].text;
    },
    name
  );
}

async function testGenericCrudPath(page: Page) {
  console.log('\n2. Generic CRUD path: exercise-line -> exercise');
  await page.goto(`${BASE_URL}/app/crud/form/exercise-line/new`);
  await page.waitForLoadState('networkidle');
  await captureScreenshot(page, '01-line-form');

  // Fill an unrelated field first — it must survive the inline create.
  const repsInput = page.locator('input[name="exercise-line/reps"]');
  await expect(repsInput).toBeVisible({ timeout: 10000 });
  await repsInput.fill('12');
  console.log('  [+] Filled reps = 12 (must survive)');

  const exerciseName = `Inline Movement ${Date.now()}`;

  // Open the mini-form. Exact match matters: this form also has a
  // "+ New exercise set" button, which a substring match would hit first.
  const newButton = page.getByRole('button', { name: '+ New exercise', exact: true });
  await expect(newButton).toBeVisible({ timeout: 10000 });
  await newButton.click();
  await page.waitForTimeout(700);
  await captureScreenshot(page, '02-miniform-open');

  const labelInput = page.locator('input[name="exercise/label"]');
  await expect(labelInput).toBeVisible({ timeout: 10000 });
  console.log('  [✓] Mini-form opened in place');

  // We must still be on the exercise-line form — no bounce.
  if (!page.url().includes('/app/crud/form/exercise-line/new')) {
    throw new Error(`Bounced away from the line form: ${page.url()}`);
  }
  console.log('  [✓] No page bounce');

  await labelInput.fill(exerciseName);
  await miniForm(page, 'exercise-line/exercise-id')
    .getByRole('button', { name: 'Create', exact: true })
    .click();
  await page.waitForTimeout(1200);
  await captureScreenshot(page, '03-after-create');

  // The new exercise must be selected in the parent select.
  const label = await selectedLabel(page, 'exercise-line/exercise-id');
  const value = await selectedValue(page, 'exercise-line/exercise-id');
  console.log(`  [i] Selected: "${label}" (${value.slice(0, 8)}…)`);
  if (label !== exerciseName) {
    throw new Error(`Expected "${exerciseName}" auto-selected, got "${label}"`);
  }
  if (!value) throw new Error('Select has no value — auto-select failed');
  console.log('  [✓] New exercise auto-selected');

  // The mini-form must be gone.
  if (await page.locator('input[name="exercise/label"]').count()) {
    throw new Error('Mini-form still present after successful create');
  }
  console.log('  [✓] Mini-form cleared');

  // Form state preserved.
  const reps = await repsInput.inputValue();
  if (reps !== '12') {
    throw new Error(`Form state lost: reps is "${reps}", expected "12"`);
  }
  console.log('  [✓] Unrelated field retained its value (no state loss)');

  // Choices.js must be re-initialized on the swapped-in select.
  const enhanced = await page.evaluate(() => {
    const el = document.querySelector('select[name="exercise-line/exercise-id"]') as HTMLElement | null;
    return el?.dataset.choicesInitialized === 'true';
  });
  if (!enhanced) throw new Error('Choices.js was not re-initialized after the swap');
  console.log('  [✓] Choices.js re-initialized on the new select');
}

async function testValidationFailure(page: Page) {
  console.log('\n3. Validation failure re-renders without creating');
  await page.goto(`${BASE_URL}/app/crud/form/exercise-line/new`);
  await page.waitForLoadState('networkidle');

  await page.getByRole('button', { name: '+ New exercise', exact: true }).click();
  await page.waitForTimeout(700);

  const labelInput = page.locator('input[name="exercise/label"]');
  await expect(labelInput).toBeVisible({ timeout: 10000 });

  // Submit with an empty label — exercise/label is required.
  await labelInput.fill('');
  await miniForm(page, 'exercise-line/exercise-id')
    .getByRole('button', { name: 'Create', exact: true })
    .click();
  await page.waitForTimeout(1200);
  await captureScreenshot(page, '04-validation-error');

  // Mini-form must still be there (user stays in place).
  const stillOpen = await page.locator('input[name="exercise/label"]').count();
  if (!stillOpen) {
    throw new Error('Mini-form disappeared on validation failure — should stay open');
  }
  console.log('  [✓] Mini-form stayed open on invalid input');

  const body = await page.locator('body').innerText();
  if (!/label/i.test(body)) {
    throw new Error('Expected a field-level error mentioning the label');
  }
  console.log('  [✓] Field-level error surfaced');
}

async function testTimerEntityPath(page: Page) {
  console.log('\n4. Timer-entity path: project-log -> project');
  await page.goto(`${BASE_URL}/app/crud/form/project-log/new`);
  await page.waitForLoadState('networkidle');

  const projectName = `Inline Project ${Date.now()}`;

  const newButton = page.getByRole('button', { name: '+ New project', exact: true });
  await expect(newButton).toBeVisible({ timeout: 10000 });
  await newButton.click();
  await page.waitForTimeout(700);

  const labelInput = page.locator('input[name="project/label"]');
  await expect(labelInput).toBeVisible({ timeout: 10000 });
  await labelInput.fill(projectName);
  await miniForm(page, 'project-log/project-id')
    .getByRole('button', { name: 'Create', exact: true })
    .click();
  await page.waitForTimeout(1200);
  await captureScreenshot(page, '05-project-created');

  const label = await selectedLabel(page, 'project-log/project-id');
  if (label !== projectName) {
    throw new Error(`Expected "${projectName}" auto-selected, got "${label}"`);
  }
  console.log('  [✓] New project auto-selected on the project-log form');
}

async function testSearchEmptyBridge(page: Page) {
  console.log('\n5. Search-empty bridge: + Create "<typed text>"');
  await page.goto(`${BASE_URL}/app/crud/form/exercise-line/new`);
  await page.waitForLoadState('networkidle');
  await page.waitForTimeout(800); // Choices.js init

  const trigger = page.locator(
    '#rel-field-exercise-line-exercise-id button[hx-get*="/app/crud/inline/"]'
  );
  const before = (await trigger.textContent())?.trim();
  console.log(`  [i] Trigger before search: "${before}"`);

  // Open the Choices dropdown and type something that matches nothing.
  const typed = `Zzz Nonexistent ${Date.now()}`;
  await page.locator('#rel-field-exercise-line-exercise-id .choices').click();
  await page.waitForTimeout(300);
  await page.keyboard.type(typed);
  await page.waitForTimeout(600);
  await captureScreenshot(page, '06-search-empty');

  const after = (await trigger.textContent())?.trim();
  console.log(`  [i] Trigger after search: "${after}"`);
  if (after !== `+ Create "${typed}"`) {
    throw new Error(`Expected '+ Create "${typed}"', got "${after}"`);
  }
  console.log('  [✓] Trigger became a create-from-search affordance');

  // And it must carry the typed text through as the label prefill.
  const vals = await trigger.getAttribute('hx-vals');
  if (!vals || !JSON.parse(vals).label) {
    throw new Error(`hx-vals missing label prefill: ${vals}`);
  }
  console.log('  [✓] Typed text passed through as label prefill');

  // The open dropdown overlays the button, so close it first — and the
  // create affordance must survive that close.
  await page.keyboard.press('Escape');
  await page.waitForTimeout(400);
  const afterClose = (await trigger.textContent())?.trim();
  if (afterClose !== `+ Create "${typed}"`) {
    throw new Error(
      `Affordance lost when the dropdown closed: "${afterClose}" — the user ` +
      `has to close it to reach the button`
    );
  }
  console.log('  [✓] Affordance survives closing the dropdown');

  await trigger.click();
  await page.waitForTimeout(900);
  const labelValue = await page.locator('input[name="exercise/label"]').inputValue();
  if (labelValue !== typed) {
    throw new Error(`Mini-form label should be prefilled with "${typed}", got "${labelValue}"`);
  }
  console.log('  [✓] Mini-form opened with the label pre-populated');
  await captureScreenshot(page, '07-prefilled-miniform');
}

async function testWorkoutPicker(page: Page) {
  console.log('\n6. Workout screen picker uses the shared component');
  await page.goto(`${BASE_URL}/app/exercise/session`);
  await page.waitForLoadState('networkidle');

  // The picker only exists inside a running session.
  const startSession = page.getByRole('button', { name: 'Start session', exact: true });
  if (await startSession.count()) {
    await startSession.click();
    await page.waitForLoadState('networkidle');
    console.log('  [+] Started a session');
  }

  // Reveal the log form (it is hidden until a set runs or backfill is toggled).
  const backfill = page.locator('#wk-backfill-toggle');
  if (await backfill.count()) {
    await backfill.click();
    await page.waitForTimeout(400);
  }
  await captureScreenshot(page, '08-workout-screen');

  const trigger = page.locator('#wk-form-card button[hx-get*="/app/crud/inline/exercise"]');
  if (!(await trigger.count())) {
    throw new Error('Workout log form has no shared inline-create affordance');
  }
  console.log('  [✓] Workout picker renders the shared inline-create trigger');

  // And it must be the shared container, so the swap target exists.
  const container = page.locator('#rel-field-exercise-id');
  if (!(await container.count())) {
    throw new Error('Workout picker is not wrapped in the shared swap container');
  }
  console.log('  [✓] Shared swap container present (#rel-field-exercise-id)');
}

async function main() {
  console.log('\n=== Inline Entity Creation Test ===');

  const browser = await chromium.launch({ headless: true });
  const page = await browser.newPage({ viewport: { width: 1280, height: 900 } });

  try {
    // Surface server-side failures instead of timing out on a missing element.
    page.on('response', (r) => {
      if (r.url().includes('/app/crud/inline/') && r.status() >= 400) {
        console.error(`  [!] ${r.request().method()} ${r.url()} -> ${r.status()}`);
      }
    });
    page.on('pageerror', (e) => console.error(`  [!] page error: ${e.message}`));

    const email = `e2e-inline-create-${Date.now()}@localhost`;
    console.log('\n1. Authenticating...');
    await authenticateForDev(page, email);
    console.log(`  [+] ${email}`);

    await testGenericCrudPath(page);
    await testValidationFailure(page);
    await testTimerEntityPath(page);
    await testSearchEmptyBridge(page);
    await testWorkoutPicker(page);

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
