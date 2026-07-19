import { chromium } from '@playwright/test';
import { authenticateForDev } from './auth.js';
const BASE_URL = process.env.BASE_URL || 'http://localhost:8080';
async function main() {
  const browser = await chromium.launch({ headless: true });
  const page = await browser.newPage({ viewport: { width: 390, height: 1000 } });
  await authenticateForDev(page, 'justin@jgood.online');
  await page.goto(`${BASE_URL}/app/boulder/session`);
  await page.click('[data-wall-chip="right-prow"]');
  await page.screenshot({ path: 'screenshots/picker-wall-filtered.png', fullPage: true });
  await page.click('button:has-text("End session")');
  await page.waitForLoadState('networkidle');
  await page.goto(`${BASE_URL}/app/crud/boulder-session?view=list`);
  page.once('dialog', d => d.accept());
  await page.click('text=Delete');
  await page.waitForTimeout(1500);
  await page.goto(`${BASE_URL}/app/boulder/session`);
  await page.screenshot({ path: 'screenshots/picker-after-cleanup.png' });
  await browser.close();
}
main();
