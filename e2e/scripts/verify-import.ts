import { chromium } from '@playwright/test';
import { authenticateForDev } from './auth.js';
const BASE_URL = process.env.BASE_URL || 'http://localhost:8080';
async function main() {
  const browser = await chromium.launch({ headless: true });
  const page = await browser.newPage({ viewport: { width: 390, height: 900 } });
  await authenticateForDev(page, 'justin@jgood.online');
  await page.goto(`${BASE_URL}/app/boulder/session`);
  await page.click('a[href*="/summary"]');
  await page.waitForLoadState('networkidle');
  await page.screenshot({ path: 'screenshots/import-session-summary.png', fullPage: true });
  await page.goto(`${BASE_URL}/app/crud/symptom-log`);
  await page.waitForLoadState('networkidle');
  await page.screenshot({ path: 'screenshots/import-symptom-list.png', fullPage: false });
  await browser.close();
}
main();
