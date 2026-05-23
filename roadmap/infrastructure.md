---
title: "Infrastructure Move (Neon to DigitalOcean)"
status: active
description: "Database migration from Neon to DigitalOcean"
tags: []
priority: high
created: 2026-02-02
updated: 2026-05-16
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
