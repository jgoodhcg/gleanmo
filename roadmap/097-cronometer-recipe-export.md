---
title: "Cronometer Recipe Export"
status: active
description: "Export custom recipes and their ingredient foods through the existing Cronometer client, without importing into Gleanmo."
created: 2026-10-15
updated: 2026-10-15
tags: [cronometer, export, nutrition]
priority: medium
---

# Cronometer Recipe Export

## Intent

Extract the user's custom recipe collection for analysis through the existing CLI integration.
Include recipes absent from recent diary entries.

## Specification

- Register `biff cronometer-export-recipes [--expected-count N]`.
- Reuse the probe's credentials and cached session.
- Discover candidates through an empty `find_food` search on the Custom tab.
- Retrieve full recipes and recursive ingredient foods in batches of 50.
- Preserve original amounts, measures, nutrient values, notes, and other food fields in JSON.
- Include the existing nutrient ID catalog for labels and units.
- Write direct ingredient edges to CSV, including names, measure metadata, and original amounts.
- Create a unique export directory under gitignored `cronometer_data/` each run.
- Reject missing food records and mismatched expected recipe counts.
- Label catalog coverage unverified unless the supplied count matches.

## Validation

- Synthetic HTTP tests verify request payloads and session reuse without network calls.
- Synthetic collection tests cover unused recipes, nested recipes, cycles, duplicate candidates, batching, and missing foods.
- Output tests check JSON preservation and CSV quoting.
- Run `just lint-fast`, `just check`, targeted tests, and `just validate`.
- User runs the task and compares the recipe count with Cronometer.

## Scope

CLI only; no app UI, database access, sync changes, or Cronometer writes.
Live Cronometer calls remain user-run under the integration's existing policy.
Retired recipes omitted by search cannot be discovered by this task.

## Context

- `dev/tasks/cronometer.clj` owns probe credentials and file-backed sessions.
- `src/tech/jgood/gleanmo/cronometer/client.clj` owns HTTP calls.
- [056-cronometer-integration.md](./056-cronometer-integration.md) documents API constraints.
- [Reference client](https://github.com/rwestergren/cronometer-api-mcp/blob/d76372f0fcf2da0c47893a2467d76ff26819ea20/src/cronometer_api_mcp/client.py) documents `find_food` with the All tab.

The empty query and `CUSTOM` tab are an unverified request variant.
The reference client does not implement a complete custom-food catalog endpoint.
Search truncation and pagination behavior are unknown.
Matching a user-supplied recipe count checks quantity, not identity or upstream completeness guarantees.

## Usage

Use the existing `CRONOMETER_EMAIL` and `CRONOMETER_PASSWORD` environment variables or local `config.env` entries.
The task shares `cronometer_data/session.edn` with `cronometer-probe`.
It does not require stopping the dev server.

```sh
biff cronometer-export-recipes --help
biff cronometer-export-recipes --expected-count 42
```

Replace `42` with the recipe count shown in your app.
Each successful run prints a new `cronometer_data/recipes-<unique>/` directory containing:

- `recipes.json`: root recipes, reachable food records, discovery metadata, and nutrient definitions.
- `ingredients.csv`: one row per direct ingredient edge, including nested recipes' own edges.

`grams_raw` retains Cronometer's `grams` value.
For serving-based nested recipes, that value can represent a serving count.
Use the referenced food's measure type and full JSON to interpret quantities.
No conversion or nutrient scaling occurs during export.

If the search returns no recipes, an unexpected response, or a count mismatch, the task stops without writing an export.
Missing ingredient records also stop the export.
The new catalog request must be checked against the real account before claiming a complete collection.

## Progress

- Implemented the CLI task, Custom-tab request, recursive collection, JSON export, and ingredient CSV.
- Targeted tests passed: 6 tests, 39 assertions.
- `just validate` passed: formatting, lint, and 220 tests with 2,335 assertions.
  Lint reported 24 warnings outside the changed files.
- `just lint-fast` passed on every changed Clojure file with no warnings.
- `git diff --check` passed.
- Remaining: user-run catalog discovery and comparison with the recipe collection in Cronometer.
- Live API and browser checks were not run; this change has no app UI.
