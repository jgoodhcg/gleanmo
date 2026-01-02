# Cognitive Games Roadmap

## Work Unit Summary
- Status: idea
- Problem / intent: Add cognitive games to track performance trends with fresh puzzle content.
- Constraints: Puzzle generation must avoid repeats and store enough metadata for analytics.
- Proposed approach: Define puzzle/guess schemas, add an AI generator, then build game UIs and dashboards.
- Open questions: Should puzzles be pre-generated or on-demand? Is single-user only acceptable for v1?

Introduce Wordle- and Connections-style games as on-demand experiences to measure cognition trends while keeping puzzle content fresh via AI generation.

## Objectives
- Provide quick daily cognitive workouts that log detailed performance metrics.
- Use AI to generate bespoke puzzles with difficulty metadata and repetition safeguards.
- Surface results in a dedicated “Games” dashboard with streak tracking and charts.

## Scope
1. **Domain Modeling**
   - `game/puzzle`: puzzle type (`:wordle` | `:connections`), solution payload (word or grouped categories), difficulty tag + rationale, AI prompt hash/input excerpt, generation timestamp, status (`:pending`, `:active`, `:completed`, `:invalid`), and owning user.
   - `game/guess`: reference to puzzle, guess text/order, per-letter feedback encoding (Wordle) or selected group path (Connections), timestamp, outcome flags (correct, strike), and derived stats (time offset from puzzle start).
   - Store Connections group metadata (title + four items + explanation) for reveal screens and result exports.

2. **Puzzle Generation**
   - Build a generator service task using the existing AI integration: collects recent puzzle solutions to avoid repeats, crafts prompts per game mode, parses responses, validates schema, and writes puzzles to XTDB.
   - Support on-demand generation triggered from the UI; queue request, persist puzzle before play begins, and show loading state until ready.
   - Record difficulty metadata and AI confidence so later analytics can correlate performance vs. challenge level.

3. **Gameplay Experience**
   - Add a sidebar “Games” link that opens a dashboard listing active/completed puzzles and a “New Puzzle” CTA per mode.
   - Wordle UI: 5-letter, 6-guess board with on-screen keyboard, color feedback, and emoji export built from stored guess feedback.
   - Connections UI: 4x4 grid, selection interaction with strikes/confirmations, dynamic grouping reveal, and final summary showing rationales.
   - Persist each guess immediately with timestamp + feedback to maintain a full play log even if the session reloads.

4. **Analytics & Reporting**
   - Track completion status, guesses used, solve duration, per-difficulty performance, streaks, and failure reasons.
   - Extend visualization layer to chart guess distributions, streak timelines, and difficulty-adjusted scores on the Games dashboard.
   - Provide export/share snippets (emoji grid for Wordle, grouped emojis/colors for Connections) using stored guess feedback.

5. **Safety & Ops**
   - Validate that only one active puzzle per mode exists per user; auto-complete or archive stale puzzles.
   - Rate-limit AI generation requests; log prompt/response pairs for auditing; provide admin tooling to edit or invalidate problematic puzzles.
   - Add smoke tests for puzzle schema validation and guess recording to ensure future changes don’t regress tracking fidelity.

## Open Questions
- Should puzzles ever be pre-generated in batches for offline play, or is on-demand sufficient?
- Do we need multi-user leaderboards, or is the initial release single-user only?
- What retention period is needed for raw guesses vs. aggregated stats (for storage planning)?
