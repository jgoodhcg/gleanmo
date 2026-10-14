import { readFileSync } from 'node:fs';
import { Page } from '@playwright/test';

const BASE_URL = process.env.BASE_URL || 'http://localhost:8080';

// The dev server reads E2E_LOGIN_TOKEN from config.env; read the same file so
// there is one place to set it. An env var of the same name wins.
function loginToken(): string {
  if (process.env.E2E_LOGIN_TOKEN) return process.env.E2E_LOGIN_TOKEN;
  try {
    const env = readFileSync(new URL('../../config.env', import.meta.url), 'utf8');
    const match = env.match(/^E2E_LOGIN_TOKEN=["']?([^"'\s]+)["']?\s*$/m);
    if (match) return match[1];
  } catch {
    // fall through to the error below
  }
  throw new Error('E2E_LOGIN_TOKEN is not set in config.env (see resources/config.template.env)');
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
