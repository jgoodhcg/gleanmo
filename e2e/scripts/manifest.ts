// Canonical route manifest for timelapse / progression-series screenshots.
//
// Single source of truth for "which frames make up one tick of the visual
// timeline." The series capture script (shot-manifest.ts) walks every entry at
// the declared viewports, so each tick is directly comparable to the last —
// same routes, same viewports, same wait conditions. That comparability is
// what makes a clean timelapse possible.
//
// Keep this list curated, not exhaustive. The goal is coverage of every
// distinct *surface type* (overview, viz charts, forms, lists, flows), not
// every CRUD entity — 25 near-identical list pages add noise, not signal.
//
// When navigation changes, update this file in the same PR (see
// screenshot-runner.md "Maintenance"). Adding a route here is the trigger for
// "this surface is now part of the visual timeline."
//
// Route paths are all authenticated (/app/*). The capture script authenticates
// once per viewport via authenticateForDev before walking the manifest.

export type ViewportName = 'mobile' | 'desktop';

export interface ViewportSpec {
  name: ViewportName;
  width: number;
  height: number;
  isMobile?: boolean;
  hasTouch?: boolean;
}

/** Default viewports — match shot-pages.ts so series frames line up with the
 *  existing per-change capture tool. Mobile mirrors iPhone 13/14; desktop is
 *  the standard e2e width with a bit more height so hero regions read fully. */
export const DEFAULT_VIEWPORTS: ViewportSpec[] = [
  { name: 'mobile', width: 390, height: 844, isMobile: true, hasTouch: true },
  { name: 'desktop', width: 1280, height: 900 },
];

export interface ManifestRoute {
  /** Filename-safe identifier. Output: `<slug>-<viewport>.png`. */
  slug: string;
  /** Route path under the app (must start with `/`). */
  path: string;
  /** Grouping label — used for logging and recorded in metadata. */
  group: 'surfaces' | 'dashboards' | 'viz' | 'stats' | 'flows' | 'forms' | 'lists';
  /** Override which viewports to capture. Defaults to mobile + desktop.
   *  Use `['desktop']` for data-table-heavy pages with no mobile layout. */
  viewports?: ViewportName[];
  /** Selector to wait for after navigation (for async-rendered content like
   *  HTMX fragments or ECharts canvases). Optional but recommended for viz. */
  waitForSelector?: string;
  /** Extra settle time after networkidle + selector, before the screenshot.
   *  Lets ECharts animations / lazy frames finish so frames don't flicker. */
  settleMs?: number;
}

export const MANIFEST_ROUTES: ManifestRoute[] = [
  // ── Primary surfaces (nav destinations) ──────────────────────────────
  { slug: 'home',      path: '/app',            group: 'surfaces', waitForSelector: '#overview-recent', settleMs: 1200 },
  { slug: 'log-hub',   path: '/app/log',        group: 'surfaces' },
  { slug: 'today',     path: '/app/task/today', group: 'surfaces', waitForSelector: '#today-quick-add' },
  { slug: 'focus',     path: '/app/task/focus', group: 'surfaces' },
  { slug: 'calendar',  path: '/app/calendar/year', group: 'surfaces', settleMs: 800 },
  { slug: 'timers',    path: '/app/timers',     group: 'surfaces', waitForSelector: '#sidebar' },

  // ── Dashboards ───────────────────────────────────────────────────────
  { slug: 'dashboards-entities',     path: '/app/dashboards/entities',     group: 'dashboards', viewports: ['desktop'] },
  { slug: 'dashboards-activity-logs', path: '/app/dashboards/activity-logs', group: 'dashboards', viewports: ['desktop'] },
  { slug: 'dashboards-stats',        path: '/app/dashboards/stats',        group: 'dashboards', viewports: ['desktop'] },

  // ── Visualizations (ECharts — highest timelapse value) ───────────────
  { slug: 'viz-habit-log',       path: '/app/viz/habit-log',       group: 'viz', settleMs: 1500 },
  { slug: 'viz-meditation-log',  path: '/app/viz/meditation-log',  group: 'viz', settleMs: 1500 },
  { slug: 'viz-bm-log',          path: '/app/viz/bm-log',          group: 'viz', settleMs: 1500 },
  { slug: 'viz-medication-log',  path: '/app/viz/medication-log',  group: 'viz', settleMs: 1500 },
  { slug: 'viz-reading-log',     path: '/app/viz/reading-log',     group: 'viz', settleMs: 1500 },
  { slug: 'viz-project-log',     path: '/app/viz/project-log',     group: 'viz', settleMs: 1500 },
  { slug: 'viz-symptom-log',     path: '/app/viz/symptom-log',     group: 'viz', settleMs: 1500 },
  { slug: 'viz-mood-log',        path: '/app/viz/mood-log',        group: 'viz', settleMs: 1500 },
  { slug: 'viz-exercise-session', path: '/app/viz/exercise-session', group: 'viz', settleMs: 1500 },
  { slug: 'viz-boulder-session', path: '/app/viz/boulder-session', group: 'viz', settleMs: 1500 },

  // ── Stats pages ──────────────────────────────────────────────────────
  { slug: 'stats-habit-patterns',    path: '/app/stats/habit-patterns',    group: 'stats', settleMs: 1000 },
  { slug: 'stats-meditation',        path: '/app/stats/meditation',        group: 'stats', settleMs: 1000 },
  { slug: 'stats-bm',                path: '/app/stats/bm',                group: 'stats', settleMs: 1000 },
  { slug: 'stats-medication-history', path: '/app/stats/medication-history', group: 'stats', settleMs: 1000 },

  // ── Flows (multi-step surfaces) ──────────────────────────────────────
  { slug: 'workout',          path: '/app/exercise/session', group: 'flows' },
  { slug: 'boulder-session',  path: '/app/boulder/session',  group: 'flows' },
  { slug: 'boulder-problems', path: '/app/boulder/problems', group: 'flows' },

  // ── Representative forms ─────────────────────────────────────────────
  { slug: 'form-habit-log-new',      path: '/app/crud/form/habit-log/new',      group: 'forms' },
  { slug: 'form-mood-log-new',       path: '/app/crud/form/mood-log/new',       group: 'forms' },
  { slug: 'form-boulder-problem-new', path: '/app/crud/form/boulder-problem/new', group: 'forms' },
  { slug: 'form-exercise-set-new',   path: '/app/crud/form/exercise-set/new',   group: 'forms' },
  { slug: 'form-book-new',           path: '/app/crud/form/book/new',           group: 'forms' },

  // ── Representative lists ─────────────────────────────────────────────
  { slug: 'list-habit',           path: '/app/crud/habit',           group: 'lists', viewports: ['desktop'] },
  { slug: 'list-book',            path: '/app/crud/book',            group: 'lists', viewports: ['desktop'] },
  { slug: 'list-boulder-problem', path: '/app/crud/boulder-problem', group: 'lists', viewports: ['desktop'] },
];
