// Goal fixtures for the visual-timeline series account.
//
// The rest of the series fixture is seeded server-side by
// /auth/e2e-seed-series (dev/tech/jgood/gleanmo/e2e_auth.clj). Goals are
// seeded here instead, through the goal editor's own POST /app/goals and
// POST /app/goal/:id/pin, so that:
//   - the goal frames exercise the same write path a user takes, and
//   - the seed works on any revision of the goals UI that keeps those two
//     routes, which lets before/after captures of a goals redesign share it.
//
// Every goal counts records the series fixture already logs once a day
// (habit logs at 08:00, project and reading intervals), so progress and the
// dated-total ruler have marks on past days as well as today.
//
// Idempotent: a goal is created only when its label is not already on
// /app/goals. The dated goal is keyed by calendar month, so each new month
// gets a fresh, live ruler rather than one long-expired goal.

import { Page } from '@playwright/test';

const BASE_URL = process.env.BASE_URL || 'http://localhost:8080';

/** Must match the series user's time zone in e2e_auth.clj. */
const SERIES_ZONE = 'America/Detroit';

/** Fixed baseline for the undated goals, so their totals keep growing. */
const SERIES_GOALS_START = '2026-09-28';

interface GoalSpec {
  label: string;
  measurement: string;
  timing: 'dated' | 'weekly' | 'open-ended';
  target: string;
  startsOn: string;
  endsOn?: string;
  pin?: boolean;
}

/** Today's `YYYY-MM-DD` in the series user's zone. */
function localToday(): string {
  return new Intl.DateTimeFormat('en-CA', { timeZone: SERIES_ZONE }).format(new Date());
}

function goalSpecs(today: string): GoalSpec[] {
  const [y, m] = today.split('-').map(Number);
  const daysInMonth = new Date(Date.UTC(y, m, 0)).getUTCDate();
  const month = String(m).padStart(2, '0');
  const monthName = new Date(Date.UTC(y, m - 1, 1)).toLocaleString('en-US', {
    month: 'long',
    timeZone: 'UTC',
  });
  return [
    {
      label: `Morning reflection, ${monthName} ${y}`,
      measurement: 'habit-log/records-total',
      timing: 'dated',
      target: String(daysInMonth - 4),
      startsOn: `${y}-${month}-01`,
      endsOn: `${y}-${month}-${daysInMonth}`,
      pin: true,
    },
    {
      label: 'Weekly project hours',
      measurement: 'project-log/duration-total',
      timing: 'weekly',
      target: '8',
      startsOn: SERIES_GOALS_START,
    },
    {
      label: 'Reading hours, open-ended',
      measurement: 'reading-log/duration-total',
      timing: 'open-ended',
      target: '40',
      startsOn: SERIES_GOALS_START,
    },
  ];
}

async function csrfToken(page: Page): Promise<string> {
  await page.goto(`${BASE_URL}/app/goals/new`, { waitUntil: 'domcontentloaded' });
  const token = await page
    .locator('#goal-editor-form input[name="__anti-forgery-token"]')
    .first()
    .getAttribute('value');
  if (!token) throw new Error('No anti-forgery token on the goal editor');
  return token;
}

async function createGoal(page: Page, csrf: string, spec: GoalSpec): Promise<string> {
  const res = await page.request.post(`${BASE_URL}/app/goals`, {
    form: {
      '__anti-forgery-token': csrf,
      label: spec.label,
      measurement: spec.measurement,
      timing: spec.timing,
      target: spec.target,
      'starts-on': spec.startsOn,
      'ends-on': spec.endsOn ?? '',
      'time-zone': SERIES_ZONE,
    },
    maxRedirects: 0,
  });
  // Success is a 303 to the new goal (`/app/goals?goal=<id>` or
  // `/app/goal/<id>`, depending on revision); a 200 is the editor re-rendered
  // with validation errors.
  const location = res.headers()['location'] ?? '';
  const id = location.match(/[0-9a-f]{8}-[0-9a-f-]{27}/)?.[0];
  if (res.status() !== 303 || !id) {
    throw new Error(`Creating goal "${spec.label}" failed: HTTP ${res.status()} ${location}`);
  }
  return id;
}

async function pinGoal(page: Page, csrf: string, id: string): Promise<void> {
  const res = await page.request.post(`${BASE_URL}/app/goal/${id}/pin`, {
    form: { '__anti-forgery-token': csrf, pinned: 'true' },
    maxRedirects: 0,
  });
  if (res.status() !== 303) {
    throw new Error(`Pinning goal ${id} failed: HTTP ${res.status()}`);
  }
}

/** Seeded records a manifest path can name as `{fixture}`. */
export type SeriesFixture = 'dated-goal';

/**
 * Ids of the seeded records manifest paths refer to, read from the goal links
 * on /app/goals. Call after seedSeriesGoals on an authenticated page. A
 * fixture whose goal is missing (e.g. archived by hand) is left out, and its
 * route is recorded as an error rather than aborting the tick.
 */
export async function seriesFixtureIds(page: Page): Promise<Partial<Record<SeriesFixture, string>>> {
  const dated = goalSpecs(localToday()).find((g) => g.timing === 'dated')!;
  await page.goto(`${BASE_URL}/app/goals`, { waitUntil: 'domcontentloaded' });
  const href = await page
    .locator('a[href*="/app/goal"]', { hasText: dated.label })
    .first()
    .getAttribute('href', { timeout: 5000 })
    .catch(() => null);
  const id = href?.match(/[0-9a-f]{8}-[0-9a-f-]{27}/)?.[0];
  return id ? { 'dated-goal': id } : {};
}

/**
 * Ensure the series account has its goal fixtures. Call on an authenticated
 * page. Returns the labels created this run (empty when already seeded).
 */
export async function seedSeriesGoals(page: Page): Promise<string[]> {
  const existing = await (await page.request.get(`${BASE_URL}/app/goals`)).text();
  const missing = goalSpecs(localToday()).filter((g) => !existing.includes(g.label));
  if (missing.length === 0) return [];

  const csrf = await csrfToken(page);
  for (const spec of missing) {
    const id = await createGoal(page, csrf, spec);
    if (spec.pin) await pinGoal(page, csrf, id);
  }
  return missing.map((g) => g.label);
}
