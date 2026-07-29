// Timelapse / progression-series capture.
//
// Walks the canonical route manifest (manifest.ts) at mobile + desktop,
// writing every frame for one "tick" of the visual timeline into a single
// timestamped directory with a metadata sidecar. Because each tick captures
// the same routes at the same viewports with the same wait conditions, frames
// are directly comparable tick-to-tick — which is what a clean timelapse needs.
//
// Usage:
//   just e2e-shot-series                 # capture a tick now
//   npm run shot:series                  # …or from e2e/
//
// Env overrides:
//   BASE_URL              dev server URL (default http://localhost:8080)
//   E2E_EMAIL             authenticated user whose data renders (default e2e-series@localhost)
//   SCREENSHOT_SERIES_DIR output root (default ./screenshots/series)
//   SCREENSHOT_LABEL      optional free-text label recorded in metadata
//                         (e.g. "pre-nav-redesign"); does not affect paths
//
// Output layout:
//   <series-dir>/<ISO-timestamp>/
//     home-mobile.png
//     home-desktop.png
//     …
//     metadata.json       { timestamp, gitSha, branch, gitDirty,
//                           workingTreeFingerprint, baseUrl, email,
//                           viewports, routes: [{ slug, group, viewport, status }] }
//
// Exits non-zero if any route fails to load, so this can gate future
// automation (CI, pre-commit). Failed routes still get recorded in metadata
// with status "error" and an error-state screenshot when possible.
//
// Requires a running dev server (see AGENTS.md "Never Run").

