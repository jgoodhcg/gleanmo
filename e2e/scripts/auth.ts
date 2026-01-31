import { Page } from '@playwright/test';

const BASE_URL = process.env.BASE_URL || 'http://localhost:8080';

export async function authenticateForDev(page: Page, email = 'e2e-test@localhost') {
  await page.goto(`${BASE_URL}/auth/e2e-login?email=${encodeURIComponent(email)}`);
  await page.waitForURL('**/app**');
}
