# Rewrite Analysis: Language & Stack Evaluation

**Date**: January 2025
**Status**: Deferred (staying with Clojure for now)

## Goals

1. **AI-optimized development** - Maximize AI code generation quality and autonomous iteration (AI can self-correct via compiler feedback without intervention)
2. **Role as guide** - Follow along, read the code, unstick hard problems, direct at a high level
3. **Easy deployment** - DigitalOcean App Platform compatibility
4. **Longevity** - Minimal churn, low deprecation risk, technology that lasts 5-10+ years
5. **Durable data** - Managed Postgres preferred (automatic backups, scaling option, ~$15/mo)
6. **Career optionality** - Be positioned for where AI consolidates the language ecosystem

## The Project

A personal quantified-self/lifestyle tracking application:

- **Size**: ~7,800 lines of Clojure across 58 source files
- **Entities**: 13 trackable types (tasks, habits, meditation, medication, exercise, projects, calendar events, locations, etc.)
- **Architecture**: Schema-driven generic CRUD system, timer subsystem, ECharts visualizations
- **Current stack**: Clojure/Biff, XTDB (embedded bitemporal DB), HTMX, Tailwind CSS
- **Complexity**: Medium - clear architecture, mostly CRUD + filtering, but sophisticated schema-driven abstractions

## Options Evaluated

| Language/Stack | AI Code Gen | AI Autonomous Iteration | Readability | Deployment | Longevity | Consolidation Bet |
|----------------|-------------|------------------------|-------------|------------|-----------|-------------------|
| **Clojure** (current) | Fair | Weak (runtime only) | Excellent (fluent) | Moderate (JVM) | Good (stable) | Risky (niche) |
| **Go + Postgres + HTMX** | Good | Excellent (fast compiler) | Excellent (explicit) | Excellent (single binary) | Excellent | Strong |
| **TypeScript + Bun + HTMX** | Excellent | Very Good (tsc) | Good | Good | Good (with discipline) | Strongest |
| **Rust** | Fair | Excellent (strict compiler) | Moderate (complex) | Excellent (single binary) | Excellent | Strong (for systems) |
| **Python** | Excellent | Moderate | N/A (dislike) | Good | Good | Strong |
| **Elixir** | Fair | Moderate | Good | Moderate | Good | Risky (niche) |

## Key Findings

### AI Autonomous Iteration

Compile-time type checking creates a feedback loop where AI can self-correct without human intervention. Ranked by strictness:

1. **Rust** - Strictest (borrow checker catches memory/concurrency bugs)
2. **Go** - Strong (compiler + `go vet` + staticcheck)
3. **TypeScript** - Good (tsc catches type errors, but type system is unsound)
4. **Clojure** - Weak (runtime errors and tests only, no compile-time feedback)

### Language Consolidation Prediction

AI investment will likely consolidate around:

- **TypeScript** - Web/full-stack default
- **Python** - AI/ML/scripting
- **Go** - Backend services, infrastructure
- **Rust** - Systems, performance-critical

Niche languages (Clojure, Elixir, Haskell) will likely see declining AI investment over time.

### TypeScript Stability Strategy

Churn is concentrated in frontend frameworks (React ecosystem, Next.js). For backend/HTMX apps, stability is achievable with:

- **Runtime**: Bun (Anthropic-backed, fast, Node-compatible)
- **Framework**: Hono (minimal, multi-runtime) or none
- **Database**: Kysely (type-safe SQL) or raw driver
- **Avoid**: Next.js, Prisma, heavy abstractions

### Bun vs Deno

Anthropic acquiring Bun is a strong signal:

- Better Claude integration with Bun workflows expected
- Likely training emphasis on Bun patterns
- Strategic investment in the runtime's future

This tips the balance toward **Bun** for AI-focused TypeScript development.

## Stack Comparisons

### Go + Postgres + HTMX vs Current Stack

| Aspect | Clojure + XTDB + HTMX | Go + Postgres + HTMX |
|--------|----------------------|----------------------|
| Language stability | Stable (Clojure 1.0 in 2009) | Very stable (Go 1.0 in 2012, strict compat) |
| Framework stability | Biff is small/niche, single maintainer | Echo/Chi are mature, multiple maintainers |
| Database stability | XTDB is niche, breaking changes v1→v2 | Postgres is gold standard, 30+ years |
| AI code generation | Poor (~1% of training data) | Good (~5-8% of training data) |
| AI understanding | Struggles with macros, Lisp syntax | Handles well - explicit, straightforward |
| DO App Platform | Works but needs JVM, ~512MB+ RAM | Native binary, ~128MB RAM sufficient |
| Cold start | Slow (JVM startup 5-15s) | Fast (<1s) |
| Managed DB cost | N/A (XTDB is embedded) | DO Postgres: $15/mo |
| Backup complexity | Manual RocksDB snapshots | Automatic with managed Postgres |
| REPL development | Excellent (live reload) | None (but fast recompile ~1s) |

### What You'd Lose in Rewrite

- REPL-driven development (Clojure's killer feature)
- Concise code (Go is 1.5-2x more lines)
- Macros for metaprogramming (generic CRUD uses this)
- Bitemporal queries (though not needed)

### What You'd Gain

- AI can actually help write and modify code
- Managed Postgres with automatic backups, point-in-time recovery
- Faster deploys, lower memory usage, cheaper hosting
- Compile-time type checking catches bugs earlier
- Larger talent pool if help is ever needed
- Simpler mental model (no macros, no lazy sequences)

## Recommendations

| Project | Recommendation | Rationale |
|---------|----------------|-----------|
| **Gleanmo** | Stay with Clojure (for now) | Finish entity migration, evaluate rewrite later when AI tooling matures |
| **Word game** | Continue with Go | Already invested, seeing good AI performance, excellent fit |
| **Future web project** | Try TypeScript + Bun | Get experience with strongest AI ecosystem, test stability strategy |
| **Bevy/game engine** | Use Rust | Right tool for that domain, separate learning track |

## Decision

Stay with Clojure for Gleanmo until:

1. All planned entities are implemented
2. Data migration from Airtable and other sources is complete
3. AI tooling evolves further (provides comparison data)

Then evaluate rewrite with more information. Meanwhile, diversify experience across Go (current game) and TypeScript/Bun (future project) to build direct comparison data.

## Rewrite Scope (If/When)

If rewriting to Go + Postgres:

| Component | Complexity | Notes |
|-----------|------------|-------|
| Schema definitions | Low | Go structs are simpler than Malli |
| Generic CRUD system | Medium | Doable with reflection or code gen |
| 13 entity modules | Low-Medium | Mostly thin wrappers |
| Timer system | Medium | Isolated, manageable |
| Visualization | Low | ECharts stays the same |
| Auth/sessions | Low | Well-trodden path in Go |

**Estimated size**: ~5,000-7,000 lines of Go (more verbose but clearer)
