# Goals performance audit

`goals.clj` builds disposable in-memory XTDB nodes from deterministic synthetic records.
It does not load application configuration or access local or production application databases.
Fixture writes use Biff's test-node constructor; application queries and goal calculations run unchanged.

Run latency measurements from the repository root:

```sh
clj -J-Xmx3g \
  -J--enable-native-access=ALL-UNNAMED \
  -J--sun-misc-unsafe-memory-access=allow \
  -Sdeps '{:paths ["src" "resources" "dev"]}' \
  -M -m benchmarks.goals 9 1000 5000
```

Arguments are sample count followed by records per source per user for each scale.
There are eight sources, two users, and additional exercise sets and related entities.
Each user has 13 goals, covering every registered measurement.
Reading history is distributed across 100 books per user.
Dates span up to ten years, with 84-day source reads, annual dashboards, and ten-year dashboards.
The larger fixture contains 90,236 documents, excluding Biff's transaction function.
These are synthetic scale assumptions, not measured production counts.

The benchmark waits for XTDB attribute statistics before taking its first query snapshot.
Transaction indexing alone does not guarantee that planner statistics are ready.
A first query with missing statistics can cache a poor plan and distort subsequent measurements.

Each case records its first invocation, performs two warmups, then reports the median and sample p95.
With nine samples, the reported p95 is the maximum; it is not a reliable production tail-latency estimate.
The first invocation is not a cold-process measurement because preceding cases share the JVM and node.
Seeding, statistics waiting, and diagnostic instrumentation are outside the measured samples.
Correctness checks verify source counts, owner IDs, book scope, and dashboard goal counts.

The preloaded-goal cases replace only `goals-for-user`, preserving actual source queries and calculations.
Compare `dashboard-39-year` with `dashboard-13-year-preloaded` to isolate goal-count overhead.
The ten-year case also preloads goals; add the separately measured goal-list cost when interpreting a full request.
No browser rendering, HTTP, RocksDB, PostgreSQL, or network latency is measured.

Capture plans in a separate process:

```sh
env GOALS_BENCH_PLANS=1 clj -J-Xmx3g \
  -J--enable-native-access=ALL-UNNAMED \
  -J--sun-misc-unsafe-memory-access=allow \
  -J-Dorg.slf4j.simpleLogger.log.xtdb.query=debug \
  -Sdeps '{:paths ["src" "resources" "dev"]}' \
  -M -m benchmarks.goals 1 5000
```

Plan mode runs each case once and labels it in the query log.
Its timings include debug logging and must not replace the latency run.
Separate annual and ten-year diagnostic invocations report query shapes, row counts, timings, and relationship-schema extraction counts.
The September 2026 findings are recorded in `roadmap/081-goals-dashboard.md`.
