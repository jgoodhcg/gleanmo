---
title: "Automated App Screenshots - Implementation Requirements"
status: idea
description: "Visual changelog via periodic route screenshots"
tags: []
priority: medium
created: 2026-02-02
updated: 2026-02-02
---

# Automated App Screenshots - Implementation Requirements

## Work Unit Summary
- Problem / intent: Build a visual changelog via periodic screenshots of key routes.
- Constraints: Keep setup lightweight and avoid adding friction to local development.
- Proposed approach: Start with local Playwright runs, then evaluate CI automation and artifact storage.
- Open questions: Should screenshots be stored in git, LFS, or external storage?

## Overview

Capture screenshots of every route in the Gleanmo app periodically to track visual progression and design evolution over time. This creates a visual changelog of the application's development.

## Approaches

### 1. **Local Development Integration**
**Trigger**: Integrated with dev server startup or as a separate command
**Tools**: Playwright/Puppeteer + Node.js script
**Storage**: Local filesystem with git tracking

**Pros:**
- No cloud costs
- Easy to set up and iterate
- Screenshots taken with dev data
- Can run on-demand during development

**Cons:**
- Requires manual execution
- Screenshots only when developing
- Local storage only

**Implementation:**
```bash
# New npm scripts in package.json
npm run screenshot:all    # Capture all routes
npm run screenshot:dev    # Auto-capture on dev server start
```

### 2. **GitHub Actions CI/CD**
**Trigger**: On every commit, PR, or scheduled (nightly/weekly)
**Tools**: GitHub Actions + Playwright
**Storage**: Git LFS or GitHub artifacts

**Pros:**
- Automated on code changes
- Free for public repos
- Git-tracked screenshots
- No infrastructure management

**Cons:**
- Need to seed test data
- GitHub Actions limitations
- Artifacts expire after 90 days

**Implementation:**
```yaml
# .github/workflows/screenshots.yml
name: Capture Screenshots
on:
  schedule:
    - cron: '0 2 * * 0' # Weekly on Sunday
  workflow_dispatch: # Manual trigger
```

### 3. **Digital Ocean Droplet**
**Trigger**: Scheduled cron job
**Tools**: Headless Chrome + Clojure/Node.js script
**Storage**: Object storage or local disk

**Pros:**
- Consistent environment
- Production-like setup
- Full control
- Can test with real data

**Cons:**
- Monthly costs (~$5-20/month)
- Infrastructure management
- Requires authentication handling

**Implementation:**
Production-like environment with scheduled screenshot capture

### 4. **Docker + Cron (Local/Cloud)**
**Trigger**: Scheduled container execution
**Tools**: Docker + Playwright + scheduling
**Storage**: Volume mounts or cloud storage

**Pros:**
- Consistent environment
- Portable (local or cloud)
- Easy to reproduce
- Isolated execution

**Cons:**
- Docker overhead
- Requires container orchestration for cloud

## Recommended Implementation Strategy

### **Phase 1: Local Development (Quick Start)**
1. Create Node.js script with Playwright
2. Route discovery from Clojure app
3. Local screenshot storage with git tracking
4. Manual execution during development

### **Phase 2: Automated Capture**
1. GitHub Actions for automated captures
2. Weekly scheduled runs
3. Screenshot comparison and diff detection
4. Slack/Discord notifications for significant changes

### **Phase 3: Advanced Features**
1. Multiple viewport sizes (desktop, tablet, mobile)
2. Screenshot comparison with visual diff tools
3. Integration with deployment pipeline
4. Historical timeline web interface

## Technical Requirements

### Route Discovery
```clojure
;; Extract all routes from app.clj
(defn extract-routes []
  "Scan route definitions and return list of paths")

;; Generate routes.json for screenshot script
{:routes [
  {:path "/app" :auth true :name "dashboard"}
  {:path "/app/habits" :auth true :name "habits"}
  {:path "/app/calendar/year" :auth true :name "calendar"}
  ;; ...
]}
```

