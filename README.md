# Gleanmo

Personal quantified-self web app I built for my own daily tracking and reflection.

## What This Is

- A real app I actively use.
- Designed with me as the primary audience.
- Public mostly so people can see how I build and think.

Gleanmo is technically multi-user and schema-driven, but it is not positioned as a plug-and-play product.

## Why I Built It

- I wanted one place to track habits, exercise, projects, and other life data.
- I wanted a flexible Clojure playground for personal data visualization.

## What It Supports

- Schema-driven CRUD for many personal data types
- Temporal activity logs (point-in-time and interval-based)
- Visualization pages (calendar heatmaps and related charts)
- User settings and privacy-aware filtering
- Email-based auth with reCAPTCHA

## Built With

- Clojure 1.11.1
- Biff + XTDB
- Rum + HTMX + Tailwind + ECharts

## Where To Look First

- `src/tech/jgood/gleanmo/app/dashboards.clj` - entry points and app navigation
- `src/tech/jgood/gleanmo/crud/routes.clj` - generic CRUD route generation
- `src/tech/jgood/gleanmo/schema.clj` - schema registry
- `src/tech/jgood/gleanmo/viz/routes.clj` - visualization route generation
- `src/tech/jgood/gleanmo/db/queries.clj` - read layer
- `src/tech/jgood/gleanmo/db/mutations.clj` - write layer

## Notes For Visitors

- This repo is optimized for active development, not end-user onboarding.
- Setup and deployment are intentionally light on hand-holding.

## Maintainer Docs

- `AGENTS.md` - project-specific agent/development rules
- `docs/maintainer-reference.md` - internal implementation and extension notes
- `roadmap/index.md` - canonical roadmap state

## License

All rights reserved. Shared for reference and educational purposes.
