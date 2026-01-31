// Usage: npm run screenshot -- /app/habits --full

import { chromium } from '@playwright/test';
import { authenticateForDev } from './auth.js';

const BASE_URL = process.env.BASE_URL || 'http://localhost:8080';

async function screenshot() {
  const args = process.argv.slice(2);
  const path = args.find(a => a.startsWith('/')) || '/app';
  const fullPage = args.includes('--full');

  const browser = await chromium.launch({ headless: true });
  const page = await browser.newPage({ viewport: { width: 1280, height: 720 } });

  // Authenticate first
  await authenticateForDev(page);

  // Navigate to target page
  await page.goto(`${BASE_URL}${path}`, { waitUntil: 'networkidle' });

  const filename = `screenshot-${Date.now()}.png`;
  await page.screenshot({ path: `screenshots/${filename}`, fullPage });
  console.log(`Screenshot saved: screenshots/${filename}`);

  await browser.close();
}

screenshot();
