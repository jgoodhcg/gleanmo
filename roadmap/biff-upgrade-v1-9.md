---
title: "Upgrade Biff to v1.9.0+"
status: ready
description: "Upgrade Biff and task libs to at least v1.9.0, then validate XTDB/Agrona changes and Java 25 compatibility"
tags: [area/dependencies, type/maintenance, tech/biff, tech/xtdb]
priority: medium
created: 2026-02-09
updated: 2026-02-09
---

# Upgrade Biff to v1.9.0+

## Intent

Upgrade the app from `com.biffweb/biff` `v1.8.27` to at least `v1.9.0` to get current framework/runtime updates, then validate whether the XTDB chain now avoids the current `org.agrona` `sun.misc.Unsafe` warning path on modern JDKs.

## Constraints

- Keep production behavior stable while upgrading dependencies.
- Preserve Docker-based deploy and Java 25 runtime compatibility.
- Avoid direct XTDB access changes in handlers/views; keep data access through query/mutation namespaces.
- Treat this as a dependency/compatibility upgrade, not a feature rewrite.

## Specification

1. Update `deps.edn` `com.biffweb/biff` to `v1.9.0` or newer with matching git SHA.
2. Update `com.biffweb/tasks` in the `:dev` alias to a compatible `v1.9.x` tag/SHA.
3. Reconcile any compile/runtime breakages introduced by Biff/XTDB/API changes.
4. Capture the resulting `xtdb-*` and `org.agrona/agrona` versions from `clj -Stree`.
5. Re-run unsafe diagnostics (`--sun-misc-unsafe-memory-access=debug`) and document whether warnings remain.
6. Validate whether both current JVM flags are still required after upgrade:
   - `--enable-native-access=ALL-UNNAMED`
   - `--sun-misc-unsafe-memory-access=allow`
   Record a keep/remove decision for each flag with evidence.

## Validation

- `just check`
- `clj -Stree | rg "xtdb|agrona|com.biffweb"`
- `clj -J--sun-misc-unsafe-memory-access=debug -M:dev uberjar`
- Test matrix for flag necessity (document outcomes):
  - build/start without `--enable-native-access=ALL-UNNAMED`
  - build/start without `--sun-misc-unsafe-memory-access=allow`
- If routing/config changes are required, run focused tests: `clj -M:dev test <namespace>`

## Context

Current dependency tree includes `com.xtdb/xtdb-core 1.23.1` and `org.agrona/agrona 1.16.0`, with debug traces showing `org.agrona.UnsafeAccess` as the current warning source under modern JDKs.

## Open Questions

- Does Biff `v1.9.x` still pin XTDB 1.x, or does it require a broader migration path?
- If Agrona warning remains after upgrade, should we keep suppression flags short-term or pin a newer XTDB/Agrona directly?
- After upgrade, are both flags still necessary, or can one/both be removed safely?
- Are there any deploy platform changes needed beyond Java 25 image updates?

## Notes

- Created from production build failure triage and Java 25 migration work.
