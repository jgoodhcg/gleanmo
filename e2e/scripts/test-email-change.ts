// E2E test for changing the account email
// Usage: npm run test:email-change
//
// A changed email is held as pending until the code sent to the new address
// is entered. The code only reaches the dev server's console, so this test
// covers everything up to a successful verify (which the unit tests cover):
// 1. Requesting a change shows the code step and leaves the email as it was,
//    while the other settings on the form still save
// 2. Wrong codes are refused, and the third ends the attempt
// 3. A pending change can be canceled
//
// Refusing an address another account has is left to the unit tests: e2e
// sign-in only allows @localhost accounts, which fail the address shape check
// before the in-use check is reached.

import { chromium, Page, expect } from '@playwright/test';
import { authenticateForDev } from './auth.js';

const BASE_URL = process.env.BASE_URL || 'http://localhost:8080';

async function captureScreenshot(page: Page, name: string) {
  const phase = process.env.SCREENSHOT_PHASE;
  const prefix = phase ? `${phase}-` : '';
  const filepath = `screenshots/${prefix}email-change-${name}.png`;
  await page.screenshot({ path: filepath, fullPage: true });
  console.log(`  [screenshot] ${filepath}`);
}

function check(condition: boolean, message: string) {
  if (!condition) throw new Error(message);
  console.log(`  [ok] ${message}`);
}

async function getAccountEditUrl(page: Page): Promise<string> {
  await page.goto(`${BASE_URL}/app`, { waitUntil: 'networkidle' });
  const href = await page.locator('a[href^="/app/users/"]').first().getAttribute('href');
  return `${BASE_URL}${href}/edit`;
}

async function submitAccountForm(page: Page) {
  await Promise.all([
    page.waitForResponse((resp) => resp.url().includes('/app/users/') && resp.status() === 200),
    page.locator('#user-edit-form button[type="submit"]').click(),
  ]);
  await page.waitForTimeout(500);
}

async function submitCode(page: Page, code: string) {
  await page.fill('#email-code', code);
  await Promise.all([
    page.waitForResponse((resp) => resp.url().includes('email-status=') && resp.status() === 200),
    page.locator('#verify-email-form button[type="submit"]').click(),
  ]);
  await page.waitForTimeout(500);
}

async function main() {
  console.log('\n=== Email Change Test ===\n');

  const browser = await chromium.launch({ headless: true });
  const context = await browser.newContext({ viewport: { width: 1280, height: 900 } });
  const page = await context.newPage();

  try {
    const ownEmail = 'e2e-email-change@localhost';
    const newEmail = `e2e-email-change-${Date.now()}@example.test`;

    await authenticateForDev(page, ownEmail);
    const editUrl = await getAccountEditUrl(page);

    console.log('1. Requesting a change...');
    await page.goto(editUrl, { waitUntil: 'networkidle' });
    await captureScreenshot(page, '01-account-edit');
    const archived = page.locator('input[name="show-archived"]');
    const archivedBefore = await archived.isChecked();
    await page.fill('input[name="email"]', newEmail);
    await archived.setChecked(!archivedBefore);
    await submitAccountForm(page);

    await expect(page.locator('#pending-email')).toBeVisible();
    check((await page.locator('#pending-email').innerText()).includes(newEmail),
          'the code step names the new address');
    check((await page.inputValue('input[name="email"]')) === ownEmail,
          'the email field still shows the current address');
    check((await page.locator('input[name="show-archived"]').isChecked()) === !archivedBefore,
          'other settings saved with the same submit');
    await captureScreenshot(page, '02-code-sent');

    console.log('2. Wrong codes...');
    await submitCode(page, 'wrong1');
    check((await page.locator('#email-status').innerText()).includes('incorrect'),
          'a wrong code is refused');
    await captureScreenshot(page, '03-wrong-code');
    await submitCode(page, 'wrong2');
    await submitCode(page, 'wrong3');
    check((await page.locator('#email-status').innerText()).includes('Too many'),
          'the third wrong code ends the attempt');
    check((await page.locator('#pending-email').count()) === 0,
          'the code step is gone');

    console.log('3. Canceling...');
    await page.goto(editUrl, { waitUntil: 'networkidle' });
    await page.fill('input[name="email"]', newEmail);
    await submitAccountForm(page);
    await expect(page.locator('#pending-email')).toBeVisible();
    await Promise.all([
      page.waitForResponse((resp) => resp.url().endsWith('/edit') && resp.status() === 200),
      page.locator('#cancel-email-change-form button[type="submit"]').click(),
    ]);
    await page.waitForTimeout(500);
    check((await page.locator('#pending-email').count()) === 0, 'canceling drops the code step');
    check((await page.inputValue('input[name="email"]')) === ownEmail, 'the email is unchanged');
    await captureScreenshot(page, '04-canceled');

    // Leave the settings as found.
    await page.goto(editUrl, { waitUntil: 'networkidle' });
    await page.locator('input[name="show-archived"]').setChecked(archivedBefore);
    await submitAccountForm(page);

    console.log('\n=== Email Change Test passed ===\n');
  } catch (error) {
    await captureScreenshot(page, 'failure');
    console.error('\nTest failed:', error);
    process.exitCode = 1;
  } finally {
    await browser.close();
  }
}

main();
