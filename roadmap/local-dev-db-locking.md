---
title: "Local Dev Database Locking"
status: draft
description: "RocksDB file lock prevents running REPL and CLI migrations concurrently"
created: 2026-02-15
updated: 2026-02-15
tags: [dx, infrastructure]
priority: medium
---

# Local Dev Database Locking

## Intent

The standalone XTDB node uses RocksDB, which holds an exclusive file lock on `storage/xtdb`. This means the dev server (with REPL) and CLI migration tasks cannot run at the same time. You have to stop the dev server to run a migration, losing REPL access for debugging.

## Constraints

- Must not break existing dev or prod workflows.
- Migration CLI must still work against both dev and prod targets.

## Options

1. **Containerized Postgres locally** — Run a local Postgres (via Docker) so the dev server and CLI tools connect over JDBC instead of competing for a RocksDB lock. Aligns with prod topology and eliminates the locking issue entirely.
2. **Standalone REPL outside dev server** — Provide a REPL alias that doesn't start the XTDB node, allowing code evaluation and exploration while the migration CLI holds the lock. Doesn't solve the core problem but unblocks basic REPL use during migrations.
3. **Shared node via nREPL** — Have the migration task connect to the already-running dev server's nREPL and use its XTDB node, avoiding a second lock. Adds coupling between migration CLI and dev server lifecycle.

## Open Questions

- Is local Postgres the right long-term play given the DigitalOcean infra move?
- If we go with containerized Postgres, should we use docker-compose or a simple `docker run` script?
