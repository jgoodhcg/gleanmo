import { readFileSync } from 'node:fs';
import { Page } from '@playwright/test';

const BASE_URL = process.env.BASE_URL || 'http://localhost:8080';

// The dev server writes its e2e sign-in token here at startup
// (dev/tech/jgood/gleanmo/e2e_auth.clj), so there is nothing to configure.
function loginToken(): string {
  try {
    return readFileSync(new URL('../../target/e2e-login-token', import.meta.url), 'utf8').trim();
  } catch {
    throw new Error('No target/e2e-login-token: start the dev server (biff dev) in this checkout first');
  }
}

async function authenticateAt(page: Page, path: string) {
  // `domcontentloaded`, not the default `load`: this is an auth redirect, and
  // waiting on every subresource made a 3ms route time out at 30s when one
  // asset stalled. The waitForURL below is the real readiness signal.
  const sep = path.includes('?') ? '&' : '?';
  const url = `${BASE_URL}${path}${sep}token=${encodeURIComponent(loginToken())}`;
  const response = await page.goto(url, {
    waitUntil: 'domcontentloaded',
  });
  const status = response?.status() ?? 0;
  if (status === 0 || status >= 400) {
    const body = (await response?.text())?.slice(0, 200) ?? '';
    throw new Error(`Authentication failed: HTTP ${status}: ${body}`);
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
