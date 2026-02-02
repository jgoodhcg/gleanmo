---
title: "Infrastructure Move (Neon to DigitalOcean)"
status: idea
description: "Database migration from Neon to DigitalOcean"
tags: []
priority: medium
created: 2026-02-02
updated: 2026-02-02
---

# Infrastructure Move (Neon to DigitalOcean)

## Work Unit Summary
- Problem / intent: Move the database off Neon to DigitalOcean for cost/control and infrastructure consolidation.
- Constraints: Preserve data integrity, avoid extended downtime, and keep backups and rollbacks available.
- Proposed approach: Select a target hosting model (managed Postgres or self-managed), stage a replica or export/import, validate consistency, then cut over with a brief maintenance window.
- Open questions: Which DigitalOcean offering (managed vs self-hosted)? What is the acceptable downtime window? What backup and rollback plan do we want before cutover?

## Notes
### Migration outline
- Inventory current Neon database size, extensions, and retention requirements.
- Decide on target: DigitalOcean Managed Postgres or a self-hosted Postgres droplet.
- Plan data transfer (logical dump/restore vs streaming replication).
- Set up monitoring, backups, and alerts before cutover.
- Execute a timed cutover and update config/secrets.
