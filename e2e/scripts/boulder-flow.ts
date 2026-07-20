// One-off verification of the bouldering session flow.
// Usage: npx tsx scripts/boulder-flow.ts

import { chromium, Page } from '@playwright/test';
import { authenticateForDev } from './auth.js';

const BASE_URL = process.env.BASE_URL || 'http://localhost:8080';

async function capture(page: Page, label: string) {
  const filepath = `screenshots/boulder-${label}.png`;
  await page.screenshot({ path: filepath, fullPage: true });
  console.log(`  [${label}] ${filepath}`);
}

async function main() {
  const browser = await chromium.launch({ headless: true });
  const page = await browser.newPage({ viewport: { width: 390, height: 844 } });
  await authenticateForDev(page, 'e2e-boulder@localhost');

  await page.goto(`${BASE_URL}/app/boulder/session`);
  await capture(page, '01-idle');

  // start a session
  await page.fill('input[name=gym]', 'Terra Firma');
  await page.click('button:has-text("Start session")');
  await page.waitForLoadState('networkidle');
  await capture(page, '02-active-empty');

  // start the attempt timer, then log with a new inline problem
  await page.click('button:has-text("Start attempt")');
  await page.waitForLoadState('networkidle');
  await capture(page, '02b-attempt-recording');
  await page.click('#bd-picker-toggle');
  await page.click('[data-problem-card="__new__"]');
  await page.fill('input[name=new-difficulty]', 'pink v0-v2');
  await page.fill('input[name=new-hold-color]', 'pink');
  await page.fill('input[name=new-wall]', 'comp');
  await page.click('[data-toggle-chip=flash]'); // should also flip sent
  await page.click('button:has-text("Log attempt")');
  await page.waitForLoadState('networkidle');
  await capture(page, '03-after-first-attempt');

  // second attempt logged without starting the timer (backfill path),
  // on the same (now preselected) problem, with extra tries
  await page.click('#bd-backfill-toggle');
  await page.click('[data-adjust="attempts:1"]');
  await page.click('[data-adjust="attempts:1"]');
  await page.click('button:has-text("Log attempt")');
  await page.waitForLoadState('networkidle');
  await capture(page, '04-after-second-attempt');

  // end session, view summary
  await page.click('button:has-text("End session")');
  await page.waitForLoadState('networkidle');
  await capture(page, '05-idle-after-end');
  await page.click('a:has-text("Terra Firma")');
  await page.waitForLoadState('networkidle');
  await capture(page, '06-summary');

  await browser.close();
}

main();
