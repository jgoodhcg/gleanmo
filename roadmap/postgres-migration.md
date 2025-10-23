# Postgres Migration Decision Log

## Outcome
- **Status:** Migration paused indefinitely; XTDB remains the primary datastore.
- **Date:** 2025-10-22
- **Decider:** Justin Good

## Why We’re Sticking With XTDB
- **Query ergonomics:** Our entity maps rely heavily on Malli metadata and domain timestamps. Encoding them as tagged JSONB means every SQL query must peel through `__gleanmo$type`/`__gleanmo$value` wrappers—unpleasant and hard to index.
- **Projection surface area:** Many user-facing features (calendar views, project timers, dashboards) sort/filter on timestamps. Mirroring those fields into first-class columns or generated columns would defeat the “single JSONB table” simplification we originally hoped for.
- **Cost vs. benefit:** XTDB already powers the app reliably. The extra engineering required to build a production-grade Postgres schema (or generated-column strategy) would exceed the friction we were trying to remove.
- **Developer flow:** We are productive in Clojure + XTDB today. Rewriting the read/write layer before we have a relational design simply slows down feature work.

## What Happens Next
- **Stay on XTDB:** Continue iterating on features using the existing Biff/XTDB stack.
- **Document pain points:** Capture the real XTDB rough edges (tooling, migrations, inspection) so future rewrites have a clear target.
- **Harvest the experiments:** Keep the codec/datasource experiments around as reference, but don’t wire them into production.

## When To Revisit Postgres (or Another Store)
- Need for typed analytics or BI integrations that XTDB cannot satisfy without heavy lifting.
- Requirement for conventional SQL access by other teams/tools.
- Performance issues that XTDB can’t meet after profiling and tuning.
- A greenfield rewrite opportunity where we can design a relational schema up front.

Until one of those triggers fires, we’ll invest in XTDB-focused improvements (better fixtures, admin utilities, query helpers) instead of a full datastore migration.
