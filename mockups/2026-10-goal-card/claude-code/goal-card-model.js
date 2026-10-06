// Shared mock model for the 2026-10 goal card mockups.
// Numbers mirror the 2026-10-05 dogfood screenshot (roadmap/090, 2026-10-05 notes):
// 245 of 5,000 reps, 19 elapsed days, 347 days left including today.

const GOAL = {
  label: 'Pullup',
  source: 'Exercise lines · Pullup · Reps',
  unit: 'reps',
  target: 5000,
  start: '2026-09-16',
  end: '2027-09-16',
  totalDays: 366,
  elapsed: 19,
  // Reps per elapsed day; sums to 245.
  daily: [15, 0, 20, 12, 10, 0, 18, 14, 16, 0, 22, 10, 12, 15, 0, 20, 18, 13, 30],
};

const STATES = [
  { key: 'start', label: 'Start of day · 0 today', today: 0 },
  { key: 'mid', label: 'Mid-day · 10 today', today: 10 },
  { key: 'pace', label: 'Kept pace · 14 today', today: 14 },
  { key: 'caught', label: 'Caught up · 30 today', today: 30 },
];

const RULES = [
  { key: 'round', label: 'Round step (≈3 days of pace)' },
  { key: 'pace', label: 'Even-pace line' },
  { key: 'both', label: 'Both' },
];

const fmt = (n) => Math.round(n).toLocaleString('en-US');
const fmt1 = (n) => (Math.round(n * 10) / 10).toLocaleString('en-US', { minimumFractionDigits: 1, maximumFractionDigits: 1 });

function niceStep(x) {
  const p = Math.pow(10, Math.floor(Math.log10(x)));
  const f = x / p;
  return p * (f < 1.5 ? 1 : f < 3.5 ? 2 : f < 7.5 ? 5 : 10);
}

function compute(todayReps, rule) {
  const g = GOAL;
  const before = g.daily.reduce((a, b) => a + b, 0); // 245
  const logged = before + todayReps;
  const daysLeft = g.totalDays - g.elapsed; // includes today
  const required = (g.target - before) / daysLeft; // 13.7, fixed through the day
  const evenRate = g.target / g.totalDays;
  const evenAtStart = evenRate * g.elapsed; // where even pace was this morning
  const evenTonight = evenRate * (g.elapsed + 1); // where even pace is at midnight
  const average = before / g.elapsed; // per elapsed day
  const liveAverage = logged / (g.elapsed + 1);
  const requiredAfter = (g.target - logged) / (daysLeft - 1);

  // Gap: the debt carried into today, against the even-pace line. Stable through the day.
  const gap = evenAtStart - before;
  // Today's remaining amounts.
  const keepPace = required - todayReps; // "needed today"
  const catchUp = evenTonight - logged; // keep pace AND close the gap by midnight

  // Thresholds
  const step = niceStep(required * 3); // 41 → 50
  const nextRound = (Math.floor(logged / step) + 1) * step;
  const paceMarks = [
    { value: Math.ceil(evenAtStart), label: 'gap closed' },
    { value: Math.ceil(evenTonight), label: 'even pace tonight' },
  ].filter((m) => m.value > logged);
  const roundMark = { value: nextRound, label: '' };
  let thresholds;
  if (rule === 'round') thresholds = [roundMark];
  else if (rule === 'pace') thresholds = paceMarks.length ? [paceMarks[0]] : [roundMark];
  else thresholds = [roundMark, ...paceMarks.slice(0, 1)].sort((a, b) => a.value - b.value);
  thresholds = thresholds.map((t) => ({ ...t, away: t.value - logged }));

  // Stages: today's default threshold step (a tenth of the target) becomes a stage the bar zooms into.
  const stageSize = niceStep(g.target / 10);
  const stageStart = Math.floor(logged / stageSize) * stageSize;
  const stage = { size: stageSize, start: stageStart, end: stageStart + stageSize,
    n: stageStart / stageSize + 1, of: Math.ceil(g.target / stageSize) };
  const inStage = (n) => Math.min(100, Math.max(0, ((n - stage.start) / stage.size) * 100));

  return {
    stage, inStage,
    g, todayReps, before, logged, daysLeft, required, evenRate, evenAtStart, evenTonight,
    average, liveAverage, requiredAfter, gap, keepPace, catchUp, step, thresholds,
    pct: (logged / g.target) * 100,
    paceMet: keepPace <= 0,
    caughtUp: catchUp <= 0,
  };
}