### Screenshot Script Structure
```javascript
// screenshots.js
const { chromium } = require('playwright');

async function captureRoutes(routes) {
  const browser = await chromium.launch();
  const context = await browser.newContext();
  
  // Handle authentication
  await authenticate(context);
  
  for (const route of routes) {
    const page = await context.newPage();
    await page.goto(`http://localhost:7888${route.path}`);
    await page.screenshot({
      path: `screenshots/${route.name}-${timestamp()}.png`,
      fullPage: true
    });
    await page.close();
  }
  
  await browser.close();
}
```

### Authentication Handling
```javascript
// Handle email-based auth for screenshots
async function authenticate(context) {
  // Option 1: Bypass auth in dev/test mode
  // Option 2: Use test user credentials
  // Option 3: Mock authentication for screenshot user
}
```

## Storage & Organization

### File Structure
```
screenshots/
├── 2025-01-15T10-30-00Z/
│   ├── dashboard.png
│   ├── habits.png
│   ├── calendar.png
│   └── metadata.json
├── 2025-01-22T10-30-00Z/
│   └── ...
└── latest/
    └── ... (symlinks to most recent)
```

### Metadata
```json
{
  "timestamp": "2025-01-15T10:30:00Z",
  "commit": "abc123def456",
  "branch": "main",
  "routes_captured": 15,
  "viewport": "1920x1080",
  "user_agent": "...",
  "environment": "development"
}
```

## Implementation Tasks

### MVP (1-2 hours)
- [ ] Create basic Playwright screenshot script
- [ ] Route discovery from app routes
- [ ] Local file storage
- [ ] Authentication bypass for screenshots

### Enhanced (4-6 hours)
- [ ] GitHub Actions workflow
- [ ] Multiple viewport sizes
- [ ] Git LFS for screenshot storage
- [ ] Diff detection between captures

### Advanced (8-12 hours)
- [ ] Visual diff reports
- [ ] Timeline web interface
- [ ] Slack/Discord integration
- [ ] Cloud deployment option

## Configuration Options

### Environment Variables
```bash
SCREENSHOT_MODE=development  # development, production, test
SCREENSHOT_AUTH_USER=screenshot@example.com
SCREENSHOT_VIEWPORT_WIDTH=1920
SCREENSHOT_VIEWPORT_HEIGHT=1080
SCREENSHOT_STORAGE_PATH=./screenshots
SCREENSHOT_NOTIFY_WEBHOOK=https://hooks.slack.com/...
```

### Routes Configuration
```json
{
  "routes": [
    {
      "path": "/app",
      "name": "dashboard", 
      "auth": true,
      "wait_for": "#main-content",
      "exclude_mobile": false
    },
    {
      "path": "/app/calendar/year",
      "name": "calendar",
      "auth": true,
      "setup_data": "create-sample-events"
    }
  ],
  "viewports": [
    {"width": 1920, "height": 1080, "name": "desktop"},
    {"width": 768, "height": 1024, "name": "tablet"},
    {"width": 375, "height": 667, "name": "mobile"}
  ]
}
```

## Success Criteria

1. ✅ **Automated Capture**: Screenshots captured without manual intervention
2. ✅ **Complete Coverage**: All authenticated routes documented
3. ✅ **Historical Tracking**: Visual progression over time
4. ✅ **Change Detection**: Notifications for significant UI changes  
5. ✅ **Easy Access**: Simple way to view and compare screenshots
6. ✅ **Low Maintenance**: Minimal ongoing effort required

## Future Enhancements

- **Visual Regression Testing**: Automatic detection of unintended changes
- **Component Screenshots**: Individual component capture for design system
- **Performance Metrics**: Capture loading times and performance data
- **A/B Testing**: Compare different design approaches
- **User Journey Screenshots**: Capture multi-step workflows
- **Integration with Design Tools**: Export to Figma/Sketch for design reviews
