// Diagnostic (not a test — deliberately outside the test-*.ts glob CI runs).
//
// Measures how long `waitForLoadState('networkidle')` actually takes on a
// representative page, and attributes the wait to the requests responsible.
// The suite performs this wait 156 times; each one is a 30s-timeout dice roll
// that the page goes fully quiet for 500ms.
//
// Usage: npx tsx scripts/diagnose-networkidle.ts [path] [iterations]

import { chromium } from '@playwright/test';
import { authenticateForDev } from './auth.js';

const BASE_URL = process.env.BASE_URL || 'http://localhost:8080';
const PATH = process.argv[2] || '/app/timers';
const RUNS = Number(process.argv[3] || 8);

const isExternal = (url: string) => !url.startsWith(BASE_URL);
const host = (url: string) => {
  try { return new URL(url).host; } catch { return url; }
};

async function main() {
  const browser = await chromium.launch();
  const page = await browser.newPage();
  await authenticateForDev(page, `e2e-diagnose-${Date.now()}@localhost`);

  const idleTimes: number[] = [];
  let timeouts = 0;
  const slowByHost = new Map<string, { count: number; worst: number }>();

  console.log(`\n=== networkidle diagnostic: ${PATH} (${RUNS} loads) ===\n`);

  for (let i = 1; i <= RUNS; i++) {
    const inflight = new Map<string, number>();
    const finished: { url: string; ms: number }[] = [];

    const onReq = (r: any) => inflight.set(r.url(), Date.now());
    const onDone = (r: any) => {
      const started = inflight.get(r.url());
      if (started !== undefined) {
        finished.push({ url: r.url(), ms: Date.now() - started });
        inflight.delete(r.url());
      }
    };
    page.on('request', onReq);
    page.on('requestfinished', onDone);
    page.on('requestfailed', onDone);

    const t0 = Date.now();
    await page.goto(`${BASE_URL}${PATH}`, { waitUntil: 'domcontentloaded' });
    const tDom = Date.now() - t0;

    let tIdle: number;
    let timedOut = false;
    try {
      await page.waitForLoadState('networkidle', { timeout: 30000 });
      tIdle = Date.now() - t0;
    } catch {
      tIdle = Date.now() - t0;
      timedOut = true;
      timeouts++;
    }
    idleTimes.push(tIdle);

    page.off('request', onReq);
    page.off('requestfinished', onDone);
    page.off('requestfailed', onDone);

    const ext = finished.filter((r) => isExternal(r.url));
    const slowest = [...finished].sort((a, b) => b.ms - a.ms).slice(0, 3);
    for (const r of ext) {
      const h = host(r.url);
      const cur = slowByHost.get(h) || { count: 0, worst: 0 };
      slowByHost.set(h, { count: cur.count + 1, worst: Math.max(cur.worst, r.ms) });
    }

    console.log(
      `run ${String(i).padStart(2)}: dom ${String(tDom).padStart(5)}ms  ` +
        `networkidle ${String(tIdle).padStart(6)}ms${timedOut ? '  *** TIMED OUT ***' : ''}  ` +
        `[${finished.length} reqs, ${ext.length} external]`,
    );
    console.log(
      `         slowest: ` +
        slowest.map((r) => `${host(r.url)} ${r.ms}ms`).join(', '),
    );
    if (inflight.size) {
      console.log(
        `         still in flight at cutoff: ` +
          [...inflight.keys()].map(host).join(', '),
      );
    }
  }

  const mean = Math.round(idleTimes.reduce((a, b) => a + b, 0) / idleTimes.length);
  console.log(`\n--- summary over ${RUNS} loads of ${PATH} ---`);
  console.log(`networkidle  min ${Math.min(...idleTimes)}ms  mean ${mean}ms  max ${Math.max(...idleTimes)}ms`);
  console.log(`timeouts: ${timeouts}/${RUNS}`);
  console.log(`\nexternal hosts contacted per page load:`);
  for (const [h, s] of [...slowByHost.entries()].sort((a, b) => b[1].worst - a[1].worst)) {
    console.log(`  ${h.padEnd(28)} ${String(s.count).padStart(3)} reqs, worst ${s.worst}ms`);
  }

  await browser.close();
}

main().catch((e) => {
  console.error(e);
  process.exit(1);
});