// Cumulative chart, zoomed to the goal so far plus a week ahead.
function chartSVG(v, { width = 640, height = 200 } = {}) {
  const g = v.g;
  const days = g.elapsed + 8;
  const yMax = Math.max(v.evenRate * days, v.logged) * 1.1;
  const pad = { l: 36, r: 10, t: 10, b: 22 };
  const x = (d) => pad.l + (d / days) * (width - pad.l - pad.r);
  const y = (n) => height - pad.b - (n / yMax) * (height - pad.t - pad.b);
  let cum = 0;
  const pts = [[0, 0]];
  g.daily.forEach((n, i) => { cum += n; pts.push([i + 1, cum]); });
  const todayPt = [g.elapsed + 0.5, v.logged];
  const path = pts.map(([d, n], i) => `${i ? 'L' : 'M'}${x(d).toFixed(1)},${y(n).toFixed(1)}`).join(' ');
  const todayPath = `M${x(g.elapsed)},${y(v.before)} L${x(todayPt[0])},${y(todayPt[1])}`;
  const even = `M${x(0)},${y(0)} L${x(days)},${y(v.evenRate * days)}`;
  const req = `M${x(g.elapsed)},${y(v.before)} L${x(days)},${y(v.before + v.required * (days - g.elapsed))}`;
  const grid = [0, 0.25, 0.5, 0.75, 1].map((f) => {
    const n = Math.round((yMax * f) / 50) * 50;
    return `<line x1="${pad.l}" x2="${width - pad.r}" y1="${y(n)}" y2="${y(n)}" stroke="#30363d" stroke-width="1"/>
      <text x="${pad.l - 6}" y="${y(n) + 3}" fill="#8b949e" font-size="10" text-anchor="end">${n}</text>`;
  }).join('');
  return `<svg class="chart" viewBox="0 0 ${width} ${height}" role="img" aria-label="Cumulative reps vs even pace">
    ${grid}
    <line x1="${x(g.elapsed)}" x2="${x(g.elapsed)}" y1="${pad.t}" y2="${height - pad.b}" stroke="#8b949e" stroke-dasharray="2 3"/>
    <text x="${x(g.elapsed) + 4}" y="${pad.t + 10}" fill="#8b949e" font-size="10">Now</text>
    <path d="${even}" stroke="#6b7785" stroke-width="1.5" stroke-dasharray="5 4" fill="none"/>
    <path d="${req}" stroke="#8b5cf6" stroke-width="1.5" stroke-dasharray="5 4" fill="none"/>
    <path d="${path}" stroke="#06b6d4" stroke-width="2" fill="none"/>
    <path d="${todayPath}" stroke="#32cd32" stroke-width="2" fill="none"/>
    <text x="${x(0)}" y="${height - 6}" fill="#8b949e" font-size="10">Sep 16</text>
    <text x="${x(g.elapsed)}" y="${height - 6}" fill="#8b949e" font-size="10" text-anchor="middle">Oct 5</text>
    <text x="${x(days)}" y="${height - 6}" fill="#8b949e" font-size="10" text-anchor="end">Oct 13</text>
  </svg>
  <p class="note"><span class="cyan">━</span> logged <span class="lime">━</span> today
    <span style="color:#6b7785">┅</span> even pace <span class="violet">┅</span> required from today · zoomed to the goal so far</p>`;
}

// Mockup controls: state and threshold rule. Index page drives embedded copies via postMessage.
function mountMock(render) {
  const params = new URLSearchParams(location.search);
  if (params.get('embed')) document.body.classList.add('embed');
  let state = params.get('state') || 'start';
  let rule = params.get('rule') || 'round';
  const bar = document.querySelector('.mock-bar');
  const draw = () => {
    const s = STATES.find((x) => x.key === state);
    render(compute(s.today, rule));
    bar.querySelectorAll('button').forEach((b) => {
      b.setAttribute('aria-pressed', String(b.dataset.state === state || b.dataset.rule === rule));
    });
  };
  bar.insertAdjacentHTML('beforeend', `
    <span class="group"><strong>Today:</strong>${STATES.map((s) => `<button data-state="${s.key}">${s.label}</button>`).join('')}</span>
    <span class="group"><strong>Threshold rule:</strong>${RULES.map((r) => `<button data-rule="${r.key}">${r.label}</button>`).join('')}</span>`);
  bar.addEventListener('click', (e) => {
    const b = e.target.closest('button');
    if (!b) return;
    if (b.dataset.state) state = b.dataset.state;
    if (b.dataset.rule) rule = b.dataset.rule;
    draw();
  });
  window.addEventListener('message', (e) => {
    if (e.data && e.data.goalMock) {
      state = e.data.state || state;
      rule = e.data.rule || rule;
      draw();
    }
  });
  draw();
}

// Shared fragments
function pickerHTML(v) {
  return `<div class="picker">
    <div class="select"><span>${v.g.label} <small>· reps</small></span><small>▾</small></div>
    <div class="pins">
      <span class="chip on"><span class="dot ${v.paceMet ? 'met' : 'behind'}"></span>Pullup</span>
      <span class="chip"><span class="dot met"></span>Pushup</span>
      <span class="chip"><span class="dot behind"></span>Reading</span>
      <span class="chip">All goals →</span>
    </div>
  </div>`;
}

function headHTML(v) {
  return `<div class="card-head">
    <div><h2>${v.g.label}</h2><div class="src">${v.g.source} · by Sep 16, 2027</div></div>
    <div class="actions"><span>★ Pinned</span><span>Edit</span><span>⋯</span></div>
  </div>`;
}

function detailsHTML(v, extra = '') {
  return `<details class="more">
    <summary><span>Details · chart</span></summary>
    <div class="inner">
      <dl class="kv">
        <dt>Days left (incl. today)</dt><dd class="num">${v.daysLeft}</dd>
        <dt>Average per elapsed day</dt><dd class="num">${fmt1(v.average)} ${v.g.unit}/day</dd>
        <dt>With today</dt><dd class="num">${fmt1(v.liveAverage)} ${v.g.unit}/day</dd>
        <dt>Required after today</dt><dd class="num">${fmt1(v.requiredAfter)} ${v.g.unit}/day</dd>
        <dt>Required vs your average</dt><dd class="num">${(v.required / v.average).toFixed(2)}×</dd>
        <dt>Even pace</dt><dd class="num">${fmt1(v.evenRate)} ${v.g.unit}/day</dd>
        ${extra}
      </dl>
      ${chartSVG(v)}
    </div>
  </details>`;
}

function thresholdText(t, unit) {
  return `<span class="white num">${fmt(t.away)}</span> to ${fmt(t.value)}${t.label ? ` <span class="muted">(${t.label})</span>` : ''}`;
}
