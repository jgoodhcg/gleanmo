// Paired before/after screenshots for the page-shell conversion.
//   SCREENSHOT_PHASE=before npm run shot:pages
//   ... convert ...
//   SCREENSHOT_PHASE=after  npm run shot:pages
//
// Captures every page whose shell is being changed, at both widths, so the
// width/heading differences are directly comparable frame to frame.

import { chromium, Page } from '@playwright/test';
import { authenticateForDev } from './auth.js';

const BASE_URL = process.env.BASE_URL || 'http://localhost:8080';
const EMAIL = process.env.E2E_EMAIL || 'justin@jgood.online';
const PHASE = process.env.SCREENSHOT_PHASE || 'now';

// Pages whose shells are converted, plus a couple already on the shell as a
// control (they should not move between phases).
const PAGES: [string, string][] = [
  ['/app/crud/form/habit-log/new', 'form-habit-log-new'],
  ['/app/crud/form/boulder-problem/new', 'form-boulder-problem-new'],
  ['/app/crud/habit', 'list-habit'],
  ['/app/crud/boulder-problem', 'list-boulder-problem'],
  ['/app/boulder/session', 'boulder-session'],
  ['/app/boulder/problems', 'boulder-problems'],
  ['/app/exercise/session', 'workout'],
  ['/app/stats/medication-history', 'medication-history'],
  ['/app/viz/habit-log', 'viz-habit-log'],
  // controls — already converted, expected identical across phases
  ['/app/dashboards/entities', 'control-entities'],
  ['/app/timers', 'control-timers'],
];

async function shoot(page: Page, label: string, name: string) {
  for (const [path, slug] of PAGES) {
    await page.goto(`${BASE_URL}${path}`, { waitUntil: 'networkidle' });
    await page.waitForTimeout(900);
    await page.screenshot({
      path: `screenshots/${PHASE}-${label}-${slug}.png`,
      fullPage: false,
    });
  }
  console.log(`  captured ${PAGES.length} pages at ${name}`);
}

async function main() {
  const browser = await chromium.launch({ headless: true });

  const mobile = await browser.newPage({
    viewport: { width: 390, height: 844 }, isMobile: true, hasTouch: true,
  });
  await authenticateForDev(mobile, EMAIL);
  await shoot(mobile, 'mobile', 'mobile 390x844');
  await mobile.close();

  const desktop = await browser.newPage({ viewport: { width: 1280, height: 900 } });
  await authenticateForDev(desktop, EMAIL);
  await shoot(desktop, 'desktop', 'desktop 1280x900');
  await desktop.close();

  await browser.close();
  console.log(`\nphase: ${PHASE}`);
}

main().catch((e) => { console.error(e); process.exit(1); });
