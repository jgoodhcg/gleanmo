// E2E: :crud/suggest-existing renders a free-text field wired to a datalist
// of values the user has already stored.
//
// Asserts the wiring (input -> list -> options) and that free entry still
// works, since the whole point is an open vocabulary. Does not assert on
// styling, and does not hardcode which values appear — only that values the
// user created show up.
//
// Usage: npm run test:field-suggestions

import { chromium, Page, expect } from '@playwright/test';
import { authenticateForDev } from './auth.js';

const BASE_URL = process.env.BASE_URL || 'http://localhost:8080';

async function createProblem(page: Page, gym: string, wall: string) {
  await page.goto(`${BASE_URL}/app/crud/form/boulder-problem/new`, { waitUntil: 'networkidle' });
  const form = page.locator('#boulder-problem-new-form');
  await form.locator('[name="boulder-problem/gym"]').fill(gym);
  await form.locator('[name="boulder-problem/difficulty"]').fill('E2E circuit');
  await form.locator('[name="boulder-problem/wall"]').fill(wall);
  await form.evaluate((f: HTMLFormElement) => {
    f.setAttribute('action', f.getAttribute('hx-post') || '/app/crud/boulder-problem');
    f.setAttribute('method', 'POST');
  });
  await form.evaluate((f: HTMLFormElement) => f.submit());
  await page.waitForLoadState('networkidle');
  await page.waitForTimeout(300);
}

async function main() {
  console.log('\n=== Field Suggestions Test ===');
  const browser = await chromium.launch({ headless: true });
  const page = await browser.newPage({ viewport: { width: 1280, height: 900 } });

  try {
    await authenticateForDev(page, `e2e-suggestions-${Date.now()}@localhost`);

    // A fresh user has no history, so the field must still be usable with an
    // empty (or absent) suggestion list — this is the open-vocabulary case.
    console.log('\n1. Free entry with no history');
    await page.goto(`${BASE_URL}/app/crud/form/boulder-problem/new`, { waitUntil: 'networkidle' });
    const wall = page.locator('[name="boulder-problem/wall"]');
    await expect(wall).toBeVisible();
    const tag = await wall.evaluate((el) => el.tagName);
    if (tag !== 'INPUT') {
      throw new Error(`flagged field should be a single-line input, got <${tag.toLowerCase()}>`);
    }
    await wall.fill('Brand New Wall');
    console.log('  [✓] renders an input and accepts a value with no suggestions');

    // 2. After storing values, they come back as suggestions.
    console.log('\n2. Stored values become suggestions');
    await createProblem(page, 'E2E Suggest Gym', 'E2E Overhang');
    await createProblem(page, 'E2E Suggest Gym', 'E2E Slab');

    await page.goto(`${BASE_URL}/app/crud/form/boulder-problem/new`, { waitUntil: 'networkidle' });

    for (const [field, expected] of [
      ['boulder-problem/wall', ['E2E Overhang', 'E2E Slab']],
      ['boulder-problem/gym', ['E2E Suggest Gym']],
    ] as [string, string[]][]) {
      const listId = await page.locator(`[name="${field}"]`).getAttribute('list');
      if (!listId) throw new Error(`${field}: no list attribute`);

      const options = await page.locator(`#${listId} option`).evaluateAll(
        (els) => els.map((e) => (e as HTMLOptionElement).value)
      );
      for (const v of expected) {
        if (!options.includes(v)) {
          throw new Error(`${field}: expected "${v}" among suggestions, got ${JSON.stringify(options)}`);
        }
      }
      console.log(`  [✓] ${field} suggests ${JSON.stringify(expected)}`);
    }

    // 3. A value not in the list is still accepted — suggestions must not
    //    constrain, or the schema being :string would be pointless.
    console.log('\n3. Open vocabulary preserved');
    const w = page.locator('[name="boulder-problem/wall"]');
    await w.fill('Totally Unseen Wall');
    if ((await w.inputValue()) !== 'Totally Unseen Wall') {
      throw new Error('field rejected a value outside its suggestions');
    }
    console.log('  [✓] accepts values outside the suggestion list');

    console.log('\n=== Test Passed ===\n');
  } catch (error) {
    console.error('\n=== Test Failed ===');
    console.error(error);
    await page.screenshot({ path: 'screenshots/field-suggestions-error.png' });
    process.exit(1);
  } finally {
    await browser.close();
  }
}

main();
