---
title: "Infrastructure Move (Neon to DigitalOcean)"
status: active
description: "Database migration from Neon to DigitalOcean"
tags: []
priority: high
created: 2026-02-02
updated: 2026-10-08
---

# Infrastructure Move (Neon to DigitalOcean)

## Work Unit Summary
- Problem / intent: Move the database off Neon to DigitalOcean to eliminate recurring Hikari/Neon connection friction and consolidate infrastructure.
- Constraints: Preserve data integrity, avoid extended downtime, and keep backups and rollbacks available.
- Proposed approach: Choose the lowest-maintenance DigitalOcean target, stage an export/import or replica, validate consistency, then cut over with a brief maintenance window.
- Open questions: Which DigitalOcean offering (managed vs self-hosted)? What is the acceptable downtime window? What backup and rollback plan do we want before cutover? Which Hikari settings should be carried forward or simplified after leaving Neon?

## Notes

### Current backup method
- Existing backup script uses `pg_dump -Fc` (custom format) with Neon credentials.
- After migration, backups need updating: either update the script credentials to point at the new DigitalOcean database, or switch to a DigitalOcean-native backup strategy (e.g., managed database automatic backups).

### Migration outline
- Inventory current Neon database size, extensions, and retention requirements.
- Decide on target: DigitalOcean Managed Postgres or a self-hosted Postgres droplet.
- Plan data transfer (logical dump/restore vs streaming replication).
- Current dumps are custom format (`-Fc`), so restore will use `pg_restore`.
- Set up monitoring, backups, and alerts before cutover.
- Execute a timed cutover and update config/secrets.

### What actually moves (verified 2026-10-08)

Prod runs Biff's `:jdbc` topology on XTDB 1.23.1 (Biff `impl/xtdb.clj`):
the tx-log **and** document store both live in Postgres, in XTDB's
`tx_events` table. The index store is RocksDB on the container's local disk,
which is thrown away on every deploy and rebuilt by replaying `tx_events`.

So the move is a plain Postgres copy of one table. No XTDB export, no code
change. Things that must survive the copy:

- `event_offset` values **are the XTDB tx-ids**. `pg_dump`/`pg_restore`
  keep them and the sequence's position, so a dump/restore is safe; a
  row-by-row re-insert would not be.
- Restore **before** the app ever connects to the new database. XTDB runs
  `CREATE TABLE IF NOT EXISTS` on start, and Biff's `save-tx-fns!` submits a
  transaction when tx-fns differ — an empty DB touched by the app first will
  collide with the restore.
- Any write to Neon after the dump is lost. Writers are: the user, and
  `reconcile-timer-flags` (`worker.clj`, daily 09:00 UTC). Keep the window
  away from 09:00 UTC.

### Runbook

Agents prepare; the user runs every step that touches prod.

**0. Inventory Neon (read-only)**

```sh
NEON='postgresql://…neon.tech/…?sslmode=require'   # XTDB_JDBC_URL minus the jdbc: prefix
psql "$NEON" -c 'select version()'
psql "$NEON" -c '\dt+'
psql "$NEON" -c "select pg_size_pretty(pg_database_size(current_database()))"
psql "$NEON" -c 'select count(*), max(event_offset) from tx_events'
```

Local `pg_dump` is 16.9. If Neon's major version is newer,
`brew install postgresql@<N>` first — `pg_dump` refuses newer servers.

**1. Provision DO Managed Postgres** (no downtime)

- Same region as the App Platform app; smallest plan (1 vCPU / 1 GB) is
  plenty; major version ≥ Neon's.
- Create a database and a non-admin user for the app (don't run the app as
  `doadmin`).
- Trusted sources: add the App Platform app, plus your current IP for the
  restore and future `--target prod` migrations.
- Enable alerts (CPU, disk, connections). Automatic daily backups and 7-day
  PITR come with the managed plan.

**2. Rehearsal** (no downtime; repeat until boring)

```sh
pg_dump -Fc --no-owner --no-acl "$NEON" -f neon-rehearsal.dump
pg_restore --no-owner --no-acl -d "$DO" neon-rehearsal.dump
psql "$DO" -c 'select count(*), max(event_offset) from tx_events'   # must match step 0
```

Optionally boot a local node against `$DO` to prove a full replay works. That
writes to the rehearsal DB (tx-fns), so drop and recreate the database before
the real cutover.

**3. Cutover** (minutes; stay off the app)

1. Stop using the app; note Neon's `count(*), max(event_offset)`.
2. `pg_dump -Fc --no-owner --no-acl "$NEON" -f neon-final.dump` — keep this
   file, it is the rollback point.
3. Restore into the fresh DO database; compare `count(*), max(event_offset)`.
   They must match exactly.
4. App Platform → env vars: set `XTDB_JDBC_URL` to
   `jdbc:postgresql://<host>:25060/<db>?user=<u>&password=<p>&sslmode=require`.
   Saving redeploys.
5. Watch `doctl apps logs <app-id> --type run --follow` until
   `Indexed` reaches the expected tx-id; then spot-check recent logs,
   timers, and goals in the UI.
6. Update local `config.env`'s prod URL and the backup script to DO.

**Rollback:** set `XTDB_JDBC_URL` back to Neon. Valid only until the first
write lands on DO; after that, dump DO and restore into Neon instead.

**4. After a quiet week**

- Revisit the Neon-specific Hikari settings in `resources/config.edn`.
  `maxLifetime` 5 min / `idleTimeout` 2 min exist only because Neon suspends;
  DO doesn't. Keep the pool small — the 1 GB plan allows ~22 connections.
- Delete the Neon project once a DO backup has been restored somewhere and
  checked.
