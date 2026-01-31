// Usage: npm run flow -- flow-name

import { chromium, Page } from '@playwright/test';
import { authenticateForDev } from './auth.js';

const BASE_URL = process.env.BASE_URL || 'http://localhost:8080';

async function capture(page: Page, label: string) {
  const filepath = `screenshots/flow-${label}.png`;
  await page.screenshot({ path: filepath });
  console.log(`  [${label}] ${filepath}`);
}

async function exampleFlow(page: Page) {
  await page.goto(`${BASE_URL}/app`);
  await capture(page, '01-app-home');

  // Add more steps as needed for specific flows
}

async function main() {
  const args = process.argv.slice(2);
  const flowName = args[0] || 'example';

  const browser = await chromium.launch({ headless: true });
  const page = await browser.newPage({ viewport: { width: 1280, height: 720 } });

  await authenticateForDev(page);

  switch (flowName) {
    case 'example':
      await exampleFlow(page);
      break;
    default:
      console.log(`Unknown flow: ${flowName}`);
  }

  await browser.close();
}

main();
