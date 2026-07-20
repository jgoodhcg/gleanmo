import { chromium } from '@playwright/test';
import { authenticateForDev } from './auth.js';
const BASE_URL = process.env.BASE_URL || 'http://localhost:8080';
async function main() {
  const browser = await chromium.launch({ headless: true });
  const page = await browser.newPage({ viewport: { width: 390, height: 900 } });
  await authenticateForDev(page, 'e2e-boulder@localhost');
  await page.goto(`${BASE_URL}/app/boulder/session/problems`);
  const before = await page.locator('button:has-text("retire")').count();
  await page.click('button:has-text("retire")');
  await page.waitForLoadState('networkidle');
  const afterRetire = await page.locator('button:has-text("retire")').count();
  const restored = await page.locator('button:has-text("restore")').count();
  console.log({ before, afterRetire, restoreButtons: restored });
  await page.click('button:has-text("restore")');
  await page.waitForLoadState('networkidle');
  console.log({ afterRestore: await page.locator('button:has-text("retire")').count() });
  await browser.close();
}
main();
