# App-Level Screenshot Runner

## Goal
Capture authenticated screenshots of essentially every UI route straight from a live dev instance, using a single command that can become part of the roadmap deliverables and future CI automation.

## Proposed Architecture
1. **Biff task entry point (`clj -M:dev screenshots`)**
   - Fetches a valid session cookie for a designated “docs” account.
   - Writes cookie + metadata into `tmp/playwright-auth.json`.
   - Shells out to Playwright (`npx playwright test --config playwright.config.ts --project screenshots`) with env vars:
     - `PLAYWRIGHT_BASE_URL=http://localhost:8080` (or whatever dev URL the user already started manually).
     - `PLAYWRIGHT_COOKIE_FILE=tmp/playwright-auth.json`.
     - `PLAYWRIGHT_ROUTE_FILE=resources/screenshot-routes.edn`.
   - Exits non-zero if Playwright reports any navigation failure so the screenshots task can gate future automation.

2. **Dev-only session endpoint**
   - Add `POST /_dev/session-cookie` guarded by `(biff.config/dev?)`.
   - Handler payload: `{:email "docs@example.com" :scopes [:standard]}`.
   - Looks up/creates the user, issues a session via Biff’s auth helpers, and returns:
     ```json
     {"cookies":[{"name":"uid","value":"...","domain":"localhost","path":"/","httpOnly":true}]}
     ```
   - The Biff task calls this endpoint (via `clj-http`) before invoking Playwright, keeping auth deterministic with no magic-link parsing.

3. **Playwright harness**
   - `global-setup.ts` reads `PLAYWRIGHT_COOKIE_FILE` and loads cookies into the context via `context.addCookies`.
   - `screenshot.spec.ts` iterates the route manifest (see below), opens each path at one or more viewports, and writes images to `screenshots/<timestamp>/<slug>.png`.
   - Metadata file (JSON) records commit hash, viewport, and success/failure per route for roadmap documentation.
   - Future CI usage: same setup, but the task would hit a staging URL instead of localhost and could push artifacts to S3/LFS.

## Route Inventory Strategy
### Manifest File
Store the authoritative list in `resources/screenshot-routes.edn`:
```clojure
{:routes
 [{:slug "home"            :path "/app"                     :auth true}
  {:slug "habits"          :path "/app/habits"              :auth true}
  {:slug "habit-log-form"  :path "/app/crud/form/habit-log/new" :auth true :state {:query {:preset "morning"}}}
  {:slug "signin"          :path "/signin"                  :auth false}]}
```

### Maintenance
1. Treat it like code: PRs update the EDN file whenever navigation changes.
2. Optionally add a helper (`clj -M:dev suggest-screenshot-routes`) that walks the router and suggests missing entries; a human reviews/commits the changes.
3. Annotate tricky routes (feature flags, modal params) with per-route setup data consumed by Playwright.

## Execution Flow
1. Developer manually runs the dev server (per the “never run servers” rule for agents).
2. Run `clj -M:dev screenshots --base-url http://localhost:8080 --email docs@example.com`.
3. Task:
   - Calls `/_dev/session-cookie` to mint auth cookies.
   - Writes `tmp/playwright-auth.json`.
   - Invokes Playwright, which loads cookies and navigates each route, saving screenshots.
4. Images land in `roadmap/screenshots/<ISO-timestamp>/…` (git-ignored or tracked via LFS, depending on storage choice).

## Open Questions
- Should we keep screenshots in the repo (smaller sets, git-tracked) or export to an external bucket and only store metadata locally?
- How many viewports per run (desktop only vs. desktop/tablet/mobile) and do we gate them behind separate Playwright projects?
- Do we diff screenshots automatically (Backstop-style) or just archive them for future documentation?

## Immediate Tasks
1. Implement `/_dev/session-cookie` endpoint + helper to mint cookies.
2. Add `screenshots` Biff task that glues cookie generation to the Playwright command.
3. Create `global-setup.ts`, `screenshot.spec.ts`, and `resources/screenshot-routes.edn` seed file.
4. Document workflow in `README.md` + `roadmap/screenshots.md` so humans know to start the dev server first, then run the task.