import { chromium, Page } from '@playwright/test';
import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { mkdirSync, writeFileSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { authenticateTimelineForDev } from './auth.js';
import {
  DEFAULT_VIEWPORTS,
  MANIFEST_ROUTES,
  type ManifestRoute,
  type ViewportName,
} from './manifest.js';

const BASE_URL = process.env.BASE_URL || 'http://localhost:8080';
const EMAIL = process.env.E2E_EMAIL || 'e2e-series@localhost';
const SERIES_DIR = process.env.SCREENSHOT_SERIES_DIR || './screenshots/series';
const LABEL = process.env.SCREENSHOT_LABEL || '';

/** Filesystem-safe ISO timestamp (UTC), second precision. */
function isoStamp(now = new Date()): string {
  return now.toISOString().replace(/[:.]/g, '-').slice(0, 19) + 'Z';
}

function gitValue(args: string[]): string {
  try {
    return execFileSync('git', args, {
      encoding: 'utf-8',
      cwd: process.cwd(),
      maxBuffer: 50 * 1024 * 1024,
    }).trim();
  } catch {
    return 'unknown';
  }
}

interface GitWorkingState {
  dirty: boolean | null;
  status: string | null;
  fingerprint: string | null;
}

function gitWorkingState(gitSha: string): GitWorkingState {
  const status = gitValue(['status', '--porcelain=v1', '--untracked-files=all']);
  if (status === 'unknown') {
    return { dirty: null, status: null, fingerprint: null };
  }

  const trackedDiff = gitValue(['diff', '--binary', 'HEAD']);
  const untracked = gitValue(['ls-files', '--others', '--exclude-standard', '-z'])
    .split('\0')
    .filter(Boolean);
  const untrackedHashes = untracked.map((path) => [
    path,
    gitValue(['hash-object', '--', path]),
  ]);
  const fingerprint = createHash('sha256')
    .update(JSON.stringify({ gitSha, trackedDiff, untrackedHashes }))
    .digest('hex');

  return {
    dirty: status.length > 0,
    status: status || null,
    fingerprint,
  };
}

interface CaptureResult {
  slug: string;
  group: string;
  path: string;
  viewport: ViewportName;
  status: 'ok' | 'error';
  error?: string;
}

async function captureRoute(
  page: Page,
  route: ManifestRoute,
  viewport: ViewportName,
  outDir: string,
): Promise<CaptureResult> {
  const result: CaptureResult = {
    slug: route.slug,
    group: route.group,
    path: route.path,
    viewport,
    status: 'ok',
  };
  const file = join(outDir, `${route.slug}-${viewport}.png`);
  try {
    const response = await page.goto(`${BASE_URL}${route.path}`, {
      waitUntil: 'networkidle',
    });
    const status = response?.status() ?? 0;
    if (status === 0 || status >= 400) {
      throw new Error(`HTTP ${status}`);
    }
    if (route.waitForSelector) {
      await page.waitForSelector(route.waitForSelector, { timeout: 15000 });
    }
    if (route.settleMs) {
      await page.waitForTimeout(route.settleMs);
    }
    // Above-the-fold capture by default: a timelapse frame should look like
    // what a user sees at a glance, not a 9000px-tall stitched page where the
    // hero drifts around frame to frame.
    await page.screenshot({ path: file, fullPage: false });
    console.log(`  + ${route.slug}-${viewport}`);
  } catch (err) {
    result.status = 'error';
    result.error = err instanceof Error ? err.message : String(err);
    try {
      await page.screenshot({ path: join(outDir, `${route.slug}-${viewport}.error.png`), fullPage: false });
    } catch {
      // page may be unusable; nothing more to do
    }
    console.log(`  x ${route.slug}-${viewport} — ${result.error}`);
  }
  return result;
}

async function main() {
  const stamp = isoStamp();
  const outDir = resolve(SERIES_DIR, stamp);

  const gitSha = gitValue(['rev-parse', 'HEAD']);
  const branch = gitValue(['rev-parse', '--abbrev-ref', 'HEAD']);
  const workingTree = gitWorkingState(gitSha);
  console.log(`\n=== Series capture ===`);
  console.log(`  dir:    ${outDir}`);
  console.log(`  sha:    ${gitSha}`);
  console.log(`  branch: ${branch}`);
  console.log(`  state:  ${
    workingTree.dirty === null
      ? 'unknown'
      : workingTree.dirty
        ? `dirty (${workingTree.fingerprint?.slice(0, 12)})`
        : 'clean'
  }`);
  console.log(`  base:   ${BASE_URL}`);
  console.log(`  email:  ${EMAIL}${LABEL ? `\n  label:  ${LABEL}` : ''}\n`);

  const results: CaptureResult[] = [];
  const browser = await chromium.launch({ headless: true });

  try {
    // One authenticated context per viewport — auth is paid once, then all
    // routes in that viewport reuse the session.
    for (const vp of DEFAULT_VIEWPORTS) {
      const context = await browser.newContext({
        viewport: { width: vp.width, height: vp.height },
        isMobile: vp.isMobile ?? false,
        hasTouch: vp.hasTouch ?? false,
      });
      const page = await context.newPage();
      await authenticateTimelineForDev(page, EMAIL);
      mkdirSync(outDir, { recursive: true });
      console.log(`\n— ${vp.name} (${vp.width}x${vp.height}) —`);

      for (const route of MANIFEST_ROUTES) {
        const viewports = route.viewports ?? DEFAULT_VIEWPORTS.map((v) => v.name);
        if (!viewports.includes(vp.name)) continue;
        // Ensure the page viewport matches (cheap, idempotent).
        await page.setViewportSize({ width: vp.width, height: vp.height });
        results.push(await captureRoute(page, route, vp.name, outDir));
      }
      await context.close();
    }
  } finally {
    await browser.close();
  }

  const metadata = {
    timestamp: stamp,
    capturedAt: new Date().toISOString(),
    gitSha,
    branch,
    gitDirty: workingTree.dirty,
    gitStatus: workingTree.status,
    workingTreeFingerprint: workingTree.fingerprint,
    baseUrl: BASE_URL,
    email: EMAIL,
    label: LABEL || null,
    viewports: DEFAULT_VIEWPORTS.map((v) => ({ name: v.name, width: v.width, height: v.height })),
    routeCount: MANIFEST_ROUTES.length,
    results,
  };
  writeFileSync(join(outDir, 'metadata.json'), `${JSON.stringify(metadata, null, 2)}\n`);

  const failed = results.filter((r) => r.status === 'error');
  console.log(`\n=== ${results.length - failed.length}/${results.length} frames captured ===`);
  console.log(`  metadata: ${join(outDir, 'metadata.json')}`);
  if (failed.length) {
    console.error(`\n=== ${failed.length} route(s) failed ===`);
    for (const f of failed) console.error(`  x ${f.slug}-${f.viewport}: ${f.error}`);
    process.exit(1);
  }
}

main().catch((e) => {
  console.error(e);
  process.exit(1);
});
