# Maintainer Reference

Internal notes for you (and AI agents) that were intentionally removed from the root `README.md` to keep it visitor-friendly.

## Canonical References

- `AGENTS.md` for project-specific rules and guardrails
- `AGENT_BLUEPRINT.md` for shared agent policy
- `roadmap/index.md` for canonical roadmap status
- `roadmap/README.md` for roadmap conventions

## Local Validation

Run these after code changes:

```bash
just check
```

Additional validation when needed:

```bash
clj -M:dev test <namespace>
just validate
```

## Visualization Rendering Pattern

Visualization flow is intentionally generic and schema-driven:

1. Build ECharts options in Clojure route/view code.
2. Render a chart container with `data-chart-data` pointing to a hidden JSON payload element.
3. `resources/public/js/main.js` discovers `[data-chart-data]` elements and initializes charts automatically.

Primary files:

- `src/tech/jgood/gleanmo/viz/routes.clj`
- `resources/public/js/main.js`

## New Entity Checklist

Use this order when adding a new entity:

1. Add schema in `src/tech/jgood/gleanmo/schema/<entity>_schema.clj`.
2. Register schema and ID type in `src/tech/jgood/gleanmo/schema.clj`.
3. Add app routes via `crud/gen-routes` in `src/tech/jgood/gleanmo/app/<entity>.clj`.
4. For temporal logs, add visualization routes via `viz.routes/gen-routes`.
5. Wire routes in `src/tech/jgood/gleanmo/app.clj`.
6. Add dashboard links in `src/tech/jgood/gleanmo/app/dashboards.clj`.
7. If custom form inputs are added, include `data-original-value` for changed-field highlighting.
8. Keep DB access in `src/tech/jgood/gleanmo/db/queries.clj` and `src/tech/jgood/gleanmo/db/mutations.clj`.
9. Validate with `just check` and targeted tests.

## Conventions To Preserve

- Namespace prefix: `tech.jgood.gleanmo.*`
- Kebab-case for vars/functions
- Schemas use `:closed true` and standard field ordering
- Filter deleted entities: `(not [?e ::sm/deleted-at])`
