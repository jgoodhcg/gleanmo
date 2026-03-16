// E2E test for reading entity CRUD (book + reading-log)
// Usage: npm run test:reading-crud
//
// This test:
// 1. Creates a book via the CRUD form
// 2. Verifies the book appears in the list
// 3. Edits the book's author
// 4. Creates a reading-log referencing the book
// 5. Verifies the reading-log appears in the list

import { chromium, Page, Locator, expect } from '@playwright/test';
import { authenticateForDev } from './auth.js';

const BASE_URL = process.env.BASE_URL || 'http://localhost:8080';

async function captureScreenshot(page: Page, name: string) {
  const phase = process.env.SCREENSHOT_PHASE;
  const prefix = phase ? `${phase}-` : '';
  const filepath = `screenshots/${prefix}reading-crud-${name}.png`;
  await page.screenshot({ path: filepath });
  console.log(`  [screenshot] ${filepath}`);
}

async function submitHtmxForm(form: Locator, fallbackAction: string) {
  await form.evaluate((node: HTMLElement, action: string) => {
    const formEl = node as HTMLFormElement;
    formEl.setAttribute('action', formEl.getAttribute('hx-post') || action);
    formEl.setAttribute('method', 'POST');
  }, fallbackAction);
  await form.evaluate((node: HTMLElement) => (node as HTMLFormElement).submit());
}

async function setHiddenOrVisibleSelectValue(select: Locator, value: string) {
  await select.evaluate((node: HTMLElement, nextValue: string) => {
    const selectEl = node as HTMLSelectElement;
    selectEl.value = nextValue;
    selectEl.dispatchEvent(new Event('input', { bubbles: true }));
    selectEl.dispatchEvent(new Event('change', { bubbles: true }));
  }, value);
}

async function createBook(page: Page, email: string, title: string, author?: string) {
  await authenticateForDev(page, email);
  await page.goto(`${BASE_URL}/app/crud/form/book/new`);
  await page.waitForLoadState('networkidle');

  const form = page.locator('#book-new-form');
  await expect(form).toBeVisible({ timeout: 10000 });
  await form.locator('textarea[name="book/title"]').fill(title);
  if (author) {
    await form.locator('textarea[name="book/author"]').fill(author);
  }
  await submitHtmxForm(form, '/app/crud/book');
  await page.waitForLoadState('networkidle');
  await page.waitForTimeout(300);
  console.log(`  [+] Created book "${title}"`);
}

async function getBookId(page: Page, email: string) {
  await authenticateForDev(page, email);
  await page.goto(`${BASE_URL}/app/crud/form/reading-log/new`);
  await page.waitForLoadState('networkidle');

  // Book options use :book/label (which is nil for books that only have :book/title),
  // so we find the first option with a non-empty UUID value instead.
  const values = await page
    .locator('select[name="reading-log/book-id"] option')
    .evaluateAll((nodes) =>
      nodes
        .map((node) => (node as HTMLOptionElement).value)
        .filter((v) => v && v.length > 0));

  if (values.length === 0) {
    throw new Error('No book options found in reading-log form');
  }
  return values[0];
}

async function getUserTodayDate(page: Page, email: string) {
  await authenticateForDev(page, email);
  await page.goto(`${BASE_URL}/app/crud/form/reading-log/new`);
  await page.waitForLoadState('networkidle');

  const beginning = await page.locator('input[name="reading-log/beginning"]').inputValue();
  if (!beginning || beginning.length < 10) {
    throw new Error(`Could not read beginning default value: "${beginning}"`);
  }
  return beginning.slice(0, 10);
}

async function createLocation(page: Page, email: string, label: string) {
  await authenticateForDev(page, email);
  await page.goto(`${BASE_URL}/app/crud/form/location/new`);
  await page.waitForLoadState('networkidle');

  const form = page.locator('#location-new-form');
  await expect(form).toBeVisible({ timeout: 10000 });
  await form.locator('input[name="location/label"]').fill(label);
  await submitHtmxForm(form, '/app/crud/location');
  await page.waitForLoadState('networkidle');
  await page.waitForTimeout(300);
  console.log(`  [+] Created location "${label}"`);
}

async function getLocationId(page: Page, email: string) {
  await authenticateForDev(page, email);
  await page.goto(`${BASE_URL}/app/crud/form/reading-log/new`);
  await page.waitForLoadState('networkidle');

  const values = await page
    .locator('select[name="reading-log/location-id"] option')
    .evaluateAll((nodes) =>
      nodes
        .map((node) => (node as HTMLOptionElement).value)
        .filter((v) => v && v.length > 0));

  if (values.length === 0) {
    throw new Error('No location options found in reading-log form');
  }
  return values[0];
}

