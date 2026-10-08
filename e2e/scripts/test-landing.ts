// E2E test for the public landing page
// Usage: npm run test:landing
//
// This test:
// 1. Loads / without signing in, at desktop and mobile widths
// 2. Verifies the sign-in and sign-up links
// 3. Verifies the page does not scroll sideways

import { chromium, Page } from '@playwright/test';

const BASE_URL = process.env.BASE_URL || 'http://localhost:8080';

async function captureScreenshot(page: Page, name: string) {
  const phase = process.env.SCREENSHOT_PHASE;
  const prefix = phase ? `${phase}-` : '';
  const filepath = `screenshots/${prefix}landing-${name}.png`;
  await page.screenshot({ path: filepath, fullPage: true });
  console.log(`  [screenshot] ${filepath}`);
}

function check(condition: boolean, message: string) {
  if (!condition) throw new Error(message);
  console.log(`  [ok] ${message}`);
}

async function checkPage(page: Page, name: string) {
  const response = await page.goto(`${BASE_URL}/`);
  await page.waitForLoadState('networkidle');
  check(response?.status() === 200, `${name}: landing page loads`);
  check(new URL(page.url()).pathname === '/', `${name}: anonymous visit stays on /`);
  await captureScreenshot(page, name);

  check(await page.locator('a[href="/signin"]').count() > 0, `${name}: sign-in link`);
  check(await page.locator('a[href="/signup"]').count() > 0, `${name}: sign-up link`);

  const overflow = await page.evaluate(
    () => document.documentElement.scrollWidth - window.innerWidth,
  );
  check(overflow <= 0, `${name}: no horizontal scroll (${overflow}px)`);
}

async function main() {
  console.log('\n=== Landing Page Test ===\n');

  const browser = await chromium.launch({ headless: true });

  try {
    const desktop = await browser.newPage({ viewport: { width: 1280, height: 900 } });
    await checkPage(desktop, '01-desktop');

    const mobile = await browser.newPage({ viewport: { width: 390, height: 844 } });
    await checkPage(mobile, '02-mobile');

    console.log('\n=== Landing page test passed ===\n');
  } catch (error) {
    console.error('\nTest failed:', error);
    process.exitCode = 1;
  } finally {
    await browser.close();
  }
}

main();
