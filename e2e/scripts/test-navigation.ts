// E2E: navigation chrome and the shared page shell.
//
// Covers the things the nav redesign introduced and that nothing else guards:
// the mobile tab bar, the sidebar open/close cycle now that the hamburger is
// gone, the /app/log hub, and the two invariants every page must hold — one
// page heading, and no control hidden under the fixed tab bar.
//
// Written against roles, aria labels and hrefs rather than classes or pixel
// positions, so restyling a page does not break it. It asserts that chrome
// *works*, not that it looks a particular way.
//
// Usage: npm run test:navigation

import { chromium, Browser, Page, expect } from '@playwright/test';
import { authenticateForDev } from './auth.js';

const BASE_URL = process.env.BASE_URL || 'http://localhost:8080';

// One route per shell kind: generated form, generated list, custom screen,
// dashboard, stats, and the primary surfaces.
const ROUTES = [
  '/app',
  '/app/log',
  '/app/timers',
  '/app/task/today',
  '/app/crud/form/habit-log/new',
  '/app/crud/habit',
  '/app/boulder/session',
  '/app/boulder/problems',
  '/app/exercise/session',
  '/app/dashboards/entities',
  '/app/stats/medication-history',
];

async function testTabBar(page: Page) {
  console.log('\n2. Mobile tab bar');
  await page.goto(`${BASE_URL}/app/timers`, { waitUntil: 'networkidle' });

  const bar = page.locator('nav[aria-label="Primary"]');
  await expect(bar).toBeVisible();

  const controls = bar.locator('a, button');
  await expect(controls).toHaveCount(5);
  console.log('  [✓] five primary surfaces');

  // The surface you are on is marked — semantic, not a colour assertion.
  const current = bar.locator('[aria-current="page"]');
  await expect(current).toHaveCount(1);
  await expect(current).toHaveAttribute('href', '/app/timers');
  console.log('  [✓] active surface marked with aria-current');

  // Home must match exactly, not by prefix, or it would light up everywhere.
  await page.goto(`${BASE_URL}/app/task/today`, { waitUntil: 'networkidle' });
  const nowCurrent = page.locator('nav[aria-label="Primary"] [aria-current="page"]');
  await expect(nowCurrent).toHaveAttribute('href', '/app/task/today');
  console.log('  [✓] home does not match by prefix');
}

async function testMenuCycle(page: Page) {
  console.log('\n3. Sidebar open/close (no hamburger any more)');
  await page.goto(`${BASE_URL}/app`, { waitUntil: 'networkidle' });

  if (await page.locator('#menu-btn').count()) {
    throw new Error('The removed mobile top bar is back');
  }
  console.log('  [✓] no legacy top bar');

  await page.getByRole('button', { name: 'Open navigation menu' }).click();
  await page.waitForTimeout(300);
  await expect(page.locator('#sidebar')).toBeVisible();
  console.log('  [✓] "more" opens the sidebar');

  // The sidebar replaces page content on mobile, so it must carry its own way
  // out — otherwise opening it is a trap.
  const close = page.getByRole('button', { name: 'Close navigation menu' });
  await expect(close).toBeVisible();
  await close.click();
  await page.waitForTimeout(300);
  await expect(page.locator('#sidebar')).toBeHidden();
  await expect(page.locator('#side-bar-page-content')).toBeVisible();
  console.log('  [✓] close restores the page');
}

async function testLogHub(page: Page) {
  console.log('\n4. /app/log hub');
  await page.goto(`${BASE_URL}/app/log`, { waitUntil: 'networkidle' });
  const links = page.locator('#side-bar-page-content a[href*="/app/"]');
  const n = await links.count();
  if (n < 8) throw new Error(`Log hub should list the logging destinations, found ${n}`);
  console.log(`  [✓] ${n} logging destinations`);
}

async function testShellInvariants(browser: Browser) {
  console.log('\n5. Shell invariants across every kind of page');

  const mobile = await browser.newPage({
    viewport: { width: 375, height: 812 }, isMobile: true, hasTouch: true,
  });
  await authenticateForDev(mobile, `e2e-nav-${Date.now()}@localhost`);

  for (const route of ROUTES) {
    await mobile.goto(`${BASE_URL}${route}`, { waitUntil: 'networkidle' });
    await mobile.waitForTimeout(600);

    // a) exactly one page heading
    const h1s = await mobile.locator('#side-bar-page-content h1').count();
    if (h1s !== 1) {
      throw new Error(`${route}: expected 1 h1 in the content area, found ${h1s}`);
    }

    // b) nothing interactive hidden behind the fixed tab bar
    const occluded = await mobile.evaluate(`(function () {
      window.scrollTo(0, document.body.scrollHeight);
      var bar = document.querySelector('nav[aria-label="Primary"]');
      if (!bar) return 'NO_TAB_BAR';
      var barTop = bar.getBoundingClientRect().top;
      var els = document.querySelectorAll('button, a, input, select, textarea');
      for (var i = 0; i < els.length; i++) {
        var el = els[i];
        if (bar.contains(el)) continue;
        var r = el.getBoundingClientRect();
        if (r.width === 0 && r.height === 0) continue;
        var mid = r.top + r.height / 2;
        if (mid > barTop && r.top < window.innerHeight && r.bottom > 0) {
          return (el.tagName + ' ' + (el.textContent || '').trim()).slice(0, 40);
        }
      }
      return null;
    })()`);
    if (occluded) {
      throw new Error(`${route}: "${occluded}" sits under the tab bar`);
    }
    console.log(`  [✓] ${route}`);
  }
  await mobile.close();
}

async function main() {
  console.log('\n=== Navigation & Page Shell Test ===');
  const browser = await chromium.launch({ headless: true });
  const page = await browser.newPage({
    viewport: { width: 375, height: 812 }, isMobile: true, hasTouch: true,
  });

  try {
    console.log('\n1. Authenticating...');
    await authenticateForDev(page, `e2e-navigation-${Date.now()}@localhost`);

    await testTabBar(page);
    await testMenuCycle(page);
    await testLogHub(page);
    await page.close();
    await testShellInvariants(browser);

    console.log('\n=== Test Passed ===\n');
  } catch (error) {
    console.error('\n=== Test Failed ===');
    console.error(error);
    process.exit(1);
  } finally {
    await browser.close();
  }
}

main();
