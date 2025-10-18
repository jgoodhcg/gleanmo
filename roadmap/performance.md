# Performance Monitoring Requirements

## Goals
- Capture per-request and database-layer latencies without printing noisy console logs.
- Persist periodic summaries so historical metrics are queryable via XTDB entity history.
- Surface metrics through an in-app dashboard instead of raw log output.
- Provide concrete visibility into known slow paths, starting with the habit-log visualization queries.

## Architecture Sketch
- Initialize Tufte once at system startup; wrap HTTP handlers and DB helpers with profiling blocks.
- Schedule a Chime task that flushes aggregated metrics into a single `:performance-report` document; rely on XTDB immutability for time-series history.
- Provide a `/app/metrics/performance` route that reads the document’s history and renders charts using the existing viz UI helpers.
- Store the latest snapshot for each node under `:performance-report/global`; rely on XTDB history for time-series data rather than accumulating vectors in the document.