async function createReadingLog(
  page: Page,
  email: string,
  data: { bookId: string; beginning: string; end?: string; locationId?: string; format?: string }
) {
  await authenticateForDev(page, email);
  await page.goto(`${BASE_URL}/app/crud/form/reading-log/new`);
  await page.waitForLoadState('networkidle');

  const form = page.locator('#reading-log-new-form');
  await expect(form).toBeVisible({ timeout: 10000 });
  await setHiddenOrVisibleSelectValue(form.locator('select[name="reading-log/book-id"]'), data.bookId);
  await form.locator('input[name="reading-log/beginning"]').fill(data.beginning);
  if (data.end) {
    await form.locator('input[name="reading-log/end"]').fill(data.end);
  }
  await setHiddenOrVisibleSelectValue(form.locator('select[name="reading-log/time-zone"]'), 'UTC');
  if (data.locationId) {
    await setHiddenOrVisibleSelectValue(form.locator('select[name="reading-log/location-id"]'), data.locationId);
  }
  if (data.format) {
    await setHiddenOrVisibleSelectValue(form.locator('select[name="reading-log/format"]'), data.format);
  }

  await submitHtmxForm(form, '/app/crud/reading-log');
  await page.waitForLoadState('networkidle');
  await page.waitForTimeout(300);
  console.log(`  [+] Created reading log ${data.beginning}`);
}

async function main() {
  console.log('\n=== Reading CRUD Test ===\n');

  const browser = await chromium.launch({ headless: true });
  const page = await browser.newPage({ viewport: { width: 1280, height: 720 } });

  try {
    const email = `e2e-reading-crud-${Date.now()}@localhost`;
    const bookTitle = `Test Book ${Date.now()}`;
    const bookAuthor = 'Original Author';
    const updatedAuthor = 'Updated Author';

    // 1. Create a book
    console.log('1. Creating book...');
    await createBook(page, email, bookTitle, bookAuthor);
    await captureScreenshot(page, '01-book-created');

    // 2. Verify book appears in list
    console.log('\n2. Verifying book in list...');
    await authenticateForDev(page, email);
    await page.goto(`${BASE_URL}/app/crud/book`);
    await page.waitForLoadState('networkidle');
    await expect(page.locator(`text=${bookTitle}`).first()).toBeVisible({ timeout: 10000 });
    console.log('  [+] Book visible in list');
    await captureScreenshot(page, '02-book-list');

    // 3. Edit the book's author
    console.log('\n3. Editing book author...');
    const editLink = page.locator(`a:has-text("${bookTitle}")`).first();
    await editLink.click();
    await page.waitForLoadState('networkidle');
    await captureScreenshot(page, '03-book-edit-form');

    const editForm = page.locator('#book-edit-form');
    await expect(editForm).toBeVisible({ timeout: 10000 });
    const authorInput = editForm.locator('textarea[name="book/author"]');
    await authorInput.clear();
    await authorInput.fill(updatedAuthor);
    await submitHtmxForm(editForm, '/app/crud/book');
    await page.waitForLoadState('networkidle');
    await page.waitForTimeout(300);
    console.log(`  [+] Updated author to "${updatedAuthor}"`);

    // 4. Verify updated author in list
    console.log('\n4. Verifying updated author...');
    await authenticateForDev(page, email);
    await page.goto(`${BASE_URL}/app/crud/book`);
    await page.waitForLoadState('networkidle');
    await expect(page.locator(`text=${updatedAuthor}`).first()).toBeVisible({ timeout: 10000 });
    console.log('  [+] Updated author visible in list');
    await captureScreenshot(page, '04-book-updated');

    // 5. Create a location for the reading log
    console.log('\n5. Creating location...');
    const locationLabel = `Test Location ${Date.now()}`;
    await createLocation(page, email, locationLabel);

    // 6. Create a reading-log referencing the book and location
    console.log('\n6. Creating reading log...');
    const bookId = await getBookId(page, email);
    const locationId = await getLocationId(page, email);
    const date = await getUserTodayDate(page, email);
    const beginning = `${date}T09:00`;
    const end = `${date}T10:00`;

    await createReadingLog(page, email, {
      bookId,
      beginning,
      end,
      locationId,
      format: 'paperback',
    });
    await captureScreenshot(page, '05-reading-log-created');

    // 7. Verify reading-log appears in list
    console.log('\n7. Verifying reading log in list...');
    await authenticateForDev(page, email);
    await page.goto(`${BASE_URL}/app/crud/reading-log`);
    await page.waitForLoadState('networkidle');
    // The list shows "Showing 1-1" if exactly one reading-log exists
    await expect(page.locator('text=Showing 1-1').first()).toBeVisible({ timeout: 10000 });
    // Verify the entry has an Edit link (proves a card rendered)
    await expect(page.locator('text=Edit').first()).toBeVisible({ timeout: 5000 });
    console.log('  [+] Reading log visible in list');
    await captureScreenshot(page, '06-reading-log-list');

    console.log('\n=== Test Passed ===\n');
  } catch (error) {
    console.error('\n=== Test Failed ===');
    console.error(error);
    await captureScreenshot(page, 'error-state');
    process.exit(1);
  } finally {
    await browser.close();
  }
}

main();
