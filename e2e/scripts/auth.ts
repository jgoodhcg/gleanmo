import { Page } from '@playwright/test';

const BASE_URL = process.env.BASE_URL || 'http://localhost:8080';

async function authenticateAt(page: Page, path: string) {
  const response = await page.goto(`${BASE_URL}${path}`);
  const status = response?.status() ?? 0;
  if (status === 0 || status >= 400) {
    throw new Error(`Authentication failed: HTTP ${status} at ${page.url()}`);
  }
  await page.waitForURL('**/app**');
}

export async function authenticateForDev(page: Page, email = 'e2e-test@localhost') {
  await authenticateAt(page, `/auth/e2e-login?email=${encodeURIComponent(email)}`);
}

export async function authenticateTimelineForDev(
  page: Page,
  email = 'e2e-series@localhost',
) {
  await authenticateAt(page, `/auth/e2e-seed-series?email=${encodeURIComponent(email)}`);
}
