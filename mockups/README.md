# Mockups

Standalone HTML design explorations. They are not part of the app.
The Dockerfile copies only `src`, `dev`, `resources` and `deps.edn`, and Tailwind scans only `src` and `resources/public/js`.

## Layout

```text
mockups/<yyyy-mm>-<topic>/<agent>/mockup-<agent>-<nn>-<slug>.html
```

- Use one directory per exploration.
- Use one subdirectory per agent, so each agent's work commits and attributes separately.
- Keep filenames when you move pages. Pages link to their siblings by name.
- Open pages directly in a browser. Some pages load ECharts and Google Fonts from a CDN.
- All mock data is fictional.

## Explorations

- `2026-09-motivating-dashboards` — dashboards for pace, streaks, milestones and creative work.
  - Claude v1: `claude/mockup-claude-00-index.html`
  - Claude v2: `claude/mockup-claude-v2-00-index.html`
  - Codex: `codex/mockup-codex-00-index.html`
  - Crit sheet: `claude/mockup-claude-crit-sheet.html` — every visualization from all four rounds, grouped by idea, each with a plate ID (A1, B3…) for comments. Screenshots in `claude/review-shots/`.
