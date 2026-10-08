// E2E test for API tokens and the agent API
// Usage: npm run test:api-tokens
//
// This test:
// 1. Creates a token granted write on tasks from /app/api-tokens
// 2. Calls the API with it: types, a dry-run and a committed tx, a by-id read,
//    a text search, and an ungranted type
// 3. Verifies requests without the token are refused
// 4. Revokes the token and verifies it stops working

import { chromium, Page, expect } from '@playwright/test';
import { authenticateForDev } from './auth.js';

const BASE_URL = process.env.BASE_URL || 'http://localhost:8080';

async function captureScreenshot(page: Page, name: string) {
  const phase = process.env.SCREENSHOT_PHASE;
  const prefix = phase ? `${phase}-` : '';
  const filepath = `screenshots/${prefix}api-tokens-${name}.png`;
  await page.screenshot({ path: filepath, fullPage: true });
  console.log(`  [screenshot] ${filepath}`);
}

function check(condition: boolean, message: string) {
  if (!condition) throw new Error(message);
  console.log(`  [ok] ${message}`);
}

async function main() {
  console.log('\n=== API Tokens Test ===\n');

  const browser = await chromium.launch({ headless: true });
  const page = await browser.newPage({ viewport: { width: 1280, height: 900 } });

  try {
    const testEmail = 'e2e-api-tokens@localhost';
    const runId = Date.now();
    const tokenLabel = `e2e token ${runId}`;
    const taskLabel = `API task ${runId}`;

    console.log('1. Creating a token...');
    await authenticateForDev(page, testEmail);
    await page.goto(`${BASE_URL}/app/api-tokens`);
    await page.waitForLoadState('networkidle');
    await captureScreenshot(page, '01-desktop');

    await page.fill('#label', tokenLabel);
    await page.check('input[name="group-tasks"][value="write"]');
    await page.click('#api-token-create-form button[type="submit"]');
    await page.waitForLoadState('networkidle');

    const tokenLocator = page.locator('#new-api-token-value');
    await expect(tokenLocator).toBeVisible({ timeout: 10000 });
    const token = ((await tokenLocator.textContent()) || '').trim();
    check(token.startsWith('gmn_'), 'new token is shown once with the gmn_ prefix');
    await captureScreenshot(page, '02-created');

    const api = page.context().request;
    const auth = { Authorization: `Bearer ${token}` };

    console.log('\n2. Calling the API...');
    const types = await api.get(`${BASE_URL}/api/v1/types`, { headers: auth });
    check(types.status() === 200, 'GET /types succeeds with the token');
    const typesBody = await types.json();
    const groups = typesBody.groups.map((g: any) => `${g.group}:${g.access}`);
    check(
      groups.length === 1 && groups[0] === 'tasks:write',
      `token reaches only tasks (got ${groups.join(', ')})`,
    );

    const op = {
      op: 'create',
      type: 'task',
      set: { 'task/label': taskLabel, 'task/state': 'now' },
    };
    const dry = await api.post(`${BASE_URL}/api/v1/tx`, {
      headers: auth,
      data: { dry_run: true, ops: [op] },
    });
    const dryBody = await dry.json();
    check(dry.status() === 200 && dryBody.valid === true, 'dry run validates the batch');

    const searchBefore = await api.get(
      `${BASE_URL}/api/v1/entities/task?q=${encodeURIComponent(taskLabel)}`,
      { headers: auth },
    );
    check((await searchBefore.json()).count === 0, 'dry run wrote nothing');

    const commit = await api.post(`${BASE_URL}/api/v1/tx`, {
      headers: auth,
      data: { ops: [op] },
    });
    const commitBody = await commit.json();
    check(commit.status() === 200 && commitBody.committed === true, 'tx commits');
    const taskId = commitBody.ops[0].id;

    const one = await api.get(`${BASE_URL}/api/v1/entities/task/${taskId}`, {
      headers: auth,
    });
    check(
      one.status() === 200 && (await one.json())['task/label'] === taskLabel,
      'created task reads back by id',
    );

    const search = await api.get(
      `${BASE_URL}/api/v1/entities/task?q=${encodeURIComponent(taskLabel)}`,
      { headers: auth },
    );
    check((await search.json()).count === 1, 'text search finds the task');

    const mood = await api.get(`${BASE_URL}/api/v1/entities/mood-log`, { headers: auth });
    check(mood.status() === 404, 'an ungranted type is not reachable');

    console.log('\n3. Requests without a token...');
    const anon = await api.get(`${BASE_URL}/api/v1/types`);
    check(anon.status() === 401, 'no token is refused even with a session cookie');

    console.log('\n4. Revoking...');
    const row = page.locator('[data-testid="api-token-row"]', { hasText: tokenLabel });
    await row.locator('button', { hasText: 'Revoke' }).click();
    await page.waitForLoadState('networkidle');
    await expect(row).toContainText('revoked', { timeout: 10000 });
    await captureScreenshot(page, '03-revoked');

    const after = await api.get(`${BASE_URL}/api/v1/types`, { headers: auth });
    check(after.status() === 401, 'revoked token is refused');

    await page.setViewportSize({ width: 390, height: 844 });
    await page.goto(`${BASE_URL}/app/api-tokens`);
    await page.waitForLoadState('networkidle');
    await captureScreenshot(page, '04-mobile');

    console.log('\n=== API tokens test passed ===\n');
  } catch (error) {
    console.error('\nTest failed:', error);
    await captureScreenshot(page, 'error').catch(() => {});
    process.exitCode = 1;
  } finally {
    await browser.close();
  }
}

main();
