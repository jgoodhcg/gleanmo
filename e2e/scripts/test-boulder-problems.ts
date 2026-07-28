// E2E: the boulder problems management screen.
//
// Covers create, retire/restore, and that retired problems are ordered by when
// they came off the wall rather than when they were created. Asserts on
// problem identity and section membership, not on layout, so restyling the
// rows does not break it.
//
// Usage: npm run test:boulder-problems

import { chromium, Page, expect } from '@playwright/test';
import { authenticateForDev } from './auth.js';

const BASE_URL = process.env.BASE_URL || 'http://localhost:8080';
const PROBLEMS = `${BASE_URL}/app/boulder/problems`;

async function createProblem(page: Page, difficulty: string) {
  await page.goto(`${BASE_URL}/app/crud/form/boulder-problem/new`, { waitUntil: 'networkidle' });
  const form = page.locator('#boulder-problem-new-form');
  await form.locator('[name="boulder-problem/gym"]').fill('E2E Gym');
  await form.locator('[name="boulder-problem/difficulty"]').fill(difficulty);
  await form.evaluate((f: HTMLFormElement) => {
    f.setAttribute('action', f.getAttribute('hx-post') || '/app/crud/boulder-problem');
    f.setAttribute('method', 'POST');
  });
  await form.evaluate((f: HTMLFormElement) => f.submit());
  await page.waitForLoadState('networkidle');
  await page.waitForTimeout(300);
}

// A problem row is the smallest ancestor of a toggle form that still contains
// exactly one such form — bounding the walk this way keeps the helpers working
// if the row markup is restyled, without ever climbing into the whole list.
const ROW_JS = `
  function rowOf(form) {
    var el = form;
    while (el.parentElement) {
      el = el.parentElement;
      if (el.querySelectorAll('form[action*="toggle-inactive"]').length > 1) break;
      if (/E2E-\\w+/.test(el.textContent)) return el;
    }
    return null;
  }
  function labelOf(el) {
    var m = el.textContent.match(/E2E-(first|second|third)/);
    return m ? m[0] : null;
  }`;

/** Our test problems in one section, in render order. Sections are identified
 *  by the action their rows offer — active rows retire, retired rows restore —
 *  rather than by walking down from a heading. */
async function section(page: Page, kind: 'active' | 'retired'): Promise<string[]> {
  const action = kind === 'active' ? 'retire' : 'restore';
  return page.evaluate(`(function () {
    ${ROW_JS}
    var forms = document.querySelectorAll('form[action*="toggle-inactive"]');
    var out = [];
    for (var i = 0; i < forms.length; i++) {
      var btn = forms[i].querySelector('button');
      if (!btn || btn.textContent.trim().toLowerCase() !== '${action}') continue;
      var row = rowOf(forms[i]);
      var label = row && labelOf(row);
      if (label) out.push(label);
    }
    return out;
  })()`) as Promise<string[]>;
}

/** Click the retire/restore button belonging to one specific problem. */
async function toggle(page: Page, label: string, action: 'retire' | 'restore') {
  const clicked = await page.evaluate(`(function () {
    ${ROW_JS}
    var forms = document.querySelectorAll('form[action*="toggle-inactive"]');
    for (var i = 0; i < forms.length; i++) {
      var btn = forms[i].querySelector('button');
      if (!btn || btn.textContent.trim().toLowerCase() !== '${action}') continue;
      var row = rowOf(forms[i]);
      if (row && labelOf(row) === '${label}') { btn.click(); return true; }
    }
    return false;
  })()`);
  if (!clicked) throw new Error(`no "${action}" button found for ${label}`);
  await page.waitForLoadState('networkidle');
  await page.waitForTimeout(400);
}

async function main() {
  console.log('\n=== Boulder Problems Test ===');
  const browser = await chromium.launch({ headless: true });
  const page = await browser.newPage({ viewport: { width: 1280, height: 1000 } });

  try {
    await authenticateForDev(page, `e2e-boulder-problems-${Date.now()}@localhost`);

    // 1. Create affordance exists — the screen manages problems, so it must
    //    also be able to add one.
    console.log('\n1. Create affordance');
    await page.goto(PROBLEMS, { waitUntil: 'networkidle' });
    await expect(
      page.locator('a[href*="/app/crud/form/boulder-problem/new"]').first()
    ).toBeVisible();
    console.log('  [✓] "+ New problem" present');

    // 2. Create three, retire them in a known order.
    console.log('\n2. Create and retire');
    for (const d of ['E2E-first', 'E2E-second', 'E2E-third']) {
      await createProblem(page, d);
    }
    await page.goto(PROBLEMS, { waitUntil: 'networkidle' });
    const active = await section(page, 'active');
    if (active.length !== 3) throw new Error(`expected 3 active, got ${active.length}`);
    console.log('  [✓] three problems on the wall');

    // 3. Each retire puts that problem at the head of the retired list.
    //    Asserting the invariant rather than one hardcoded permutation, so the
    //    test says what the feature promises: retired is ordered by *when it
    //    came off the wall*, not by when it was created.
    console.log('\n3. Retired ordering follows retirement, not creation');
    const retiredInOrder: string[] = [];
    for (const label of ['E2E-second', 'E2E-first', 'E2E-third']) {
      await toggle(page, label, 'retire');
      retiredInOrder.unshift(label);          // newest first
      await page.goto(PROBLEMS, { waitUntil: 'networkidle' });
      const shown = await section(page, 'retired');
      if (JSON.stringify(shown) !== JSON.stringify(retiredInOrder)) {
        throw new Error(
          `after retiring ${label}, expected ${JSON.stringify(retiredInOrder)} ` +
          `but the page showed ${JSON.stringify(shown)}`
        );
      }
      console.log(`  [✓] retired ${label} -> ${JSON.stringify(shown)}`);
    }
    // Creation order was first, second, third — so a list that still reads in
    // creation order would mean the sort is not doing anything.
    if (JSON.stringify(retiredInOrder) === JSON.stringify(['E2E-first', 'E2E-second', 'E2E-third'])) {
      throw new Error('retirement order coincided with creation order — test proves nothing');
    }

    // 4. Restore puts one back on the wall.
    console.log('\n4. Restore');
    await toggle(page, 'E2E-second', 'restore');
    await page.goto(PROBLEMS, { waitUntil: 'networkidle' });
    const backOn = await section(page, 'active');
    if (!backOn.includes('E2E-second')) {
      throw new Error('restore did not return the problem to the wall');
    }
    console.log('  [✓] restored back onto the wall');

    console.log('\n=== Test Passed ===\n');
  } catch (error) {
    console.error('\n=== Test Failed ===');
    console.error(error);
    await page.screenshot({ path: 'screenshots/boulder-problems-error.png' });
    process.exit(1);
  } finally {
    await browser.close();
  }
}

main();
